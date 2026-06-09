package com.example.tuneplay.ui.profile

import android.content.Context
import android.net.Uri
import android.util.Log
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.tuneplay.R
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.data.local.entity.Playlist
import com.example.tuneplay.data.repository.MusicRepository
import com.example.tuneplay.databinding.FragmentProfileBinding
import com.bumptech.glide.Glide
import com.example.tuneplay.player.MusicController
import com.example.tuneplay.ui.home.SongAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 个人主页 Fragment — 头像/昵称管理、实时播放统计、歌单列表、最近播放和本地音乐入口。
 * 歌单支持 ItemTouchHelper 拖拽排序和 PlaylistCardAdapter 侧滑删除。
 * 播放时长和次数每次打开页面从 Room 实时查询，不再缓存。
 */
class ProfileFragment : Fragment() {

    private var _binding: FragmentProfileBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: MusicRepository

    private val avatarPickerLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { saveAvatarToInternalStorage(it) } }

    companion object {
        private const val PREFS_NAME = "tuneplay_prefs"
        private const val PREF_NICKNAME = "profile_nickname"
        private const val PREF_AVATAR_PATH = "profile_avatar_path"
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentProfileBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.getInstance(requireContext())
        repository = MusicRepository(db.songDao(), db.historyDao(), db.playlistDao(), db.searchHistoryDao())

        binding.rvPlaylists.layoutManager = LinearLayoutManager(requireContext())

        // Load avatar and nickname from SharedPreferences
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        loadAvatar(prefs.getString(PREF_AVATAR_PATH, null))
        val nickname = prefs.getString(PREF_NICKNAME, null)
        if (!nickname.isNullOrEmpty()) {
            binding.tvUsername.text = nickname
        }

        // Avatar click → photo picker
        binding.ivAvatar.setOnClickListener { avatarPickerLauncher.launch("image/*") }
        // Nickname click → edit dialog
        binding.tvUsername.setOnClickListener { showNicknameDialog() }

        // "我喜欢" → navigate to full song list
        binding.cardLiked.root.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val likedPlaylist = withContext(Dispatchers.IO) {
                    repository.getPlaylistByName(getString(R.string.playlist_liked))
                } ?: return@launch
                val bundle = Bundle().apply {
                    putLong(PlaylistSongsFragment.ARG_PLAYLIST_ID, likedPlaylist.id)
                    putString(PlaylistSongsFragment.ARG_PLAYLIST_NAME, getString(R.string.playlist_liked))
                }
                findNavController().navigate(
                    R.id.action_profileFragment_to_playlistSongsFragment, bundle
                )
            }
        }

        // "本地音乐" → navigate to local songs list
        binding.cardLocalMusic.root.setOnClickListener {
            val bundle = Bundle().apply {
                putLong(PlaylistSongsFragment.ARG_PLAYLIST_ID, -2L)
                putString(PlaylistSongsFragment.ARG_PLAYLIST_NAME, getString(R.string.local_music))
            }
            findNavController().navigate(
                R.id.action_profileFragment_to_playlistSongsFragment, bundle
            )
        }

        // "创建歌单" button
        binding.btnCreatePlaylist.setOnClickListener {
            showCreatePlaylistDialog()
        }

        // "最近播放" card → navigate to recent plays list (up to 100)
        val recentCard = binding.cardRecentPlays.root
        recentCard.setOnClickListener {
            val bundle = Bundle().apply {
                putLong(PlaylistSongsFragment.ARG_PLAYLIST_ID, -1L)
                putString(PlaylistSongsFragment.ARG_PLAYLIST_NAME, getString(R.string.section_recently_played))
            }
            findNavController().navigate(
                R.id.action_profileFragment_to_playlistSongsFragment, bundle
            )
        }

        loadStats()
        loadPlaylists()
        loadRecentPlaysCard()
        loadLocalMusicCard()
    }

    /** 弹出创建歌单对话框 — 检查重名后通过 Repository 写入 Room */
    private fun showCreatePlaylistDialog() {
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.playlist_create_hint)
            setHintTextColor(0xFF999999.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 32, 48, 16)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.playlist_create_new)
            .setView(input)
            .setPositiveButton(R.string.playlist_create_confirm) { d, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) {
                    viewLifecycleOwner.lifecycleScope.launch {
                        val existing = withContext(Dispatchers.IO) {
                            repository.getPlaylistByName(name)
                        }
                        if (existing != null) {
                            android.widget.Toast.makeText(
                                requireContext(),
                                getString(R.string.playlist_already_exists),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            withContext(Dispatchers.IO) { repository.createPlaylist(name) }
                            android.widget.Toast.makeText(
                                requireContext(), "歌单已创建", android.widget.Toast.LENGTH_SHORT
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
        dialog.getButton(android.app.Dialog.BUTTON_POSITIVE)?.setTextColor(0xFFFFFFFF.toInt())
        dialog.getButton(android.app.Dialog.BUTTON_NEGATIVE)?.setTextColor(0xFFFFFFFF.toInt())
    }

    /** 加载播放统计（时长/次数）和"我喜欢"卡片数据 — 每次打开页面从 Room 实时查询 */
    private fun loadStats() {
        viewLifecycleOwner.lifecycleScope.launch {
            val totalDuration = withContext(Dispatchers.IO) { repository.getTotalPlayDuration() }
            val playCount = withContext(Dispatchers.IO) { repository.getTotalPlayCount() }
            android.util.Log.d("ProfileFragment", "loadStats: totalDuration=$totalDuration playCount=$playCount")

            binding.tvStatSongs.text = formatDuration(totalDuration)
            binding.tvStatPlays.text = playCount.toString()

            // Load liked playlist card: name, count, cover from latest added song
            val likedPlaylist = withContext(Dispatchers.IO) {
                repository.getPlaylistByName(getString(R.string.playlist_liked))
            }
            if (likedPlaylist != null) {
                val likedCount = withContext(Dispatchers.IO) {
                    repository.getSongCountInPlaylist(likedPlaylist.id)
                }
                binding.cardLiked.tvPlaylistName.text = getString(R.string.playlist_liked)
                binding.cardLiked.tvSongCount.text = "$likedCount 首"
                val likedCover = withContext(Dispatchers.IO) {
                    val songs = repository.getSongsInPlaylist(likedPlaylist.id).first()
                    songs.firstOrNull { it.coverArtPath.isNotBlank() }?.coverArtPath
                }
                Glide.with(this@ProfileFragment)
                    .load(likedCover)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(binding.cardLiked.ivPlaylistCover)
            }

            binding.tvStatPlays.text = playCount.toString()
        }
    }

    /** 将用户选择的头像图片复制到应用内部存储并持久化路径到 SharedPreferences */
    private fun saveAvatarToInternalStorage(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            try {
                val avatarDir = File(requireContext().filesDir, "avatar")
                avatarDir.mkdirs()
                val avatarFile = File(avatarDir, "avatar.jpg")
                requireContext().contentResolver.openInputStream(uri)?.use { input ->
                    avatarFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                val path = avatarFile.absolutePath
                requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_AVATAR_PATH, path)
                    .apply()
                withContext(Dispatchers.Main) {
                    loadAvatar(path)
                }
            } catch (_: Exception) {
                // Silently fail — avatar remains as placeholder
            }
        }
    }

    /** 通过 Glide 加载头像 — 文件不存在时显示占位图 */
    private fun loadAvatar(path: String?) {
        if (!path.isNullOrEmpty() && File(path).exists()) {
            Glide.with(this)
                .load(File(path))
                .placeholder(R.drawable.ic_person_24)
                .circleCrop()
                .into(binding.ivAvatar)
        }
    }

    /** 弹出昵称编辑对话框 — 修改后即时更新 UI 和 SharedPreferences */
    private fun showNicknameDialog() {
        val prefs = requireContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val currentNickname = prefs.getString(PREF_NICKNAME, null) ?: ""
        val input = android.widget.EditText(requireContext()).apply {
            hint = getString(R.string.profile_nickname_hint)
            setText(currentNickname)
            setHintTextColor(0xFF999999.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(48, 32, 48, 16)
        }
        val dialog = com.google.android.material.dialog.MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.profile_nickname_dialog_title)
            .setView(input)
            .setPositiveButton(getString(R.string.playlist_create_confirm)) { d, _ ->
                val newName = input.text.toString().trim()
                if (newName.isNotEmpty()) {
                    prefs.edit().putString(PREF_NICKNAME, newName).apply()
                    binding.tvUsername.text = newName
                }
                d.dismiss()
            }
            .setNegativeButton(getString(R.string.playlist_create_cancel)) { d, _ ->
                d.dismiss()
            }
            .show()
        dialog.getButton(android.app.Dialog.BUTTON_POSITIVE)?.setTextColor(0xFFFFFFFF.toInt())
        dialog.getButton(android.app.Dialog.BUTTON_NEGATIVE)?.setTextColor(0xFFFFFFFF.toInt())
    }

    /** 格式化毫秒时长为可读字符串：< 1 分钟 / X 分钟 / X 小时 */
    private fun formatDuration(totalMillis: Long): String {
        if (totalMillis <= 0) return "0 分钟"
        val totalSeconds = totalMillis / 1000
        if (totalSeconds < 60) return "< 1 分钟"
        val totalMinutes = totalSeconds / 60
        if (totalMinutes < 60) return "$totalMinutes 分钟"
        val hours = totalMinutes / 60.0
        return if (hours == hours.toLong().toDouble()) {
            "${hours.toLong()} 小时"
        } else {
            "%.1f 小时".format(hours)
        }
    }

    /** 加载用户歌单列表 — 排除"我喜欢"，为每首歌单加载封面和歌曲数，支持拖拽排序和侧滑删除 */
    private fun loadPlaylists() {
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getAllPlaylists().collect { playlists ->
                val userPlaylists = playlists.filter { it.name != getString(R.string.playlist_liked) }
                binding.tvStatPlaylists.text = playlists.size.toString()

                binding.tvSectionPlaylists.visibility = View.VISIBLE

                // Build card items with covers (first song's cover by sort order)
                val items = userPlaylists.map { playlist ->
                    val count = withContext(Dispatchers.IO) {
                        repository.getSongCountInPlaylist(playlist.id)
                    }
                    val coverUrl = withContext(Dispatchers.IO) {
                        val songs = repository.getSongsInPlaylist(playlist.id).first()
                        songs.firstOrNull { it.coverArtPath.isNotBlank() }?.coverArtPath
                    }
                    PlaylistCardItem(
                        id = playlist.id,
                        name = playlist.name,
                        songCount = count,
                        coverUrl = coverUrl
                    )
                }

                val adapter = binding.rvPlaylists.adapter as? PlaylistCardAdapter
                if (adapter == null) {
                    val newAdapter = PlaylistCardAdapter(
                        items = items,
                        onClick = { item ->
                            val bundle = Bundle().apply {
                                putLong(PlaylistSongsFragment.ARG_PLAYLIST_ID, item.id)
                                putString(PlaylistSongsFragment.ARG_PLAYLIST_NAME, item.name)
                            }
                            findNavController().navigate(
                                R.id.action_profileFragment_to_playlistSongsFragment, bundle
                            )
                        },
                        onDeleteClick = { item -> deletePlaylist(item) },
                        onItemMoved = { from, to -> persistSortOrder() }
                    )
                    binding.rvPlaylists.adapter = newAdapter
                    attachDragHelper(binding.rvPlaylists, newAdapter)
                } else {
                    adapter.updateItems(items)
                }
            }
        }
    }

    /** 删除歌单 — 通过名称查找到 Playlist 实体后删除（CASCADE 会自动清理关联歌曲） */
    private fun deletePlaylist(item: PlaylistCardItem) {
        viewLifecycleOwner.lifecycleScope.launch {
            val playlist = withContext(Dispatchers.IO) {
                repository.getPlaylistByName(item.name)
            }
            if (playlist != null) {
                withContext(Dispatchers.IO) { repository.deletePlaylist(playlist) }
                Toast.makeText(requireContext(), "已删除「${item.name}」", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 绑定 ItemTouchHelper 实现长按拖拽排序 */
    private fun attachDragHelper(rv: RecyclerView, adapter: PlaylistCardAdapter) {
        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val from = viewHolder.bindingAdapterPosition
                val to = target.bindingAdapterPosition
                if (from == RecyclerView.NO_POSITION || to == RecyclerView.NO_POSITION) return false
                adapter.moveItem(from, to)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}
            override fun isLongPressDragEnabled(): Boolean = true
        }
        ItemTouchHelper(callback).attachToRecyclerView(rv)
    }

    /** 将当前歌单排序写入 Room（拖拽后调用） */
    private fun persistSortOrder() {
        viewLifecycleOwner.lifecycleScope.launch {
            val adapter = binding.rvPlaylists.adapter as? PlaylistCardAdapter ?: return@launch
            val items = adapter.getItems()
            withContext(Dispatchers.IO) {
                for ((i, item) in items.withIndex()) {
                    repository.updatePlaylistSortOrder(item.id, i)
                }
            }
        }
    }

    /** 加载最近播放入口卡片 — 无播放记录时隐藏 */
    private fun loadRecentPlaysCard() {
        val recentCard = binding.cardRecentPlays
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getRecentlyPlayedSongs(100).collect { songs ->
                val hasRecent = songs.isNotEmpty()
                binding.tvSectionRecent.visibility = if (hasRecent) View.VISIBLE else View.GONE
                recentCard.root.visibility = if (hasRecent) View.VISIBLE else View.GONE
                if (hasRecent) {
                    recentCard.tvPlaylistName.text = getString(R.string.section_recently_played)
                    recentCard.tvSongCount.text = "${songs.size} 首"
                    Glide.with(this@ProfileFragment)
                        .load(songs.firstOrNull { it.coverArtPath.isNotBlank() }?.coverArtPath)
                        .placeholder(R.drawable.ic_music_note)
                        .centerCrop()
                        .into(recentCard.ivPlaylistCover)
                }
            }
        }
    }

    /** 加载本地音乐入口卡片 — 显示本地歌曲总数和封面，点击进入本地音乐列表页 */
    private fun loadLocalMusicCard() {
        val card = binding.cardLocalMusic
        viewLifecycleOwner.lifecycleScope.launch {
            repository.getLocalSongs().collect { songs ->
                val count = songs.size
                card.tvPlaylistName.text = getString(R.string.local_music)
                card.tvSongCount.text = "$count 首"
                Glide.with(this@ProfileFragment)
                    .load(songs.firstOrNull { it.coverArtPath.isNotBlank() }?.coverArtPath)
                    .placeholder(R.drawable.ic_music_note)
                    .centerCrop()
                    .into(card.ivPlaylistCover)
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
