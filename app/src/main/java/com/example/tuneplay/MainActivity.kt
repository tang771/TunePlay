package com.example.tuneplay

import android.Manifest
import android.animation.ObjectAnimator
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.ViewOutlineProvider
import android.view.animation.LinearInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.bumptech.glide.Glide
import com.example.tuneplay.databinding.ActivityMainBinding
import com.example.tuneplay.player.MusicController
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 主 Activity — 管理底部导航、迷你播放器。
 * 迷你播放器在非播放器/歌词页面时显示，播放时专辑封面旋转动画。
 * Android 13+ 请求通知权限以启用前台服务。
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var rotationAnimator: ObjectAnimator? = null

    // Mini player child views — resolved from included layout root
    private lateinit var miniAlbumArt: ImageView
    private lateinit var miniTitle: TextView
    private lateinit var miniArtist: TextView
    private lateinit var miniSeek: ProgressBar
    private lateinit var miniPlayPause: ImageButton
    private lateinit var miniSkipPrev: ImageButton
    private lateinit var miniSkipNext: ImageButton
    private lateinit var miniRoot: View

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* granted or not, service handles fallback */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        val navHostFragment = supportFragmentManager
            .findFragmentById(R.id.nav_host_fragment_activity_main) as NavHostFragment
        val navController = navHostFragment.navController
        binding.navView.setupWithNavController(navController)

        // Liquid glass: blur system wallpaper behind translucent overlays
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            window.setBackgroundBlurRadius(25)
        }

        resolveMiniPlayerViews()
        setupMiniPlayer(navController)
    }

    /** 从 include 布局中解析迷你播放器的子 View 引用 */
    private fun resolveMiniPlayerViews() {
        val root = binding.miniPlayer.root
        miniRoot = root
        miniAlbumArt = root.findViewById(R.id.iv_mini_album_art)
        miniTitle = root.findViewById(R.id.tv_mini_title)
        miniArtist = root.findViewById(R.id.tv_mini_artist)
        miniSeek = root.findViewById(R.id.seek_mini)
        miniPlayPause = root.findViewById(R.id.btn_mini_play_pause)
        miniSkipPrev = root.findViewById(R.id.btn_mini_skip_prev)
        miniSkipNext = root.findViewById(R.id.btn_mini_skip_next)

        // Make album art circular
        miniAlbumArt.clipToOutline = true
        miniAlbumArt.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: android.graphics.Outline) {
                outline.setOval(0, 0, view.width, view.height)
            }
        }
    }

    private fun setupMiniPlayer(navController: NavController) {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                // Current song data
                launch {
                    MusicController.currentSong.collect { song ->
                        if (song != null) {
                            miniTitle.text = song.title
                            miniArtist.text = song.artist
                            Glide.with(this@MainActivity)
                                .load(song.coverArtPath)
                                .placeholder(R.drawable.ic_music_note)
                                .circleCrop()
                                .into(miniAlbumArt)
                        }
                    }
                }

                // Playing state → button icon + rotation
                launch {
                    MusicController.isPlaying.collect { playing ->
                        miniPlayPause.setImageResource(
                            if (playing) android.R.drawable.ic_media_pause
                            else android.R.drawable.ic_media_play
                        )
                        if (playing) startRotation() else pauseRotation()
                    }
                }

                // Progress
                launch {
                    combine(
                        MusicController.position,
                        MusicController.duration
                    ) { pos, dur -> Pair(pos, dur) }.collect { (pos, dur) ->
                        if (dur > 0) {
                            miniSeek.progress = (pos * 1000 / dur).toInt()
                        }
                    }
                }
            }
        }

        // Show/hide mini player and bottom nav based on destination
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val onPlayerPage = destination.id == R.id.navigation_player
            val onLyricsPage = destination.id == R.id.navigation_lyrics
            val hasSong = MusicController.getCurrentSong() != null
            // Hide mini player on player/lyrics page; show elsewhere when a song is loaded
            miniRoot.visibility = if (onPlayerPage || onLyricsPage || !hasSong) View.GONE else View.VISIBLE
            // Hide bottom nav on player/lyrics page
            binding.navView.visibility = if (onPlayerPage || onLyricsPage) View.GONE else View.VISIBLE
        }

        // Controls
        miniPlayPause.setOnClickListener {
            MusicController.togglePlayPause(this)
        }
        miniSkipPrev.setOnClickListener {
            MusicController.skipPrevious(this)
        }
        miniSkipNext.setOnClickListener {
            MusicController.skipNext(this)
        }
        miniRoot.setOnClickListener {
            navController.navigate(R.id.navigation_player)
        }
    }

    /** 启动迷你播放器封面旋转动画（8 秒一圈，循环） */
    private fun startRotation() {
        if (rotationAnimator?.isRunning == true) return
        rotationAnimator = ObjectAnimator.ofFloat(miniAlbumArt, "rotation", 0f, 360f).apply {
            duration = 8000L
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    /** 暂停旋转动画，保留当前角度 */
    private fun pauseRotation() {
        rotationAnimator?.let {
            if (it.isRunning) {
                val currentRotation = miniAlbumArt.rotation
                it.cancel()
                miniAlbumArt.rotation = currentRotation
            }
        }
    }

    override fun onDestroy() {
        rotationAnimator?.cancel()
        super.onDestroy()
    }
}
