package com.example.tuneplay.player

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.service.MusicService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 音乐播放控制器 — 全局单例，管理播放状态和命令下发。
 * 通过 StateFlow 暴露播放状态供 UI 观察，通过 Intent 向 MusicService 发送命令。
 * Intent 携带歌曲元数据（包括 neteaseId）用于播放历史记录和 403 重试。
 * 支持三种播放模式：顺序(0)、单曲循环(1)、随机(2)。
 */
object MusicController {

    /** 公共状态 — UI 通过 collect 订阅 */
    private val _currentSong = MutableStateFlow<Song?>(null)
    val currentSong: StateFlow<Song?> = _currentSong.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private val _duration = MutableStateFlow(0L)
    val duration: StateFlow<Long> = _duration.asStateFlow()

    /** 内部状态 — 播放列表和游标 */
    private var playlist: List<Song> = emptyList()
    private var currentIndex: Int = -1

    private val _currentPlaylist = MutableStateFlow<List<Song>>(emptyList())
    val currentPlaylist: StateFlow<List<Song>> = _currentPlaylist.asStateFlow()

    /** 当前在线歌曲的网易云 ID，用于歌词查询 */
    private val _currentNeteaseId = MutableStateFlow(0L)
    val currentNeteaseId: StateFlow<Long> = _currentNeteaseId.asStateFlow()

    /** 当前播放列表名称，用于 Now Playing 标题栏显示 */
    private val _playlistName = MutableStateFlow("")
    val playlistName: StateFlow<String> = _playlistName.asStateFlow()

    /** 播放模式: 0=顺序, 1=单曲循环, 2=随机 */
    private val _playMode = MutableStateFlow(0)
    val playMode: StateFlow<Int> = _playMode.asStateFlow()

    /** Cycle play mode: sequential → repeat one → shuffle → sequential */
    fun cyclePlayMode() {
        _playMode.value = (_playMode.value + 1) % 3
    }

    fun isShuffleOn(): Boolean = _playMode.value == 2
    fun isRepeatOne(): Boolean = _playMode.value == 1

    /**
     * 播放指定播放列表中的第 index 首歌曲。
     * 重置 position/duration/isPlaying 避免 UI 显示上一首歌的残留状态。
     */
    fun play(context: Context, songs: List<Song>, index: Int) {
        if (songs.isEmpty() || index !in songs.indices) return
        playlist = songs
        currentIndex = index
        _currentPlaylist.value = songs
        val song = songs[index]
        _currentSong.value = song
        _currentNeteaseId.value = song.neteaseId
        // 重置状态避免显示上一首歌的残留进度
        _isPlaying.value = false
        _position.value = 0L
        _duration.value = 0L
        sendCommand(context, MusicService.ACTION_PLAY, song)
    }

    /** 切换播放/暂停 */
    fun togglePlayPause(context: Context) {
        sendCommand(context, MusicService.ACTION_PLAY_PAUSE, null)
    }

    /** 下一首 — 根据播放模式（顺序/单曲循环/随机）计算索引后调用 [play] */
    fun skipNext(context: Context) {
        if (playlist.isEmpty()) return
        val nextIndex = if (_playMode.value == 2) {
            // Shuffle: pick random song
            (0 until playlist.size).filter { it != currentIndex }.randomOrNull() ?: 0
        } else {
            if (_playMode.value == 1) currentIndex
            else (currentIndex + 1) % playlist.size
        }
        play(context, playlist, nextIndex)
    }

    /** 上一首 — 边界时回到列表末尾 */
    fun skipPrevious(context: Context) {
        if (playlist.isEmpty()) return
        val prevIndex = if (currentIndex - 1 < 0) playlist.size - 1 else currentIndex - 1
        play(context, playlist, prevIndex)
    }

    /** 拖拽进度条 — 直接向 MusicService 发送 SEEK 命令 */
    fun seekTo(context: Context, position: Long) {
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_SEEK
            putExtra(MusicService.EXTRA_SEEK_POSITION, position)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    /** 停止播放并移除通知 */
    fun stop(context: Context) {
        sendCommand(context, MusicService.ACTION_STOP, null)
    }

    /** 由 MusicService 回调 — 更新播放状态供 UI 观察 */
    fun updatePlaybackState(isPlaying: Boolean, position: Long, duration: Long) {
        _isPlaying.value = isPlaying
        _position.value = position
        _duration.value = duration
    }

    /** 获取当前播放的歌曲，未播放时返回 null */
    fun getCurrentSong(): Song? = _currentSong.value

    /** 设置当前播放列表名称，用于 Now Playing 标题栏 */
    fun setPlaylistName(name: String) {
        _playlistName.value = name
    }

    /** 追加歌曲到当前播放队列末尾（用于后台解析在线歌曲后补充队列） */
    fun appendToPlaylist(song: com.example.tuneplay.data.local.entity.Song) {
        playlist = playlist + song
        _currentPlaylist.value = playlist
    }

    /** 播放在线流媒体歌曲 — 单曲播放，直接传递 URL 和 neteaseId */
    fun playOnline(context: Context, song: Song, neteaseId: Long) {
        playlist = listOf(song)
        currentIndex = 0
        _currentSong.value = song
        _currentPlaylist.value = listOf(song)
        _currentNeteaseId.value = neteaseId
        _isPlaying.value = false
        _position.value = 0L
        _duration.value = 0L
        val intent = Intent(context, MusicService::class.java).apply {
            action = MusicService.ACTION_PLAY
            putExtra(MusicService.EXTRA_SONG_PATH, song.filePath)
            putExtra(MusicService.EXTRA_SONG_TITLE, song.title)
            putExtra(MusicService.EXTRA_SONG_ARTIST, song.artist)
            putExtra(MusicService.EXTRA_SONG_ALBUM_ART, song.coverArtPath)
            putExtra(MusicService.EXTRA_SONG_ID, song.id)
            putExtra(MusicService.EXTRA_SONG_NETEASE_ID, song.neteaseId)
            putExtra(MusicService.EXTRA_IS_ONLINE, true)
        }
        ContextCompat.startForegroundService(context, intent)
    }

    private fun sendCommand(context: Context, action: String, song: Song?) {
        val intent = Intent(context, MusicService::class.java).apply {
            this.action = action
            if (song != null) {
                putExtra(MusicService.EXTRA_SONG_PATH, song.filePath)
                putExtra(MusicService.EXTRA_SONG_TITLE, song.title)
                putExtra(MusicService.EXTRA_SONG_ARTIST, song.artist)
                putExtra(MusicService.EXTRA_SONG_ALBUM_ART, song.coverArtPath)
                putExtra(MusicService.EXTRA_SONG_ID, song.id)
                putExtra(MusicService.EXTRA_SONG_NETEASE_ID, song.neteaseId)
            }
        }
        ContextCompat.startForegroundService(context, intent)
    }
}
