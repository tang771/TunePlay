package com.example.tuneplay.ui.home

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.widget.PopupWindow
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.flexbox.FlexboxLayoutManager
import com.google.android.flexbox.FlexDirection
import com.google.android.flexbox.FlexWrap
import com.example.tuneplay.R
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.databinding.FragmentHomeBinding
import com.example.tuneplay.player.MusicController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 Fragment — 音乐库的主界面。
 * 走马灯轮播独立于其他内容区域（推荐/歌单/发现），数据不足时只隐藏走马灯不影响其他区域。
 * 搜索支持本地歌曲过滤和在线搜索（网易云），点击歌曲直接播放并导航到播放器。
 */
class HomeFragment : Fragment() {

    private var _binding: FragmentHomeBinding? = null
    private val binding get() = _binding!!
    private lateinit var homeViewModel: HomeViewModel
    private lateinit var unifiedAdapter: UnifiedSongAdapter
    private lateinit var searchHistoryAdapter: SearchHistoryAdapter
    private var historyPopup: PopupWindow? = null
    private var resultsRv: RecyclerView? = null

    // Carousel
    private var carouselAdapter: CarouselAdapter? = null
    private val autoScrollHandler = Handler(Looper.getMainLooper())
    private var carouselSongs: List<Song> = emptyList()
    private var carouselActive = false
    private var recommendedAdapter: RecommendedSongAdapter? = null
    private var playlistAdapter: RecommendedPlaylistAdapter? = null
    private var discoverAdapter: RecommendedSongAdapter? = null

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
        _binding = FragmentHomeBinding.inflate(inflater, container, false)
        homeViewModel = ViewModelProvider(this)[HomeViewModel::class.java]

        // Create results RecyclerView programmatically, positioned below search box
        resultsRv = RecyclerView(requireContext()).apply {
            layoutManager = FlexboxLayoutManager(requireContext()).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
            clipToPadding = false
            val pad = (8 * resources.displayMetrics.density).toInt()
            setPadding(0, pad, 0, pad)
            visibility = View.GONE
            setBackgroundColor(0xFF080808.toInt())
        }
        binding.root.addView(resultsRv, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ).apply {
            topMargin = (60 * resources.displayMetrics.density).toInt() // 48dp bar + 12dp margin
        })

        unifiedAdapter = UnifiedSongAdapter(
            onLocalClick = { song ->
                buildPlaylistFromSearch(clickedLocal = song)
            },
            onOnlineClick = { onlineSong ->
                buildPlaylistFromSearch(clickedOnline = onlineSong)
            }
        )
        resultsRv?.adapter = unifiedAdapter

        setupSearchHistory()
        setupSearchBehavior()
        setupCarousel()
        setupRecommended()
        setupPlaylists()
        setupDiscover()

        return binding.root
    }

    // ── Carousel ──────────────────────────────────────────────────────

    private fun setupCarousel() {
        carouselAdapter = CarouselAdapter { song ->
            viewLifecycleOwner.lifecycleScope.launch {
                val allSongs = homeViewModel.likedSongs.value ?: return@launch
                if (allSongs.isEmpty()) return@launch
                val freshSong = homeViewModel.refreshSongUrl(song)
                val songs = allSongs.map { if (it.id == freshSong.id) freshSong else it }
                val idx = songs.indexOfFirst { it.id == freshSong.id }.coerceAtLeast(0)
                MusicController.setPlaylistName(getString(R.string.playlist_liked))
                MusicController.play(requireContext(), songs, idx)
                findNavController().navigate(R.id.navigation_player)
            }
        }

        binding.carousel.adapter = carouselAdapter
        binding.carousel.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageScrollStateChanged(state: Int) {
                if (state == ViewPager2.SCROLL_STATE_DRAGGING) {
                    stopAutoScroll()
                } else if (state == ViewPager2.SCROLL_STATE_IDLE) {
                    startAutoScroll()
                }
            }
        })

        homeViewModel.likedSongs.observe(viewLifecycleOwner) { songs ->
            val picked = pickDailySongs(songs)
            carouselSongs = picked
            stopAutoScroll()
            carouselAdapter?.submitList(picked)
            if (picked.size >= 3) {
                binding.carouselContainer.visibility = View.VISIBLE
                updateResultsTopMargin(showCarousel = true)
                // Defer setCurrentItem to let RecyclerView finish layout after notifyDataSetChanged
                binding.carousel.post {
                    binding.carousel.setCurrentItem(carouselAdapter!!.getStartPosition(), false)
                    startAutoScroll()
                }
            } else {
                binding.carouselContainer.visibility = View.GONE
                updateResultsTopMargin(showCarousel = false)
            }
        }
    }

    /** 根据日期间种子从收藏列表中轮选 3 首歌曲用于走马灯展示 */
    private fun pickDailySongs(songs: List<Song>): List<Song> {
        if (songs.isEmpty()) return emptyList()
        val todaySeed = (System.currentTimeMillis() / 86400000).toInt()
        val start = (todaySeed % songs.size).coerceIn(0, songs.size - 1)
        val result = mutableListOf<Song>()
        for (i in 0 until minOf(3, songs.size)) {
            result.add(songs[(start + i) % songs.size])
        }
        return result
    }

    private fun startAutoScroll() {
        stopAutoScroll()
        if (carouselSongs.size < 2) return
        carouselActive = true
        autoScrollHandler.postDelayed(autoScrollRunnable, 2000)
    }

    private fun stopAutoScroll() {
        carouselActive = false
        autoScrollHandler.removeCallbacks(autoScrollRunnable)
    }

    private val autoScrollRunnable = object : Runnable {
        override fun run() {
            if (!carouselActive || _binding == null) return
            if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
            try {
                val vp = binding.carousel
                if (vp.adapter?.itemCount ?: 0 > 0) {
                    vp.setCurrentItem(vp.currentItem + 1, true)
                }
            } catch (_: Exception) { }
            if (carouselActive && _binding != null) {
                autoScrollHandler.postDelayed(this, 2000)
            }
        }
    }

    private fun updateResultsTopMargin(showCarousel: Boolean) {
        val rv = resultsRv ?: return
        val lp = rv.layoutParams as? FrameLayout.LayoutParams ?: return
        val density = resources.displayMetrics.density
        // With NestedScrollView: large enough to push resultsRv below all content
        lp.topMargin = if (showCarousel) {
            (1400 * density).toInt()
        } else {
            (60 * density).toInt()
        }
        rv.layoutParams = lp
    }

    // ── Recommended songs ──────────────────────────────────────────

    private fun setupRecommended() {
        val rv = binding.recommendedRv
        rv.layoutManager = LinearLayoutManager(requireContext())
        recommendedAdapter = RecommendedSongAdapter { song ->
            viewLifecycleOwner.lifecycleScope.launch {
                val songs = homeViewModel.guessYouLike.value ?: return@launch
                val freshSong = homeViewModel.refreshSongUrl(song)
                val freshSongs = songs.map { if (it.id == freshSong.id) freshSong else it }
                val idx = freshSongs.indexOfFirst { it.id == freshSong.id }.coerceAtLeast(0)
                MusicController.play(requireContext(), freshSongs, idx)
                findNavController().navigate(R.id.navigation_player)
            }
        }
        rv.adapter = recommendedAdapter

        homeViewModel.guessYouLike.observe(viewLifecycleOwner) { songs ->
            recommendedAdapter?.submitList(songs)
        }
    }

    // ── Recommended playlists ──────────────────────────────────────

    private fun setupPlaylists() {
        val rv = binding.playlistRv
        rv.layoutManager = LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false)
        playlistAdapter = RecommendedPlaylistAdapter { playlist ->
            if (playlist.songs.isEmpty()) return@RecommendedPlaylistAdapter
            val bundle = Bundle().apply {
                putLongArray("song_ids", playlist.songs.map { it.id }.toLongArray())
                putString("playlist_name", playlist.name)
            }
            findNavController().navigate(R.id.navigation_playlist_songs, bundle)
        }
        rv.adapter = playlistAdapter

        homeViewModel.playlistRecommendations.observe(viewLifecycleOwner) { playlists ->
            playlistAdapter?.submitList(playlists)
        }
    }

    // ── Discover more songs ────────────────────────────────────────

    private fun setupDiscover() {
        val rv = binding.discoverRv
        rv.layoutManager = LinearLayoutManager(requireContext())
        discoverAdapter = RecommendedSongAdapter { song ->
            viewLifecycleOwner.lifecycleScope.launch {
                val songs = homeViewModel.discoverMore.value ?: return@launch
                val freshSong = homeViewModel.refreshSongUrl(song)
                val freshSongs = songs.map { if (it.id == freshSong.id) freshSong else it }
                val idx = freshSongs.indexOfFirst { it.id == freshSong.id }.coerceAtLeast(0)
                MusicController.play(requireContext(), freshSongs, idx)
                findNavController().navigate(R.id.navigation_player)
            }
        }
        rv.adapter = discoverAdapter

        homeViewModel.discoverMore.observe(viewLifecycleOwner) { songs ->
            discoverAdapter?.submitList(songs)
        }
    }

    // ── Search behavior ─────────────────────────────────────────────

    private fun setupSearchBehavior() {
        binding.etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val query = s?.toString() ?: ""
                homeViewModel.onSearchQueryChanged(query)
                binding.btnSearchClear.visibility = if (query.isNotEmpty()) View.VISIBLE else View.GONE
                if (query.isNotEmpty()) {
                    // Typing: dismiss history, hide carousel, show results
                    dismissHistoryPopup()
                    binding.carouselContainer.visibility = View.GONE
                    updateResultsTopMargin(showCarousel = false)
                    resultsRv?.visibility = View.VISIBLE
                } else {
                    // Empty: show history (if focused), hide results, show carousel if available
                    resultsRv?.visibility = View.GONE
                    if (carouselSongs.size >= 3) {
                        binding.carouselContainer.visibility = View.VISIBLE
                        updateResultsTopMargin(showCarousel = true)
                        startAutoScroll()
                    }
                    if (binding.etSearch.hasFocus()) showHistoryPopup()
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })

        binding.btnSearchClear.setOnClickListener {
            binding.etSearch.text?.clear()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
        }

        binding.etSearch.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                binding.etSearch.clearFocus()
                dismissHistoryPopup()
                true
            } else false
        }

        binding.etSearch.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus && binding.etSearch.text.isNullOrBlank()) {
                showHistoryPopup()
            } else {
                dismissHistoryPopup()
            }
        }

        // Click on search box always shows history if text is blank
        binding.etSearch.setOnClickListener {
            if (binding.etSearch.text.isNullOrBlank()) showHistoryPopup()
        }

        homeViewModel.combinedItems.observe(viewLifecycleOwner) { items ->
            unifiedAdapter.submitList(items)
        }
    }

    // ── Search history popup ────────────────────────────────────────

    private fun setupSearchHistory() {
        searchHistoryAdapter = SearchHistoryAdapter(
            onItemClick = { query ->
                binding.etSearch.setText(query)
                binding.etSearch.setSelection(query.length)
                dismissHistoryPopup()
                // Hide keyboard and clear focus after selecting history
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.etSearch.windowToken, 0)
                binding.etSearch.clearFocus()
            },
            onDeleteClick = { query ->
                homeViewModel.deleteSearchHistory(query)
            }
        )
        homeViewModel.searchHistory.observe(viewLifecycleOwner) { history ->
            searchHistoryAdapter.submitList(history)
            // Reactively show popup when data arrives and conditions are right
            if (history.isNotEmpty()
                && binding.etSearch.hasFocus()
                && binding.etSearch.text.isNullOrBlank()
            ) {
                showHistoryPopup()
            }
        }
    }

    private fun showHistoryPopup() {
        val history = homeViewModel.searchHistory.value ?: return
        if (history.isEmpty()) return
        if (historyPopup?.isShowing == true) return

        val rv = RecyclerView(requireContext()).apply {
            layoutManager = FlexboxLayoutManager(requireContext()).apply {
                flexDirection = FlexDirection.ROW
                flexWrap = FlexWrap.WRAP
            }
            adapter = searchHistoryAdapter
            setBackgroundColor(0xFF1A1A1A.toInt())
        }

        val popupHeight = (resources.displayMetrics.heightPixels * 0.3).toInt()
        historyPopup = PopupWindow(
            rv,
            binding.searchContainer.width,
            popupHeight,
            true
        ).apply {
            isOutsideTouchable = true
            isFocusable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            setBackgroundDrawable(
                android.graphics.drawable.ColorDrawable(0xFF1A1A1A.toInt())
            )
            elevation = 8f * resources.displayMetrics.density
            showAsDropDown(binding.searchContainer, 0, 4)
        }

        historyPopup?.setOnDismissListener { historyPopup = null }
    }

    private fun dismissHistoryPopup() {
        historyPopup?.dismiss()
        historyPopup = null
    }

    // ── Play from search results 从搜索结果播放 ────────────────────────

    /**
     * 从搜索结果构建播放列表：
     * - 在线歌曲：先解析目标歌曲 → 立即播放 → 后台解析剩余歌曲并追加到队列
     * - 本地歌曲：收集列表中所有本地歌曲构建队列
     */
    private fun buildPlaylistFromSearch(
        clickedLocal: Song? = null,
        clickedOnline: com.example.tuneplay.data.network.OnlineSong? = null
    ) {
        val ctx = context ?: return
        if (!viewLifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        // Capture NavController before launching coroutine — findNavController()
        // may fail inside the coroutine if fragment state changes
        val navController = findNavController()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val items = homeViewModel.combinedItems.value ?: emptyList()
                if (items.isEmpty()) return@launch

                when {
                    // ── Online song: resolve clicked first, play immediately ──
                    clickedOnline != null -> {
                        val resolved = homeViewModel.resolveOnlineSong(clickedOnline)
                        if (resolved == null) {
                            Toast.makeText(ctx, R.string.online_play_failed, Toast.LENGTH_SHORT).show()
                            return@launch
                        }
                        MusicController.setPlaylistName(getString(R.string.app_name))
                        MusicController.play(ctx, listOf(resolved), 0)
                        navController.navigate(R.id.navigation_player)

                        // Background: resolve remaining online songs via ViewModel scope
                        // (NOT lifecycleScope — it gets cancelled when we navigate away)
                        val otherOnline = items.filterIsInstance<SongItem.Online>()
                            .filter { it.song.id != clickedOnline.id }
                        if (otherOnline.isNotEmpty()) {
                            homeViewModel.resolveAndAppendToPlaylist(otherOnline.map { it.song })
                        }
                    }
                    // ── Local song: build playlist from combined items ──
                    clickedLocal != null -> {
                        val playlist = mutableListOf<Song>()
                        var clickIndex = -1
                        for (item in items) {
                            if (item is SongItem.Local) {
                                if (item.song.id == clickedLocal.id) clickIndex = playlist.size
                                playlist.add(item.song)
                            }
                        }
                        if (playlist.isEmpty()) return@launch
                        MusicController.setPlaylistName(getString(R.string.app_name))
                        MusicController.play(ctx, playlist, clickIndex.coerceAtLeast(0))
                        navController.navigate(R.id.navigation_player)
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("HomeFragment", "Play from search failed", e)
                Toast.makeText(ctx, R.string.online_play_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    // ── Permissions & scanning ──────────────────────────────────────

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

    private fun requestMediaPermission() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions)
    }

    private fun scanLibrary() {
        homeViewModel.scanMediaStore { count ->
            val msg = if (count > 0) {
                getString(R.string.import_scan_result, count)
            } else {
                getString(R.string.import_scan_empty)
            }
            Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
        }
    }

    private fun importFiles(uris: List<Uri>) {
        viewLifecycleOwner.lifecycleScope.launch {
            homeViewModel.setScanning(true)
            try {
                var imported = 0
                for (uri in uris) {
                    try {
                        requireContext().contentResolver.takePersistableUriPermission(
                            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
                        )
                    } catch (_: SecurityException) { }
                    val song = withContext(Dispatchers.IO) { buildSongFromUri(uri) }
                    if (song != null) {
                        withContext(Dispatchers.IO) { homeViewModel.insertSong(song) }
                        imported++
                    }
                }
                val msg = if (imported == 1) getString(R.string.import_file_added)
                else getString(R.string.import_files_added, imported)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            } finally {
                homeViewModel.setScanning(false)
            }
        }
    }

    /** 从 Content URI 构建 Song 对象 — 通过 MediaStore 查询元数据，失败时回退到文件名解析 */
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

    override fun onDestroyView() {
        super.onDestroyView()
        stopAutoScroll()
        dismissHistoryPopup()
        resultsRv = null
        _binding = null
    }
}
