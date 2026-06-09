package com.example.tuneplay.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tuneplay.R
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.databinding.ItemRecommendedSongBinding

/**
 * 推荐歌曲列表适配器 — item_recommended_song 布局。
 * 内置私有 formatDuration 方法（与 SongAdapter 中的实现重复，可考虑提取到工具类）。
 */
class RecommendedSongAdapter(
    private val onItemClick: (Song) -> Unit
) : RecyclerView.Adapter<RecommendedSongAdapter.ViewHolder>() {

    private val items = mutableListOf<Song>()

    fun submitList(songs: List<Song>) {
        items.clear()
        items.addAll(songs)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecommendedSongBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(private val binding: ItemRecommendedSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items.getOrNull(pos)?.let { onItemClick(it) }
                }
            }
        }

        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtistAlbum.text = "${song.artist} - ${song.album}"
            binding.tvDuration.text = formatDuration(song.duration)
            Glide.with(binding.ivAlbumArt.context)
                .load(song.coverArtPath)
                .placeholder(R.drawable.ic_music_note)
                .centerCrop()
                .into(binding.ivAlbumArt)
        }
    }
}
