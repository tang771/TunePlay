package com.example.tuneplay.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import com.example.tuneplay.data.local.entity.PlaybackHistory
import com.example.tuneplay.data.local.entity.Song
import kotlinx.coroutines.flow.Flow

/** 按歌曲聚合的播放次数投影 */
data class SongPlayCount(
    @ColumnInfo(name = "song_id") val songId: Long,
    @ColumnInfo(name = "play_count") val playCount: Int
)

/**
 * 播放历史 DAO — 记录每次播放行为，为推荐引擎提供数据源。
 * 支持按时间范围、歌曲、艺术家等维度聚合查询。
 * [SongPlayCount] 和 [ArtistPlayCount] 为聚合查询结果的投影类型。
 */
@Dao
interface HistoryDao {

    @Query("SELECT * FROM playback_history ORDER BY played_at DESC")
    fun getAllHistory(): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history ORDER BY played_at DESC LIMIT :limit")
    fun getRecentHistory(limit: Int): Flow<List<PlaybackHistory>>

    @Query("SELECT * FROM playback_history WHERE song_id = :songId ORDER BY played_at DESC")
    fun getHistoryBySong(songId: Long): Flow<List<PlaybackHistory>>

    @Query(
        """
        SELECT * FROM playback_history
        WHERE played_at >= :since
        ORDER BY played_at DESC
        """
    )
    fun getHistorySince(since: Long): Flow<List<PlaybackHistory>>

    @Query(
        """
        SELECT song_id, COUNT(*) AS play_count
        FROM playback_history
        GROUP BY song_id
        ORDER BY play_count DESC
        LIMIT :limit
        """
    )
    fun getMostPlayedSongs(limit: Int = 20): Flow<List<SongPlayCount>>

    @Insert
    suspend fun insertHistory(history: PlaybackHistory): Long

    @Delete
    suspend fun deleteHistory(history: PlaybackHistory)

    @Query("DELETE FROM playback_history WHERE played_at < :before")
    suspend fun deleteHistoryBefore(before: Long)

    @Query("DELETE FROM playback_history")
    suspend fun deleteAllHistory()

    // ---- Recommendation queries ----

    @Query(
        """
        SELECT DISTINCT s.* FROM songs s
        INNER JOIN playback_history ph ON s.id = ph.song_id
        GROUP BY s.id
        ORDER BY COUNT(ph.id) DESC
        LIMIT :limit
        """
    )
    fun getMostPlayedSongsWithDetails(limit: Int = 10): Flow<List<Song>>

    @Query(
        """
        SELECT DISTINCT s.* FROM songs s
        INNER JOIN playback_history ph ON s.id = ph.song_id
        GROUP BY s.id
        ORDER BY MAX(ph.played_at) DESC
        LIMIT :limit
        """
    )
    fun getRecentlyPlayedSongs(limit: Int = 10): Flow<List<Song>>

    @Query("SELECT COUNT(*) FROM playback_history")
    suspend fun getTotalPlayCount(): Int

    /** 通过记录 ID 写入实际播放时长 */
    @Query("UPDATE playback_history SET play_duration = :duration WHERE id = :id")
    suspend fun updatePlayDuration(id: Long, duration: Long)

    /** 通过歌曲 ID + 播放开始时间定位记录并写入时长，由 MusicService.finalizeCurrentPlayback() 调用 */
    @Query("UPDATE playback_history SET play_duration = :duration WHERE song_id = :songId AND played_at = :startTime")
    suspend fun updatePlayDurationByStart(songId: Long, startTime: Long, duration: Long)

    @Query("SELECT COALESCE(SUM(play_duration), 0) FROM playback_history")
    suspend fun getTotalPlayDuration(): Long

    // ---- Recommendation engine queries ----

    @Query("SELECT * FROM playback_history WHERE played_at >= :since")
    suspend fun getHistorySinceList(since: Long): List<PlaybackHistory>

    @Query(
        """
        SELECT s.artist, COUNT(*) AS play_count
        FROM playback_history ph
        INNER JOIN songs s ON ph.song_id = s.id
        GROUP BY s.artist
        ORDER BY play_count DESC
        LIMIT :limit
        """
    )
    suspend fun getTopArtists(limit: Int = 5): List<ArtistPlayCount>

    @Query(
        """
        SELECT s.* FROM songs s
        LEFT JOIN (
            SELECT song_id, MAX(played_at) AS last_played
            FROM playback_history
            GROUP BY song_id
        ) ph ON s.id = ph.song_id
        WHERE s.artist = :artist
          AND (ph.last_played IS NULL OR ph.last_played < :before)
        ORDER BY s.title
        LIMIT :limit
        """
    )
    suspend fun getUnplayedSongsByArtist(artist: String, before: Long, limit: Int = 5): List<Song>

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN (
            SELECT song_id, COUNT(*) AS play_count
            FROM playback_history
            WHERE played_at >= :since
            GROUP BY song_id
        ) ph ON s.id = ph.song_id
        ORDER BY ph.play_count DESC
        LIMIT :limit
        """
    )
    suspend fun getTopSongsSince(since: Long, limit: Int = 20): List<Song>
}

/** 按艺术家聚合的播放次数投影，用于推荐引擎识别偏好艺术家 */
data class ArtistPlayCount(
    @ColumnInfo(name = "artist") val artist: String,
    @ColumnInfo(name = "play_count") val playCount: Int
)
