package com.example.tuneplay.ui.profile

import android.annotation.SuppressLint
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tuneplay.R
import com.example.tuneplay.databinding.ItemPlaylistCardBinding
import java.util.Collections
import kotlin.math.abs

/** 歌单卡片数据 */
data class PlaylistCardItem(
    val id: Long,
    val name: String,
    val songCount: Int,
    val coverUrl: String?
)

/**
 * 歌单卡片适配器 — 支持左滑露出圆形垃圾桶图标，点击垃圾桶删除。
 * 滑动进度驱动垃圾桶动画（alpha 0→1, scale 0.5→1），
 * 滑动超过 40% 卡宽时自动打开，超过 50% 时自动关闭。
 * 同时只有一个卡片处于展开状态 ([revealedPositions])。
 */
class PlaylistCardAdapter(
    private var items: List<PlaylistCardItem>,
    private val onClick: (PlaylistCardItem) -> Unit,
    private val onDeleteClick: (PlaylistCardItem) -> Unit,
    private val onItemMoved: ((fromPos: Int, toPos: Int) -> Unit)? = null
) : RecyclerView.Adapter<PlaylistCardAdapter.VH>() {

    val revealedPositions = mutableSetOf<Int>()

    private data class TouchState(
        var startX: Float = 0f,
        var swiping: Boolean = false
    )
    private val touchStates = mutableMapOf<Int, TouchState>()

    class VH(val binding: ItemPlaylistCardBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val binding = ItemPlaylistCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(binding)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindViewHolder(holder: VH, position: Int) {
        val item = items[position]
        holder.binding.tvPlaylistName.text = item.name
        holder.binding.tvSongCount.text = "${item.songCount} 首"
        Glide.with(holder.itemView.context)
            .load(item.coverUrl)
            .placeholder(R.drawable.ic_music_note)
            .centerCrop()
            .into(holder.binding.ivPlaylistCover)

        // Restore or reset card content position + trash icon state
        val cardContent = holder.binding.cardContent
        val trash = holder.binding.trashContainer
        if (revealedPositions.contains(position)) {
            cardContent.translationX = -revealWidth(holder)
            trash.alpha = 1f
            trash.scaleX = 1f
            trash.scaleY = 1f
        } else {
            cardContent.translationX = 0f
            trash.alpha = 0f
            trash.scaleX = 0.5f
            trash.scaleY = 0.5f
        }

        touchStates.remove(position)

        // Trash icon click → delete
        trash.setOnClickListener {
            if (revealedPositions.contains(position)) {
                closeRevealAt(position)
                onDeleteClick(item)
            }
        }

        // Touch handling on the root view
        holder.itemView.setOnTouchListener { view, event ->
            handleTouch(holder, event, position, item)
        }
    }

    override fun getItemCount(): Int = items.size

    fun updateItems(newItems: List<PlaylistCardItem>) {
        items = newItems
        touchStates.clear()
        closeAllReveals()
        notifyDataSetChanged()
    }

    fun getItems(): List<PlaylistCardItem> = items

    fun moveItem(fromPos: Int, toPos: Int) {
        closeAllReveals()
        Collections.swap(items, fromPos, toPos)
        notifyItemMoved(fromPos, toPos)
        onItemMoved?.invoke(fromPos, toPos)
    }

    fun closeAllReveals() {
        val toClose = revealedPositions.toSet()
        for (pos in toClose) closeRevealAt(pos)
    }

    fun closeRevealAt(position: Int) {
        touchStates.remove(position)
        if (!revealedPositions.remove(position)) return
        notifyItemChanged(position)
    }

    private fun revealWidth(holder: VH): Float {
        // Reveal enough to show the trash icon (44dp) + its margin (18dp) ≈ 62dp
        return holder.itemView.width * 0.22f
    }

    // --- Touch handling ---

    private fun handleTouch(holder: VH, event: MotionEvent, position: Int, item: PlaylistCardItem): Boolean {
        val maxDx = revealWidth(holder)
        val isRevealed = revealedPositions.contains(position)
        val cardContent = holder.binding.cardContent
        val trash = holder.binding.trashContainer

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                touchStates[position] = TouchState(startX = event.x)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val state = touchStates[position] ?: return false
                val dx = event.x - state.startX
                if (!state.swiping && abs(dx) > 12f) {
                    state.swiping = true
                    holder.itemView.parent.requestDisallowInterceptTouchEvent(true)
                }
                if (state.swiping) {
                    val newTx = if (isRevealed)
                        (-maxDx + dx).coerceIn(-maxDx, 0f)
                    else
                        dx.coerceIn(-maxDx, 0f)
                    cardContent.translationX = newTx
                    // Animate trash icon based on reveal progress
                    val progress = abs(newTx) / maxDx // 0..1
                    trash.alpha = progress
                    trash.scaleX = 0.5f + 0.5f * progress
                    trash.scaleY = 0.5f + 0.5f * progress
                    return true
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val state = touchStates.remove(position)
                holder.itemView.parent.requestDisallowInterceptTouchEvent(false)

                if (state != null && state.swiping) {
                    val tx = cardContent.translationX
                    if (isRevealed && tx > -maxDx * 0.5f) {
                        // Close: hide trash, slide card back
                        closeRevealAt(position)
                    } else if (!isRevealed && tx < -maxDx * 0.4f) {
                        // Open: reveal trash
                        closeAllOtherReveals(position)
                        revealedPositions.add(position)
                        cardContent.animate().translationX(-maxDx).setDuration(200).start()
                        trash.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200).start()
                    } else if (isRevealed) {
                        // Snap back to revealed
                        cardContent.animate().translationX(-maxDx).setDuration(150).start()
                        trash.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(150).start()
                    } else {
                        // Snap back to closed
                        cardContent.animate().translationX(0f).setDuration(150).start()
                        trash.animate().alpha(0f).scaleX(0.5f).scaleY(0.5f).setDuration(150).start()
                    }
                    return true
                }
                // Tap on card (no swipe)
                if (isRevealed && event.x > holder.itemView.width - maxDx) {
                    // Tapped the revealed area → delete via trash click simulation
                    trash.performClick()
                } else if (isRevealed) {
                    closeAllReveals()
                } else {
                    closeAllReveals()
                    onClick(item)
                }
                return true
            }
        }
        return false
    }

    private fun closeAllOtherReveals(keep: Int) {
        for (pos in revealedPositions.toSet()) if (pos != keep) closeRevealAt(pos)
    }
}
