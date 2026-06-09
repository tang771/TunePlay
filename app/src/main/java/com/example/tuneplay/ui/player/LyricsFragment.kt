package com.example.tuneplay.ui.player

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.bumptech.glide.Glide
import com.example.tuneplay.R
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.data.local.LrcParser
import com.example.tuneplay.data.network.NeteaseApi
import com.example.tuneplay.data.repository.MusicRepository
import com.example.tuneplay.databinding.FragmentLyricsBinding
import com.example.tuneplay.player.MusicController
import com.example.tuneplay.ui.home.formatDuration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest

/**
 * 歌词页面 — 全屏歌词显示 + 毛玻璃模糊背景。
 * 支持 LRC 本地文件解析和网易云在线歌词获取。
 * 下滑手势关闭、点击歌词行跳转、3 秒无操作恢复自动滚动。
 */
class LyricsFragment : Fragment() {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!

    private lateinit var lrcAdapter: LrcAdapter
    private lateinit var repository: MusicRepository
    private var autoScrollLrc = true
    private val scrollHandler = Handler(Looper.getMainLooper())
    private val api = NeteaseApi.create()
    private var currentSongId: Long = 0L
    private var isLiked = false
    private var isUserSeeking = false

    // Swipe-down-to-dismiss state
    private var trackingSwipe = false
    private var initialY = 0f
    private val dismissThreshold = 150f

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLyricsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val db = AppDatabase.getInstance(requireContext())
        repository = MusicRepository(db.songDao(), db.historyDao(), db.playlistDao(), db.searchHistoryDao())

        setupBackButton()
        setupLyrics()
        setupSeekBar()
        setupControls()
        setupActionButtons()
        setupSwipeToDismiss()
        setupBlurBackground()

        viewLifecycleOwner.lifecycleScope.launch {
            loadLyrics()
        }
        collectPlaybackState()
    }

    private fun setupBackButton() {
        binding.btnLyricsBack.setOnClickListener {
            findNavController().navigateUp()
        }
    }

    private fun setupLyrics() {
        lrcAdapter = LrcAdapter { line ->
            MusicController.seekTo(requireContext(), line.timeMs)
            autoScrollLrc = false
            scrollHandler.removeCallbacksAndMessages(null)
            scrollHandler.postDelayed({ autoScrollLrc = true }, 3000L)
        }
        binding.rvLyrics.layoutManager = LinearLayoutManager(requireContext())
        binding.rvLyrics.adapter = lrcAdapter

        // Pause auto-scroll when user touches the list
        binding.rvLyrics.setOnTouchListener { _, event ->
            if (event.action == MotionEvent.ACTION_DOWN) {
                autoScrollLrc = false
                scrollHandler.removeCallbacksAndMessages(null)
                scrollHandler.postDelayed({ autoScrollLrc = true }, 3000L)
            }
            false
        }
    }

    private fun setupSeekBar() {
        binding.seekLyrics.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    binding.tvLyricsCurrentTime.text = formatDuration(progress.toLong())
                }
            }

            override fun onStartTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = true
            }

            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                isUserSeeking = false
                seekBar?.progress?.let { progress ->
                    MusicController.seekTo(requireContext(), progress.toLong())
                }
            }
        })
    }

    private fun setupControls() {
        binding.btnLyricsPrev.setOnClickListener {
            MusicController.skipPrevious(requireContext())
        }
        binding.btnLyricsPlayPause.setOnClickListener {
            MusicController.togglePlayPause(requireContext())
        }
        binding.btnLyricsNext.setOnClickListener {
            MusicController.skipNext(requireContext())
        }
        binding.btnLyricsShuffle.setOnClickListener {
            MusicController.cyclePlayMode()
        }
        binding.btnLyricsRepeat.setOnClickListener {
            val playlist = MusicController.currentPlaylist.value
            if (playlist.isEmpty()) {
                Toast.makeText(requireContext(), "播放列表为空", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            PlaylistSheetFragment(playlist).show(parentFragmentManager, "PlaylistSheet")
        }
    }

    private fun setupActionButtons() {
        binding.btnLyricsLike.setOnClickListener {
            viewLifecycleOwner.lifecycleScope.launch {
                val likedPlaylist = repository.getPlaylistByName(
                    getString(R.string.playlist_liked)
                ) ?: return@launch
                isLiked = repository.toggleSongInPlaylist(likedPlaylist.id, currentSongId)
                updateLikeButton()
                if (isLiked) {
                    Toast.makeText(requireContext(), "已添加到收藏歌单", Toast.LENGTH_SHORT).show()
                }
            }
        }
        binding.btnLyricsAdd.setOnClickListener {
            if (currentSongId != 0L) {
                PlaylistSelectSheetFragment.newInstance(currentSongId)
                    .show(parentFragmentManager, "PlaylistSelectSheet")
            }
        }
    }

    private fun updateLikeButton() {
        binding.btnLyricsLike.setImageResource(
            if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart
        )
    }

    /** 下滑关闭手势 — 仅当 RecyclerView 滚到顶部时触发，超过阈值或 1/3 高度则关闭 */
    private fun setupSwipeToDismiss() {
        binding.root.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    // Only track swipe if RecyclerView is at very top
                    val lm = binding.rvLyrics.layoutManager as? LinearLayoutManager
                    val atTop = lm != null &&
                        lm.findFirstVisibleItemPosition() == 0 &&
                        (lm.findViewByPosition(0)?.top ?: -1) >= 0
                    if (atTop) {
                        trackingSwipe = true
                        initialY = event.rawY
                    } else {
                        trackingSwipe = false
                    }
                    trackingSwipe
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!trackingSwipe) return@setOnTouchListener false
                    val deltaY = event.rawY - initialY
                    if (deltaY > 0) {
                        binding.root.translationY = deltaY
                        binding.root.alpha = 1f - (deltaY / dismissThreshold * 0.5f).coerceIn(0f, 0.8f)
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (!trackingSwipe) {
                        trackingSwipe = false
                        return@setOnTouchListener false
                    }
                    trackingSwipe = false
                    val deltaY = event.rawY - initialY
                    if (deltaY > dismissThreshold || deltaY > binding.root.height / 3) {
                        binding.root.animate()
                            .translationY(binding.root.height.toFloat())
                            .alpha(0f)
                            .setDuration(200)
                            .withEndAction { findNavController().navigateUp() }
                            .start()
                    } else {
                        binding.root.animate()
                            .translationY(0f)
                            .alpha(1f)
                            .setDuration(200)
                            .start()
                    }
                    true
                }
                else -> trackingSwipe
            }
        }
    }

    private fun setupBlurBackground() {
        val song = MusicController.getCurrentSong() ?: return
        Glide.with(this)
            .load(song.coverArtPath)
            .placeholder(R.drawable.ic_music_note)
            .transform(BlurTransform(20))
            .into(binding.ivLyricsBlurBg)
    }

    /** 加载歌词 — 优先本地 LRC 文件，在线歌曲回退到网易云 API */
    private suspend fun loadLyrics() {
        val song = MusicController.getCurrentSong()
        binding.tvLyricsInfoTitle.text = song?.title ?: ""
        binding.tvLyricsInfoArtist.text = song?.artist ?: ""

        // Try local LRC first, then online API for online songs
        var lyrics = if (song?.lrcPath != null) {
            LrcParser.parseFromFile(song.lrcPath)
        } else null

        // For online songs (no local LRC), fetch from Netease API
        if (lyrics == null && song != null) {
            val neteaseId = MusicController.currentNeteaseId.value
            if (neteaseId > 0) {
                binding.tvLyricsInfoTitle.text = "${song.title} (加载歌词…)"
                val lrcText = withContext(Dispatchers.IO) {
                    try {
                        api.getLyric(neteaseId).lrc?.lyric
                    } catch (_: Exception) { null }
                }
                if (lrcText != null && lrcText.isNotBlank()) {
                    lyrics = LrcParser.parse(lrcText)
                }
            }
        }

        // Update display
        binding.tvLyricsInfoTitle.text = song?.title ?: ""
        if (lyrics != null && lyrics.isNotEmpty()) {
            lrcAdapter.setLyrics(lyrics)
            autoScrollLrc = true
            binding.rvLyrics.visibility = View.VISIBLE
            binding.llLyricsEmpty.visibility = View.GONE
            // Scroll first line above center after layout is complete
            binding.rvLyrics.viewTreeObserver.addOnPreDrawListener(
                object : android.view.ViewTreeObserver.OnPreDrawListener {
                    override fun onPreDraw(): Boolean {
                        binding.rvLyrics.viewTreeObserver.removeOnPreDrawListener(this)
                        val lm = binding.rvLyrics.layoutManager as? LinearLayoutManager
                        val rv = binding.rvLyrics
                        val visibleCenter = rv.paddingTop + (rv.height - rv.paddingTop - rv.paddingBottom) / 2
                        val oneLinePx = (32 * resources.displayMetrics.density).toInt()
                        val offset = (visibleCenter - oneLinePx).coerceAtLeast(0)
                        lm?.scrollToPositionWithOffset(0, offset)
                        return true
                    }
                })
        } else {
            lrcAdapter.setLyrics(emptyList())
            binding.rvLyrics.visibility = View.GONE
            binding.llLyricsEmpty.visibility = View.VISIBLE

            if (song != null) {
                Glide.with(this)
                    .load(song.coverArtPath)
                    .placeholder(R.drawable.ic_music_note)
                    .into(binding.ivLyricsEmptyArt)
            }
        }
    }

    private fun collectPlaybackState() {
        viewLifecycleOwner.lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    MusicController.currentSong.collect { song ->
                        if (song != null) {
                            binding.tvLyricsInfoTitle.text = song.title
                            binding.tvLyricsInfoArtist.text = song.artist

                            val songChanged = song.id != currentSongId
                            currentSongId = song.id
                            lifecycleScope.launch {
                                val likedPlaylist = repository.getPlaylistByName(
                                    getString(R.string.playlist_liked)
                                )
                                if (likedPlaylist != null) {
                                    isLiked = repository.isSongInPlaylist(
                                        likedPlaylist.id, song.id
                                    )
                                    updateLikeButton()
                                }
                            }
                            // Reload lyrics and blur background when song changes
                            if (songChanged) {
                                loadLyrics()
                                setupBlurBackground()
                            }
                        }
                    }
                }
                launch {
                    MusicController.isPlaying.collect { playing ->
                        binding.btnLyricsPlayPause.setImageResource(
                            if (playing) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                    }
                }
                launch {
                    combine(
                        MusicController.position,
                        MusicController.duration
                    ) { pos, dur -> Pair(pos, dur) }.collect { (pos, dur) ->
                        if (!isUserSeeking) {
                            binding.tvLyricsCurrentTime.text = formatDuration(pos)
                            binding.tvLyricsDuration.text = formatDuration(dur)
                            if (dur > 0) {
                                binding.seekLyrics.max = dur.toInt()
                                binding.seekLyrics.progress = pos.toInt()
                            }
                        }
                        lrcAdapter.setCurrentLine(pos)
                        if (autoScrollLrc) {
                            val linePos = lrcAdapter.getCurrentLinePosition()
                            if (linePos >= 0) {
                                val lm = binding.rvLyrics.layoutManager as? LinearLayoutManager
                                val rv = binding.rvLyrics
                                val visibleCenter = rv.paddingTop + (rv.height - rv.paddingTop - rv.paddingBottom) / 2
                                // Shift highlight up by one line (~32dp)
                                val oneLinePx = (32 * resources.displayMetrics.density).toInt()
                                val offset = (visibleCenter - oneLinePx).coerceAtLeast(0)
                                lm?.scrollToPositionWithOffset(linePos, offset)
                            }
                        }
                    }
                }
                launch {
                    MusicController.playMode.collect { mode ->
                        binding.btnLyricsShuffle.setImageResource(
                            when (mode) {
                                0 -> R.drawable.ic_shuffle
                                1 -> R.drawable.ic_repeat_one
                                else -> R.drawable.ic_shuffle
                            }
                        )
                        binding.btnLyricsShuffle.imageTintList = ColorStateList.valueOf(
                            if (mode != 0) 0xFFFFFFFF.toInt() else 0xFFAAAAAA.toInt()
                        )
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        scrollHandler.removeCallbacksAndMessages(null)
        _binding = null
    }
}

/**
 * 模糊变换 — Glide BitmapTransformation，通过缩放到 1/4 再放大回原尺寸实现模糊效果。
 * 用于歌词页背景，不修改磁盘缓存 key（添加 "blur_$radius" 后缀）。
 */
class BlurTransform(
    private val radius: Int = 20
) : BitmapTransformation() {

    override fun transform(
        pool: BitmapPool,
        toTransform: Bitmap,
        outWidth: Int,
        outHeight: Int
    ): Bitmap {
        val scaled = Bitmap.createScaledBitmap(
            toTransform,
            (toTransform.width / 4).coerceAtLeast(1),
            (toTransform.height / 4).coerceAtLeast(1),
            true
        )
        return Bitmap.createScaledBitmap(scaled, outWidth, outHeight, true)
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("blur_$radius".toByteArray())
    }
}
