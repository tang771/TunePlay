package com.example.tuneplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 播放历史 — 记录每次播放行为。
 * [playDuration] 由 MusicService.finalizeCurrentPlayback() 在切歌/停止时写入，
 * [source] 标记来源（local / online）。
 */
@Entity(
    tableName = "playback_history",
    foreignKeys = [
        ForeignKey(
            entity = Song::class,
            parentColumns = ["id"],
            childColumns = ["song_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["song_id"]), Index(value = ["played_at"])]
)
data class PlaybackHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "song_id")
    val songId: Long,

    @ColumnInfo(name = "played_at")
    val playedAt: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "play_duration")
    val playDuration: Long = 0,

    @ColumnInfo(name = "source")
    val source: String = "local"
)
