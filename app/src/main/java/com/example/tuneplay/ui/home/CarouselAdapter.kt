package com.example.tuneplay.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tuneplay.R
import com.example.tuneplay.data.local.entity.Song

/**
 * 走马灯轮播适配器 — 通过 FAKE_MULTIPLIER 实现无限循环滚动。
 * 实际数据和虚拟位置通过取模映射，起始位置设在虚拟列表中间。
 */
class CarouselAdapter(
    private val onItemClick: (Song) -> Unit
) : RecyclerView.Adapter<CarouselAdapter.ViewHolder>() {

    companion object {
        private const val FAKE_MULTIPLIER = 1000
    }

    private val items = mutableListOf<Song>()
    private val realSize get() = items.size
    private val fakeCount get() = realSize * FAKE_MULTIPLIER

    fun submitList(songs: List<Song>) {
        items.clear()
        items.addAll(songs)
        notifyDataSetChanged()
    }

    fun getSongAt(position: Int): Song? =
        if (realSize > 0) items.getOrNull(position % realSize) else null

    fun getStartPosition(): Int =
        if (realSize > 0) (fakeCount / 2) - (fakeCount / 2) % realSize else 0

    override fun getItemCount(): Int = if (realSize > 0) fakeCount else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_carousel_page, parent, false) as ImageView
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        if (realSize > 0) {
            holder.bind(items[position % realSize])
        }
    }

    inner class ViewHolder(private val imageView: ImageView) : RecyclerView.ViewHolder(imageView) {
        init {
            imageView.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    getSongAt(pos)?.let { onItemClick(it) }
                }
            }
        }

        fun bind(song: Song) {
            Glide.with(imageView.context)
                .load(song.coverArtPath)
                .placeholder(R.drawable.ic_music_note)
                .centerCrop()
                .into(imageView)
        }
    }
}
