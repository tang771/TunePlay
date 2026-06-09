package com.example.tuneplay.ui.home

import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.data.network.OnlineSong

/**
 * 搜索列表项 — 用于 UnifiedSongAdapter 的多视图类型。
 * Local: 本地歌曲，Online: 在线搜索结果，Section: 分组标题（如"本地歌曲"/"在线搜索"）。
 */
sealed class SongItem {
    data class Local(val song: Song) : SongItem()
    data class Online(val song: OnlineSong, val coverUrl: String? = null) : SongItem()
    data class Section(val title: String) : SongItem()
}
