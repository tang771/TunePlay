package com.example.tuneplay.ui.home

import android.util.Log
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import android.graphics.drawable.Drawable
import com.example.tuneplay.R
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.data.network.OnlineSong
import com.example.tuneplay.databinding.ItemSectionHeaderBinding
import com.example.tuneplay.databinding.ItemSongBinding

/**
 * 统一搜索列表适配器 — 支持本地歌曲、在线歌曲和分组标题三种视图类型。
 * 使用 sealed class [SongItem] 区分不同类型，DiffUtil 优化列表更新。
 */
class UnifiedSongAdapter(
    private val onLocalClick: (Song) -> Unit,
    private val onOnlineClick: (OnlineSong) -> Unit
) : ListAdapter<SongItem, RecyclerView.ViewHolder>(DiffCallback) {

    override fun getItemViewType(position: Int): Int {
        return when (getItem(position)) {
            is SongItem.Local -> VIEW_TYPE_LOCAL
            is SongItem.Online -> VIEW_TYPE_ONLINE
            is SongItem.Section -> VIEW_TYPE_SECTION
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        return when (viewType) {
            VIEW_TYPE_LOCAL -> {
                val binding = ItemSongBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                LocalViewHolder(binding)
            }
            VIEW_TYPE_ONLINE -> {
                val binding = ItemSongBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                OnlineViewHolder(binding)
            }
            VIEW_TYPE_SECTION -> {
                val binding = ItemSectionHeaderBinding.inflate(
                    LayoutInflater.from(parent.context), parent, false
                )
                SectionViewHolder(binding)
            }
            else -> throw IllegalStateException("Unknown viewType: $viewType")
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (val item = getItem(position)) {
            is SongItem.Local -> (holder as LocalViewHolder).bind(item.song)
            is SongItem.Online -> (holder as OnlineViewHolder).bind(item.song, item.coverUrl)
            is SongItem.Section -> (holder as SectionViewHolder).bind(item.title)
        }
    }

    inner class LocalViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(song: Song) {
            binding.tvTitle.text = song.title
            binding.tvArtistAlbum.text = "${song.artist} — ${song.album}"
            binding.tvDuration.text = formatDuration(song.duration)
            Glide.with(binding.ivAlbumArt.context)
                .load(song.coverArtPath)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .centerCrop()
                .into(binding.ivAlbumArt)
            binding.root.setOnClickListener { onLocalClick(song) }
        }
    }

    inner class OnlineViewHolder(private val binding: ItemSongBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(song: OnlineSong, resolvedCoverUrl: String? = null) {
            binding.tvTitle.text = song.name
            binding.tvArtistAlbum.text = song.artistNames()
            binding.tvDuration.text = formatDuration(song.duration)
            val url = song.coverUrl()
            Glide.with(binding.ivAlbumArt.context)
                .load(url)
                .placeholder(R.drawable.ic_music_note)
                .error(R.drawable.ic_music_note)
                .centerCrop()
                .listener(object : RequestListener<Drawable> {
                    override fun onLoadFailed(
                        e: GlideException?,
                        model: Any?,
                        target: Target<Drawable>,
                        isFirstResource: Boolean
                    ): Boolean {
                        Log.e("SearchCover", "Glide FAIL: $url → ${e?.message}", e)
                        return false
                    }
                    override fun onResourceReady(
                        resource: Drawable,
                        model: Any,
                        target: Target<Drawable>,
                        dataSource: DataSource,
                        isFirstResource: Boolean
                    ): Boolean = false
                })
                .into(binding.ivAlbumArt)
            binding.root.setOnClickListener { onOnlineClick(song) }
        }
    }

    inner class SectionViewHolder(private val binding: ItemSectionHeaderBinding) :
        RecyclerView.ViewHolder(binding.root) {
        fun bind(title: String) {
            binding.tvSectionHeader.text = title
        }
    }

    companion object {
        private const val VIEW_TYPE_LOCAL = 0
        private const val VIEW_TYPE_ONLINE = 1
        private const val VIEW_TYPE_SECTION = 2

        val DiffCallback = object : DiffUtil.ItemCallback<SongItem>() {
            override fun areItemsTheSame(oldItem: SongItem, newItem: SongItem): Boolean {
                return when {
                    oldItem is SongItem.Local && newItem is SongItem.Local ->
                        oldItem.song.id == newItem.song.id
                    oldItem is SongItem.Online && newItem is SongItem.Online ->
                        oldItem.song.id == newItem.song.id
                    oldItem is SongItem.Section && newItem is SongItem.Section ->
                        oldItem.title == newItem.title
                    else -> false
                }
            }

            override fun areContentsTheSame(oldItem: SongItem, newItem: SongItem): Boolean {
                return oldItem == newItem
            }
        }
    }
}
