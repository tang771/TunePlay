package com.example.tuneplay.ui.player

import android.app.Dialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tuneplay.R
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.data.local.entity.Playlist
import com.example.tuneplay.data.repository.MusicRepository
import com.example.tuneplay.databinding.BottomSheetPlaylistSelectBinding
import com.example.tuneplay.databinding.ItemPlaylistSelectBinding
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

/**
 * 歌单选择底部弹窗 — 将歌曲添加/移出歌单，支持新建歌单。
 * 每个歌单项显示是否已包含当前歌曲（勾选图标），点击切换状态。
 * 操作完成后通过 FragmentResult 通知 PlayerFragment 刷新爱心状态。
 */
class PlaylistSelectSheetFragment : BottomSheetDialogFragment() {

    private var _binding: BottomSheetPlaylistSelectBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: MusicRepository
    private var songId: Long = 0L

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = BottomSheetPlaylistSelectBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        songId = arguments?.getLong(ARG_SONG_ID) ?: 0L
        val db = AppDatabase.getInstance(requireContext())
        repository = MusicRepository(db.songDao(), db.historyDao(), db.playlistDao(), db.searchHistoryDao())

        binding.rvPlaylists.layoutManager = LinearLayoutManager(requireContext())

        binding.btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        observePlaylists()
    }

    private fun observePlaylists() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getAllPlaylists().collect { playlists ->
                launch {
                    val selectedPlaylistIds = mutableSetOf<Long>()

                    // Check which playlists contain this song
                    repository.getPlaylistsContainingSong(songId).collect { containing ->
                        selectedPlaylistIds.clear()
                        selectedPlaylistIds.addAll(containing.map { it.id })

                        val items = playlists.map { playlist ->
                            val count = repository.getSongCountInPlaylist(playlist.id)
                            PlaylistSelectItem(
                                playlist = playlist,
                                songCount = count,
                                isSelected = playlist.id in selectedPlaylistIds
                            )
                        }

                        binding.rvPlaylists.adapter = PlaylistSelectAdapter(items) { item ->
                            lifecycleScope.launch {
                                val nowSelected = repository.toggleSongInPlaylist(
                                    item.playlist.id, songId
                                )
                                // Notify PlayerFragment to refresh like button
                                parentFragmentManager.setFragmentResult("playlist_changed", Bundle())
                                Toast.makeText(
                                    requireContext(),
                                    if (nowSelected) getString(R.string.playlist_added)
                                    else getString(R.string.playlist_removed),
                                    Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    }
                }
            }
        }
    }

    private fun showCreatePlaylistDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.playlist_create_hint)
            setHintTextColor(0xFF999999.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 32, 48, 16)
        }

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_create_new)
            .setView(input)
            .setPositiveButton(R.string.playlist_create_confirm) { d, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    lifecycleScope.launch {
                        val existing = repository.getPlaylistByName(name)
                        if (existing != null) {
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.playlist_already_exists),
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            val playlistId = repository.createPlaylist(name)
                            repository.addSongToPlaylist(playlistId, songId)
                            // Notify PlayerFragment to refresh like button
                            parentFragmentManager.setFragmentResult("playlist_changed", Bundle())
                            Toast.makeText(
                                requireContext(),
                                getString(R.string.playlist_added),
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                }
                d.dismiss()
            }
            .setNegativeButton(R.string.playlist_create_cancel) { d, _ ->
                d.dismiss()
            }
            .show()
        // Fix button text color for dark theme
        dialog.getButton(android.app.Dialog.BUTTON_POSITIVE)?.setTextColor(0xFFFFFFFF.toInt())
        dialog.getButton(android.app.Dialog.BUTTON_NEGATIVE)?.setTextColor(0xFFFFFFFF.toInt())
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        private const val ARG_SONG_ID = "song_id"

        fun newInstance(songId: Long): PlaylistSelectSheetFragment {
            return PlaylistSelectSheetFragment().apply {
                arguments = Bundle().apply { putLong(ARG_SONG_ID, songId) }
            }
        }
    }
}

data class PlaylistSelectItem(
    val playlist: Playlist,
    val songCount: Int,
    val isSelected: Boolean,
    val coverUrl: String? = null
)

/** 歌单选择列表适配器 — 显示歌单名、歌曲数和勾选状态 */
class PlaylistSelectAdapter(
    private var items: List<PlaylistSelectItem>,
    private val onItemClick: (PlaylistSelectItem) -> Unit
) : RecyclerView.Adapter<PlaylistSelectAdapter.ViewHolder>() {

    class ViewHolder(val binding: ItemPlaylistSelectBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemPlaylistSelectBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.binding.tvPlaylistName.text = item.playlist.name
        holder.binding.tvSongCount.text = "${item.songCount} 首"
        holder.binding.ivCheck.setImageResource(
            if (item.isSelected) android.R.drawable.checkbox_on_background
            else android.R.drawable.checkbox_off_background
        )
        holder.binding.root.setOnClickListener {
            onItemClick(item)
        }
    }

    override fun getItemCount(): Int = items.size
}
