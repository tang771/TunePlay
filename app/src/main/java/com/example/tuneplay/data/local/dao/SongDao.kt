package com.example.tuneplay.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.example.tuneplay.data.local.entity.Song
import kotlinx.coroutines.flow.Flow

/**
 * 歌曲 DAO — 本地歌曲的增删改查。
 * 支持按标题/艺术家/专辑模糊搜索，按专辑/艺术家分组查询，
 * 以及通过网易云 ID 查找在线歌曲。
 */
@Dao
interface SongDao {

    @Query("SELECT * FROM songs ORDER BY title ASC")
    fun getAllSongs(): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE id = :songId")
    suspend fun getSongById(songId: Long): Song?

    @Query("SELECT * FROM songs WHERE file_path = :filePath LIMIT 1")
    suspend fun getSongByPath(filePath: String): Song?

    @Query(
        """
        SELECT * FROM songs
        WHERE title LIKE '%' || :query || '%'
           OR artist LIKE '%' || :query || '%'
           OR album LIKE '%' || :query || '%'
        ORDER BY title ASC
        """
    )
    fun searchSongs(query: String): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE album = :albumName ORDER BY track_number ASC")
    fun getSongsByAlbum(albumName: String): Flow<List<Song>>

    @Query("SELECT * FROM songs WHERE artist = :artistName ORDER BY album ASC, track_number ASC")
    fun getSongsByArtist(artistName: String): Flow<List<Song>>

    @Query("SELECT DISTINCT album FROM songs ORDER BY album ASC")
    fun getAllAlbums(): Flow<List<String>>

    @Query("SELECT DISTINCT artist FROM songs ORDER BY artist ASC")
    fun getAllArtists(): Flow<List<String>>

    @Query("SELECT * FROM songs ORDER BY date_added DESC LIMIT :limit")
    fun getRecentlyAdded(limit: Int = 50): Flow<List<Song>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(song: Song): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<Song>): List<Long>

    @Update
    suspend fun updateSong(song: Song)

    @Delete
    suspend fun deleteSong(song: Song)

    @Query("DELETE FROM songs")
    suspend fun deleteAllSongs()

    @Query("SELECT COUNT(*) FROM songs")
    suspend fun getSongCount(): Int

    /** 一次性获取全部歌曲列表（非 Flow），用于构建播放队列 */
    @Query("SELECT * FROM songs")
    suspend fun getAllSongsList(): List<Song>

    /** 通过网易云 ID 查找已缓存的在线歌曲 */
    @Query("SELECT * FROM songs WHERE netease_id = :neteaseId AND netease_id != 0 LIMIT 1")
    suspend fun getSongByNeteaseId(neteaseId: Long): Song?

    @Query("SELECT * FROM songs WHERE title = :title AND artist = :artist AND (netease_id = 0 OR netease_id IS NULL) LIMIT 1")
    suspend fun getLocalSongByTitleAndArtist(title: String, artist: String): Song?

    @Query("UPDATE songs SET file_path = :newPath WHERE id = :songId")
    suspend fun updateFilePath(songId: Long, newPath: String)

    /** 获取全部本地歌曲（neteaseId == 0 或无 neteaseId），按标题排序 */
    @Query("SELECT * FROM songs WHERE netease_id = 0 OR netease_id IS NULL ORDER BY title ASC")
    fun getLocalSongs(): Flow<List<Song>>

    /** 本地歌曲总数，用于个人主页统计展示 */
    @Query("SELECT COUNT(*) FROM songs WHERE netease_id = 0 OR netease_id IS NULL")
    suspend fun getLocalSongCount(): Int
}
