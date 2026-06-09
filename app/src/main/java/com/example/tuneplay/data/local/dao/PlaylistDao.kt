package com.example.tuneplay.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tuneplay.data.local.entity.Playlist
import com.example.tuneplay.data.local.entity.PlaylistSong
import com.example.tuneplay.data.local.entity.Song
import kotlinx.coroutines.flow.Flow

/**
 * 歌单 DAO — 歌单和歌单-歌曲关联的 CRUD。
 * 联表查询返回 Song 对象；删除歌单和歌曲时通过外键 CASCADE 自动清理关联。
 */
@Dao
interface PlaylistDao {

    // ===== Playlist CRUD =====

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertPlaylist(playlist: Playlist): Long

    @Query("SELECT * FROM playlists ORDER BY sort_order ASC, created_at ASC")
    fun getAllPlaylists(): Flow<List<Playlist>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    suspend fun getPlaylistById(playlistId: Long): Playlist?

    @Query("SELECT * FROM playlists WHERE name = :name LIMIT 1")
    suspend fun getPlaylistByName(name: String): Playlist?

    @Update
    suspend fun updatePlaylist(playlist: Playlist)

    @Query("UPDATE playlists SET sort_order = :sortOrder WHERE id = :playlistId")
    suspend fun updateSortOrder(playlistId: Long, sortOrder: Int)

    @Delete
    suspend fun deletePlaylist(playlist: Playlist)

    // ===== PlaylistSong CRUD =====

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun addSongToPlaylist(playlistSong: PlaylistSong): Long

    @Delete
    suspend fun removeSongFromPlaylist(playlistSong: PlaylistSong)

    @Query(
        """
        SELECT s.* FROM songs s
        INNER JOIN playlist_songs ps ON s.id = ps.song_id
        WHERE ps.playlist_id = :playlistId
        ORDER BY ps.sort_order ASC, ps.added_at DESC
        """
    )
    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>>

    @Query(
        """
        SELECT COUNT(*) FROM playlist_songs
        WHERE playlist_id = :playlistId AND song_id = :songId
        """
    )
    suspend fun isSongInPlaylist(playlistId: Long, songId: Long): Boolean

    @Query(
        """
        SELECT * FROM playlist_songs
        WHERE playlist_id = :playlistId AND song_id = :songId
        LIMIT 1
        """
    )
    suspend fun getPlaylistSong(playlistId: Long, songId: Long): PlaylistSong?

    @Query(
        """
        SELECT p.* FROM playlists p
        INNER JOIN playlist_songs ps ON p.id = ps.playlist_id
        WHERE ps.song_id = :songId
        ORDER BY p.sort_order ASC
        """
    )
    fun getPlaylistsContainingSong(songId: Long): Flow<List<Playlist>>

    @Query("SELECT COUNT(*) FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun getSongCountInPlaylist(playlistId: Long): Int

    @Query("DELETE FROM playlist_songs WHERE playlist_id = :playlistId")
    suspend fun clearPlaylist(playlistId: Long)
}
