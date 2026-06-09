package com.example.tuneplay.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tuneplay.R
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.databinding.ItemSongCompactBinding

/**
 * 推荐歌曲紧凑布局适配器 — 使用 item_song_compact 布局（小封面 + 标题/艺术家）。
 */
class RecommendAdapter(
    private val onSongClick: (Song) -> Unit
) : ListAdapter<Song, RecommendAdapter.ViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemSongCompactBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val song = getItem(position)
        holder.bind(song)
    }

    inner class ViewHolder(
        private val binding: ItemSongCompactBinding
    ) : RecyclerView.ViewHolder(binding.root) {

        fun bind(song: Song) {
            binding.tvCompactTitle.text = song.title
            binding.tvCompactArtist.text = song.artist
            Glide.with(binding.root)
                .load(song.coverArtPath)
                .placeholder(R.drawable.ic_music_note)
                .centerCrop()
                .into(binding.ivCompactArt)

            binding.root.setOnClickListener {
                onSongClick(song)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<Song>() {
        override fun areItemsTheSame(oldItem: Song, newItem: Song): Boolean =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: Song, newItem: Song): Boolean =
            oldItem == newItem
    }
}
