package com.example.tuneplay.ui.profile

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.tuneplay.R
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.data.network.NeteaseApi
import com.example.tuneplay.data.network.tryGetSongUrl
import com.example.tuneplay.data.repository.MusicRepository
import com.example.tuneplay.data.scanner.MediaStoreScanner
import com.example.tuneplay.databinding.FragmentPlaylistSongsBinding
import com.example.tuneplay.player.MusicController
import com.example.tuneplay.ui.home.ImportSheetFragment
import com.example.tuneplay.ui.home.SongAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 歌单歌曲列表页面 — 支持多种数据源模式：
 * - 普通歌单 (playlistId > 0)：显示指定歌单中的歌曲
 * - 本地音乐 (playlistId == -2)：显示全部本地歌曲，提供导入入口（+ 按钮）
 * - 最近播放 (playlistId == -1)：显示最近 100 条播放记录
 * - 推荐歌单 (songIds 不为空)：显示指定 ID 的歌曲列表，支持保存为歌单
 *
 * 本地音乐模式支持扫描 MediaStore 和选择文件导入，扫描前会检查权限。
 */
class PlaylistSongsFragment : Fragment() {

    private var _binding: FragmentPlaylistSongsBinding? = null
    private val binding get() = _binding!!
    private lateinit var repository: MusicRepository
    private var playlistId: Long = 0L
    private var sortByAlpha = true
    private var allSongs: List<Song> = emptyList()
    private var songIds: LongArray? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            scanLibrary()
        }
    }

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            importFiles(uris)
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPlaylistSongsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        playlistId = arguments?.getLong(ARG_PLAYLIST_ID) ?: 0L
        songIds = arguments?.getLongArray(ARG_SONG_IDS)
        val name = arguments?.getString(ARG_PLAYLIST_NAME) ?: ""

        val db = AppDatabase.getInstance(requireContext())
        repository = MusicRepository(db.songDao(), db.historyDao(), db.playlistDao(), db.searchHistoryDao())

        binding.tvTitle.text = name
        binding.rvSongs.layoutManager = LinearLayoutManager(requireContext())

        binding.btnBack.setOnClickListener { findNavController().navigateUp() }
        binding.btnSortAlpha.setOnClickListener { sort(true) }
        binding.btnSortDate.setOnClickListener { sort(false) }

        if (playlistId == -2L) {
            binding.btnUpload.visibility = View.VISIBLE
            binding.btnUpload.setOnClickListener { showImportSheet() }
        }

        if (songIds != null && songIds!!.isNotEmpty()) {
            binding.btnSavePlaylist.visibility = View.VISIBLE
            binding.btnSavePlaylist.setOnClickListener { savePlaylist(name) }
        }

        loadSongs()
    }

    /** 根据数据源模式加载歌曲：推荐歌单 > 本地音乐 > 最近播放 > 普通歌单 */
    private fun loadSongs() {
        viewLifecycleOwner.lifecycleScope.launch {
            if (songIds != null && songIds!!.isNotEmpty()) {
                val songs = withContext(Dispatchers.IO) {
                    songIds!!.map { id -> repository.getSongById(id) }.filterNotNull()
                }
                allSongs = songs
                binding.tvSongCount.text = "${songs.size} 首"
                applySort()
            } else if (playlistId == -2L) {
                repository.getLocalSongs().collect { songs ->
                    allSongs = songs
                    binding.tvSongCount.text = "${songs.size} 首"
                    updateEmptyState(songs.isEmpty())
                    applySort()
                }
            } else if (playlistId == -1L) {
                repository.getRecentlyPlayedSongs(100).collect { songs ->
                    allSongs = songs
                    binding.tvSongCount.text = "${songs.size} 首"
                    applySort()
                }
            } else {
                repository.getSongsInPlaylist(playlistId).collect { songs ->
                    allSongs = songs
                    binding.tvSongCount.text = "${songs.size} 首"
                    applySort()
                }
            }
        }
    }

    /** 切换空状态视图（仅本地音乐模式使用），有数据时显示列表，无数据时显示空提示 */
    private fun updateEmptyState(isEmpty: Boolean) {
        binding.tvEmpty.visibility = if (isEmpty) View.VISIBLE else View.GONE
        binding.rvSongs.visibility = if (isEmpty) View.GONE else View.VISIBLE
    }

    // ── Import ──────────────────────────────────────────────────────────

    /** 弹出导入选项底部弹窗：扫描媒体库 / 选择文件 */
    private fun showImportSheet() {
        val sheet = ImportSheetFragment()
        sheet.onScanLibrary = {
            if (hasMediaPermission()) scanLibrary()
            else requestMediaPermission()
        }
        sheet.onPickFiles = {
            filePickerLauncher.launch(arrayOf("audio/*"))
        }
        sheet.show(parentFragmentManager, ImportSheetFragment.TAG)
    }

    /** 检查媒体读取权限 — Android 13+ 使用 READ_MEDIA_AUDIO，以下使用 READ_EXTERNAL_STORAGE */
    private fun hasMediaPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_MEDIA_AUDIO
            ) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** 请求媒体权限 — 根据系统版本选择对应权限 */
    private fun requestMediaPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    /** 扫描 MediaStore 并导入 — 扫描结果为空时不会清空现有数据，防止数据丢失 */
    private fun scanLibrary() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val scanner = MediaStoreScanner(requireContext().contentResolver)
                val scannedSongs = withContext(Dispatchers.IO) { scanner.scan() }
                if (scannedSongs.isEmpty()) {
                    Toast.makeText(requireContext(), R.string.import_scan_empty, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    repository.deleteAllSongs()
                    repository.insertSongs(scannedSongs)
                }
                Toast.makeText(
                    requireContext(),
                    getString(R.string.import_scan_result, scannedSongs.size),
                    Toast.LENGTH_SHORT
                ).show()
            } catch (_: Exception) {
                Toast.makeText(requireContext(), "扫描失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 导入用户选择的文件 — 从 Content URI 提取元数据，跳过已存在的文件 */
    private fun importFiles(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            var imported = 0
            for (uri in uris) {
                try {
                    requireContext().contentResolver.takePersistableUriPermission(
                        uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                    )
                } catch (_: SecurityException) { }
                val song = withContext(Dispatchers.IO) { buildSongFromUri(uri) }
                if (song != null) {
                    withContext(Dispatchers.IO) {
                        val existing = repository.getSongByPath(song.filePath)
                        if (existing == null) {
                            repository.insertSongs(listOf(song))
                        }
                    }
                    imported++
                }
            }
            val msg = if (imported == 1) getString(R.string.import_file_added)
            else getString(R.string.import_files_added, imported)
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    /** 从 Content URI 提取音频元数据 — 先查 MediaStore，失败后从 OpenableColumns 取文件名 */
    private fun buildSongFromUri(uri: Uri): Song? {
        val resolver = requireContext().contentResolver
        var title = ""; var artist = ""; var album = ""; var duration = 0L; var size = 0L
        val projection = arrayOf(
            MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE, MediaStore.Audio.Media.MIME_TYPE
        )
        resolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                title = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)) ?: ""
                artist = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)) ?: ""
                album = cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)) ?: ""
                duration = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION))
                size = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE))
            }
        }
        if (title.isBlank()) {
            resolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIdx >= 0) {
                        title = (cursor.getString(nameIdx) ?: "").substringBeforeLast('.')
                    }
                    val sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIdx >= 0 && size == 0L) size = cursor.getLong(sizeIdx)
                }
            }
        }
        if (title.isBlank()) {
            title = uri.lastPathSegment?.substringAfterLast('/')?.substringBeforeLast('.') ?: "Unknown"
        }
        return Song(
            title = title, artist = artist.ifBlank { "Unknown Artist" },
            album = album.ifBlank { "Unknown Album" }, duration = duration,
            filePath = uri.toString(), fileSize = size,
            mimeType = resolver.getType(uri) ?: "audio/*"
        )
    }

    // ── Sort & Play ──────────────────────────────────────────────────

    /** 将当前推荐歌单持久化到 Room — 同名歌单先删后建 */
    private fun savePlaylist(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val existing = repository.getPlaylistByName(name)
                    if (existing != null) {
                        repository.deletePlaylist(existing)
                    }
                    val newId = repository.createPlaylist(name)
                    for (song in allSongs) {
                        repository.addSongToPlaylist(newId, song.id)
                    }
                }
                binding.btnSavePlaylist.text = "已加入歌单"
                binding.btnSavePlaylist.isEnabled = false
                binding.btnSavePlaylist.setTextColor(0xFF999999.toInt())
                Toast.makeText(requireContext(), "已添加到个人歌单", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(requireContext(), "保存失败", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** 切换排序方式 — A-Z 字母序 / 添加时间倒序 */
    private fun sort(byAlpha: Boolean) {
        sortByAlpha = byAlpha
        binding.btnSortAlpha.setTextColor(
            if (byAlpha) 0xFFFFFFFF.toInt() else 0xFF999999.toInt()
        )
        binding.btnSortDate.setTextColor(
            if (!byAlpha) 0xFFFFFFFF.toInt() else 0xFF999999.toInt()
        )
        applySort()
    }

    /** 排序并更新 RecyclerView — 在线歌曲点击时先解析 URL 再播放，本地歌曲直接播放 */
    private fun applySort() {
        val sorted = if (sortByAlpha) {
            allSongs.sortedBy { it.title.lowercase() }
        } else {
            allSongs.sortedByDescending { it.dateAdded }
        }
        val adapter = SongAdapter { song ->
            if (song.neteaseId > 0) {
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        val api = NeteaseApi.create()
                        val freshUrl = withContext(Dispatchers.IO) {
                            api.tryGetSongUrl(song.neteaseId)
                        }
                        if (freshUrl != null) {
                            val freshSong = song.copy(filePath = freshUrl)
                            val idx = sorted.indexOf(song)
                            val updatedList = sorted.toMutableList()
                            if (idx >= 0) updatedList[idx] = freshSong
                            MusicController.play(requireContext(), updatedList, idx.coerceAtLeast(0))
                            findNavController().navigate(R.id.navigation_player)
                        } else {
                            Toast.makeText(requireContext(), R.string.online_play_failed, Toast.LENGTH_SHORT).show()
                        }
                    } catch (_: Exception) { }
                }
            } else {
                val index = sorted.indexOf(song)
                MusicController.play(requireContext(), sorted, index.coerceAtLeast(0))
                findNavController().navigate(R.id.navigation_player)
            }
        }
        binding.rvSongs.adapter = adapter
        adapter.submitList(sorted)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        /** 歌单 ID：>0 普通歌单，-1 最近播放，-2 本地音乐 */
        const val ARG_PLAYLIST_ID = "playlist_id"
        /** 歌单名称，用于标题栏显示 */
        const val ARG_PLAYLIST_NAME = "playlist_name"
        /** 歌曲 ID 数组，用于推荐歌单展示 */
        const val ARG_SONG_IDS = "song_ids"
    }
}
