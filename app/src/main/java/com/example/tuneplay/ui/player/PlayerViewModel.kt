package com.example.tuneplay.ui.player

import androidx.lifecycle.ViewModel
import com.example.tuneplay.player.MusicController
import kotlinx.coroutines.flow.StateFlow

/**
 * 播放器 ViewModel — 薄封装层，直接代理 MusicController 的 StateFlow。
 * 所有播放状态都由 MusicController 全局单例管理。
 */
class PlayerViewModel : ViewModel() {

    val currentSong = MusicController.currentSong
    val isPlaying: StateFlow<Boolean> = MusicController.isPlaying
    val position: StateFlow<Long> = MusicController.position
    val duration: StateFlow<Long> = MusicController.duration
}
