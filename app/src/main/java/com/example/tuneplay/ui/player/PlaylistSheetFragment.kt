package com.example.tuneplay.ui.player

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tuneplay.R
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.player.MusicController
import com.google.android.material.bottomsheet.BottomSheetDialogFragment

/**
 * 播放列表底部弹窗 — 显示当前播放队列，点击歌曲跳转播放。
 * 当前播放的歌曲高亮显示（白字 + 半透明背景）。
 */
class PlaylistSheetFragment(
    private val playlist: List<Song>
) : BottomSheetDialogFragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        return inflater.inflate(R.layout.bottom_sheet_playlist, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val rv = view.findViewById<RecyclerView>(R.id.rv_playlist_sheet)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = PlaylistAdapter(playlist) { song ->
            val index = playlist.indexOf(song)
            if (index >= 0) {
                MusicController.play(requireContext(), playlist, index)
            }
            dismiss()
        }
    }

    private class PlaylistAdapter(
        private val songs: List<Song>,
        private val onClick: (Song) -> Unit
    ) : RecyclerView.Adapter<PlaylistAdapter.VH>() {

        class VH(view: View) : RecyclerView.ViewHolder(view) {
            val title: TextView = view.findViewById(android.R.id.text1)
            val artist: TextView = view.findViewById(android.R.id.text2)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val view = LayoutInflater.from(parent.context)
                .inflate(android.R.layout.simple_list_item_2, parent, false)
            return VH(view)
        }

        override fun onBindViewHolder(holder: VH, position: Int) {
            val song = songs[position]
            val isCurrent = MusicController.getCurrentSong()?.id == song.id
            holder.title.text = song.title
            holder.artist.text = song.artist
            holder.title.setTextColor(
                if (isCurrent) Color.WHITE else Color.argb(179, 255, 255, 255)
            )
            holder.artist.setTextColor(
                if (isCurrent) Color.argb(200, 255, 255, 255) else Color.argb(120, 255, 255, 255)
            )
            holder.itemView.setBackgroundColor(
                if (isCurrent) 0x33FFFFFF.toInt() else android.graphics.Color.TRANSPARENT
            )
            holder.itemView.setOnClickListener { onClick(song) }
        }

        override fun getItemCount(): Int = songs.size
    }
}
