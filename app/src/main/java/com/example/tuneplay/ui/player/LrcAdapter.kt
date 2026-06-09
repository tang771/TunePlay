package com.example.tuneplay.ui.player

import android.graphics.Color
import android.graphics.Typeface
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.tuneplay.data.local.LrcLine
import com.example.tuneplay.databinding.ItemLrcLineBinding

/**
 * 歌词列表适配器 — 支持当前行高亮（白色加粗 22sp）和非当前行（半透明 14sp）。
 * [setCurrentLine] 使用二分查找定位，[highlightLine] 用于用户手动拖拽歌词。
 */
class LrcAdapter(
    private val onLineClick: ((LrcLine) -> Unit)? = null
) : RecyclerView.Adapter<LrcAdapter.ViewHolder>() {

    private var lines: List<LrcLine> = emptyList()
    private var currentIndex: Int = -1

    fun setLyrics(lyrics: List<LrcLine>) {
        lines = lyrics
        currentIndex = -1
        notifyDataSetChanged()
    }

    fun setCurrentLine(timeMs: Long) {
        val newIndex = findLineIndex(timeMs)
        if (newIndex == currentIndex) return

        val oldIndex = currentIndex
        currentIndex = newIndex

        if (oldIndex in lines.indices) notifyItemChanged(oldIndex)
        if (currentIndex in lines.indices) notifyItemChanged(currentIndex)
    }

    fun getCurrentLinePosition(): Int = if (currentIndex >= 0) currentIndex else 0

    fun getLine(position: Int): LrcLine? = lines.getOrNull(position)

    /** Visually highlight a line by adapter position — used while user drags lyrics. */
    fun highlightLine(position: Int) {
        if (position == currentIndex || position !in lines.indices) return
        val oldIndex = currentIndex
        currentIndex = position
        if (oldIndex in lines.indices) notifyItemChanged(oldIndex)
        notifyItemChanged(currentIndex)
    }

    private fun findLineIndex(timeMs: Long): Int {
        if (lines.isEmpty()) return -1

        var lo = 0
        var hi = lines.size - 1
        var best = -1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (lines[mid].timeMs <= timeMs) {
                best = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return best
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemLrcLineBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val line = lines[position]
        val isCurrent = position == currentIndex

        holder.binding.tvLrcText.text = line.text
        holder.binding.tvLrcText.textSize = if (isCurrent) 22f else 14f
        holder.binding.tvLrcText.setTextColor(
            if (isCurrent) Color.WHITE else Color.argb(120, 255, 255, 255)
        )
        holder.binding.tvLrcText.setTypeface(
            holder.binding.tvLrcText.typeface,
            if (isCurrent) Typeface.BOLD else Typeface.NORMAL
        )

        holder.binding.root.setOnClickListener {
            onLineClick?.invoke(line)
        }
    }

    override fun getItemCount(): Int = lines.size

    class ViewHolder(val binding: ItemLrcLineBinding) : RecyclerView.ViewHolder(binding.root)
}
