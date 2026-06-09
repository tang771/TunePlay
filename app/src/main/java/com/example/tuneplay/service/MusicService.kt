package com.example.tuneplay.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import androidx.core.app.NotificationCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.tuneplay.MainActivity
import com.example.tuneplay.R
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.data.local.entity.PlaybackHistory
import com.example.tuneplay.data.network.NeteaseApi
import com.example.tuneplay.data.network.tryGetSongUrl
import com.example.tuneplay.player.MusicController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 音乐播放前台服务 — 管理 ExoPlayer 生命周期和通知栏控制。
 * 接收来自 MusicController 的 Intent 命令执行播放/暂停/切歌/拖拽，
 * 通过 MediaSession 支持锁屏控制和蓝牙耳机。
 *
 * 播放历史：开始播放时 insert，每 5 秒定时 persist 当前进度，切歌时 finalize。
 * 在线歌曲：直连 CDN，遇 403 自动通过本地 stream-relay 中继重试一次。
 */
class MusicService : Service() {

    private lateinit var player: ExoPlayer
    private lateinit var mediaSession: MediaSession
    /** 当前歌曲信息 — 用于通知栏和日志 */
    private var currentTitle: String = ""
    private var currentArtist: String = ""
    /** IO 协程作用域 — 数据库写入操作 */
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    /** 主线程协程作用域 — 定时更新播放进度 */
    private val mainScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var positionUpdater: kotlinx.coroutines.Job? = null
    /** 当前播放记录 — 用于 songId + startTime 组合定位记录写入时长 */
    private var currentPlaySongId: Long = 0L
    private var playbackStartTime: Long = 0L
    /** 当前播放网易云 ID — 用于 403 重试时重新获取 URL */
    private var currentNeteaseId: Long = 0L
    private var urlRetryCount: Int = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        player = ExoPlayer.Builder(this).build()
        setupMediaSession()
        setupPlayerListener()
    }

    /**
     * 处理来自 MusicController 的播放命令。
     * ACTION_PLAY: 结算上一首歌的播放时长，开始播放新歌并记录历史。
     * ACTION_PLAY_PAUSE: 切换播放/暂停状态。
     * ACTION_SKIP_NEXT/PREV: 通过 MusicController 跳转下一首/上一首。
     */
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            when (intent?.action) {
                ACTION_PLAY -> {
                    try {
                        // 先结算上一首歌的播放时长
                        finalizeCurrentPlayback()
                        val path = intent.getStringExtra(EXTRA_SONG_PATH) ?: return START_NOT_STICKY
                        currentTitle = intent.getStringExtra(EXTRA_SONG_TITLE) ?: "Unknown"
                        currentArtist = intent.getStringExtra(EXTRA_SONG_ARTIST) ?: "Unknown"
                        val songId = intent.getLongExtra(EXTRA_SONG_ID, 0L)
                        currentNeteaseId = intent.getLongExtra(EXTRA_SONG_NETEASE_ID, 0L)
                        urlRetryCount = 0
                        // 立即显示通知（包含 startForeground 调用，防止 ANR）
                        updateNotification()
                        playFile(path)
                        recordPlaybackHistory(songId)
                    } catch (e: Exception) {
                        android.util.Log.e("MusicService", "ACTION_PLAY failed", e)
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        stopSelf()
                    }
                }
                ACTION_PLAY_PAUSE -> {
                    updateNotification()
                    if (player.isPlaying) player.pause() else player.play()
                }
                ACTION_STOP -> {
                    finalizeCurrentPlayback()
                    stopPlayback()
                }
                ACTION_SEEK -> {
                    val pos = intent.getLongExtra(EXTRA_SEEK_POSITION, 0L)
                    player.seekTo(pos)
                }
                ACTION_SKIP_NEXT -> {
                    finalizeCurrentPlayback()
                    MusicController.skipNext(this)
                }
                ACTION_SKIP_PREV -> {
                    finalizeCurrentPlayback()
                    MusicController.skipPrevious(this)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "onStartCommand failed", e)
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        finalizeCurrentPlayback()
        stopPositionUpdater()
        serviceScope.cancel()
        mainScope.cancel()
        player.release()
        mediaSession.release()
        super.onDestroy()
    }

    /**
     * 加载并播放音频文件。
     * 先 stop + clearMediaItems 清理残留，再根据 path 类型构造 URI：
     * - 本地文件：file:// URI
     * - MediaStore：content:// URI
     * - 在线流媒体：直连 URL，[viaRelay] 为 true 时走本地 3001 端口中继转发
     */
    private fun playFile(path: String, viaRelay: Boolean = false) {
        try {
            android.util.Log.d("MusicService", "playFile: ${path.take(120)} relay=$viaRelay")
            player.stop()
            player.clearMediaItems()
            val uri = when {
                path.startsWith("http") && viaRelay -> {
                    val encoded = java.net.URLEncoder.encode(path, "UTF-8")
                    Uri.parse("http://10.0.2.2:3001/stream?url=$encoded")
                }
                path.startsWith("http") || path.startsWith("content") -> Uri.parse(path)
                else -> Uri.parse("file://$path")
            }
            val mediaItem = MediaItem.fromUri(uri)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()
        } catch (e: Exception) {
            android.util.Log.e("MusicService", "playFile exception: ${e.message}", e)
        }
    }

    /** 停止播放 — 移除通知、释放播放器状态、停止 Service 自身 */
    private fun stopPlayback() {
        stopPositionUpdater()
        player.stop()
        MusicController.updatePlaybackState(false, 0, 0)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /** 在 playback_history 表插入一条新记录（playDuration=0），后续由 finalize 或定时 persist 更新 */
    private fun recordPlaybackHistory(songId: Long) {
        if (songId == 0L) {
            android.util.Log.w("MusicService", "recordPlaybackHistory: songId is 0, skipping")
            return
        }
        currentPlaySongId = songId
        playbackStartTime = System.currentTimeMillis()
        serviceScope.launch {
            try {
                AppDatabase.getInstance(this@MusicService).historyDao()
                    .insertHistory(PlaybackHistory(songId = songId, playedAt = playbackStartTime))
                android.util.Log.d("MusicService", "recordPlaybackHistory: inserted songId=$songId startTime=$playbackStartTime")
            } catch (e: Exception) {
                android.util.Log.e("MusicService", "recordPlaybackHistory failed", e)
            }
        }
    }

    /** 结算当前歌曲的播放时长 — 仅记录超过 500ms 的播放，通过 songId + startTime 定位记录 */
    private fun finalizeCurrentPlayback() {
        if (currentPlaySongId == 0L) return
        val elapsed = System.currentTimeMillis() - playbackStartTime
        if (elapsed > 500) {
            serviceScope.launch {
                try {
                    AppDatabase.getInstance(this@MusicService).historyDao()
                        .updatePlayDurationByStart(currentPlaySongId, playbackStartTime, elapsed)
                    android.util.Log.d("MusicService", "finalizeCurrentPlayback: songId=$currentPlaySongId elapsed=$elapsed")
                } catch (e: Exception) {
                    android.util.Log.e("MusicService", "finalizePlayback failed", e)
                }
            }
        }
        currentPlaySongId = 0L
        playbackStartTime = 0L
    }

    /** 初始化 MediaSession — 支持锁屏控制、蓝牙耳机按键映射 */
    private fun setupMediaSession() {
        mediaSession = MediaSession(this, "TunePlay")
        mediaSession.setCallback(object : MediaSession.Callback() {
            override fun onPlay() {
                player.play()
            }
            override fun onPause() {
                player.pause()
            }
            override fun onSkipToNext() {
                MusicController.skipNext(this@MusicService)
            }
            override fun onSkipToPrevious() {
                MusicController.skipPrevious(this@MusicService)
            }
            override fun onSeekTo(pos: Long) {
                player.seekTo(pos)
            }
        })
        mediaSession.isActive = true
    }

    /** 注册 ExoPlayer 回调 — 状态变更、播放结束、错误重试（403 → 中继）、播放/暂停切换 */
    private fun setupPlayerListener() {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                when (state) {
                    Player.STATE_READY -> {
                        val duration = player.duration.coerceAtLeast(0L)
                        MusicController.updatePlaybackState(player.isPlaying, player.currentPosition, duration)
                        updateNotification()
                        if (player.isPlaying) startPositionUpdater()
                    }
                    Player.STATE_ENDED -> {
                        finalizeCurrentPlayback()
                        MusicController.updatePlaybackState(false, 0, 0)
                        stopPositionUpdater()
                        stopForeground(STOP_FOREGROUND_REMOVE)
                        MusicController.skipNext(this@MusicService)
                    }
                    Player.STATE_BUFFERING -> {}
                    Player.STATE_IDLE -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                val duration = player.duration.coerceAtLeast(0L)
                MusicController.updatePlaybackState(isPlaying, player.currentPosition, duration)
                updateNotification()
                if (isPlaying) startPositionUpdater() else stopPositionUpdater()
            }

            override fun onPlayerError(error: PlaybackException) {
                android.util.Log.e("MusicService", "Player error code=${error.errorCode} msg=${error.message}", error)
                // Retry online song on source error (403/timeout) by re-fetching the stream URL
                val isSourceError = error.errorCode == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
                    || error.errorCode == PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
                    || error.errorCode == PlaybackException.ERROR_CODE_IO_UNSPECIFIED
                if (isSourceError && currentNeteaseId > 0 && urlRetryCount < 1) {
                    urlRetryCount++
                    android.util.Log.w("MusicService", "Retrying via relay neteaseId=$currentNeteaseId")
                    serviceScope.launch {
                        try {
                            val api = NeteaseApi.create()
                            val freshUrl = api.tryGetSongUrl(currentNeteaseId)
                            if (freshUrl != null) {
                                withContext(Dispatchers.Main) {
                                    playFile(freshUrl, viaRelay = true)
                                    startPositionUpdater()
                                }
                                return@launch
                            }
                        } catch (_: Exception) { }
                        withContext(Dispatchers.Main) { stopPlayback() }
                    }
                    return
                }
                MusicController.updatePlaybackState(false, 0, 0)
                stopPositionUpdater()
                stopForeground(STOP_FOREGROUND_REMOVE)
            }
        })
    }

    /** 启动进度更新协程 — 每 250ms 同步进度到 MusicController，每 5s 持久化播放时长到数据库 */
    private fun startPositionUpdater() {
        stopPositionUpdater()
        positionUpdater = mainScope.launch {
            var tick = 0
            while (true) {
                if (player.isPlaying) {
                    MusicController.updatePlaybackState(true, player.currentPosition, player.duration)
                    tick++
                    if (tick >= 20) {
                        tick = 0
                        persistCurrentDuration(player.currentPosition)
                    }
                }
                kotlinx.coroutines.delay(250)
            }
        }
    }

    /** 将当前播放时长写入数据库，使统计数据在播放过程中也能实时更新 */
    private fun persistCurrentDuration(elapsed: Long) {
        if (currentPlaySongId == 0L || elapsed <= 0) {
            android.util.Log.d("MusicService", "persistCurrentDuration: skipped songId=$currentPlaySongId elapsed=$elapsed")
            return
        }
        serviceScope.launch {
            try {
                AppDatabase.getInstance(this@MusicService).historyDao()
                    .updatePlayDurationByStart(currentPlaySongId, playbackStartTime, elapsed)
                android.util.Log.d("MusicService", "persistCurrentDuration: updated songId=$currentPlaySongId elapsed=$elapsed")
            } catch (_: Exception) {
                android.util.Log.e("MusicService", "persistCurrentDuration failed")
            }
        }
    }

    /** 停止进度更新协程 */
    private fun stopPositionUpdater() {
        positionUpdater?.cancel()
        positionUpdater = null
    }

    /** 创建通知渠道（Android 8.0+ 必需），IMPORTANCE_LOW 避免声音干扰 */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.notification_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_desc)
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    /** 构建并显示前台通知 — 含封面/标题/艺术家、播放/暂停、上一首/下一首操作按钮 */
    private fun updateNotification() {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (player.isPlaying) android.R.drawable.ic_media_pause
        else android.R.drawable.ic_media_play

        val playPauseIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_PLAY_PAUSE
        }
        val playPausePending = PendingIntent.getService(
            this, 1, playPauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skipNextIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_SKIP_NEXT
        }
        val skipNextPending = PendingIntent.getService(
            this, 2, skipNextIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val skipPrevIntent = Intent(this, MusicService::class.java).apply {
            action = ACTION_SKIP_PREV
        }
        val skipPrevPending = PendingIntent.getService(
            this, 3, skipPrevIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Build MediaSession metadata for lock screen
        val sessionToken = MediaSessionCompat.Token.fromToken(mediaSession.sessionToken)
        val mediaStyle = androidx.media.app.NotificationCompat.MediaStyle()
            .setMediaSession(sessionToken)
            .setShowActionsInCompactView(0, 1, 2)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(currentTitle)
            .setContentText(currentArtist)
            .setSmallIcon(R.drawable.ic_music_note)
            .setContentIntent(pendingIntent)
            .setStyle(mediaStyle)
            .addAction(android.R.drawable.ic_media_previous, "Previous", skipPrevPending)
            .addAction(playPauseIcon, "Play/Pause", playPausePending)
            .addAction(android.R.drawable.ic_media_next, "Next", skipNextPending)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(player.isPlaying)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        /** 通知渠道和 ID */
        const val CHANNEL_ID = "tuneplay_playback"
        const val NOTIFICATION_ID = 1
        /** Intent Action 常量 — 用于 MusicController → MusicService 通信 */
        const val ACTION_PLAY = "com.example.tuneplay.ACTION_PLAY"
        const val ACTION_PLAY_PAUSE = "com.example.tuneplay.ACTION_PLAY_PAUSE"
        const val ACTION_STOP = "com.example.tuneplay.ACTION_STOP"
        const val ACTION_SEEK = "com.example.tuneplay.ACTION_SEEK"
        const val ACTION_SKIP_NEXT = "com.example.tuneplay.ACTION_SKIP_NEXT"
        const val ACTION_SKIP_PREV = "com.example.tuneplay.ACTION_SKIP_PREV"
        const val EXTRA_SONG_PATH = "song_path"
        const val EXTRA_SONG_TITLE = "song_title"
        const val EXTRA_SONG_ARTIST = "song_artist"
        const val EXTRA_SONG_ALBUM = "song_album"
        const val EXTRA_SONG_DURATION = "song_duration"
        const val EXTRA_SONG_ALBUM_ART = "song_cover"
        const val EXTRA_SONG_ID = "song_id"
        const val EXTRA_SONG_NETEASE_ID = "song_netease_id"
        const val EXTRA_SEEK_POSITION = "seek_position"
        const val EXTRA_IS_ONLINE = "is_online"
    }
}
