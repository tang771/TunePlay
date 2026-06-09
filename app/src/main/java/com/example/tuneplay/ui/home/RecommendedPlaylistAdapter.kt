package com.example.tuneplay.ui.home

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.tuneplay.R
import com.example.tuneplay.data.recommend.GeneratedPlaylist
import com.example.tuneplay.databinding.ItemRecommendedPlaylistBinding

/**
 * 推荐歌单列表适配器 — 展示 GeneratedPlaylist，用第一首歌的封面作为歌单封面。
 */
class RecommendedPlaylistAdapter(
    private val onItemClick: (GeneratedPlaylist) -> Unit
) : RecyclerView.Adapter<RecommendedPlaylistAdapter.ViewHolder>() {

    private val items = mutableListOf<GeneratedPlaylist>()

    fun submitList(playlists: List<GeneratedPlaylist>) {
        items.clear()
        items.addAll(playlists)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemRecommendedPlaylistBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(items[position])
    }

    inner class ViewHolder(private val binding: ItemRecommendedPlaylistBinding) :
        RecyclerView.ViewHolder(binding.root) {
        init {
            binding.root.setOnClickListener {
                val pos = bindingAdapterPosition
                if (pos != RecyclerView.NO_POSITION) {
                    items.getOrNull(pos)?.let { onItemClick(it) }
                }
            }
        }

        fun bind(playlist: GeneratedPlaylist) {
            binding.tvPlaylistName.text = playlist.name
            val firstSong = playlist.songs.firstOrNull()
            if (firstSong != null) {
                Glide.with(binding.ivPlaylistCover.context)
                    .load(firstSong.coverArtPath)
                    .placeholder(R.drawable.ic_music_note)
                    .error(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(binding.ivPlaylistCover)
            }
        }
    }
}
