package com.example.tuneplay.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tuneplay.data.local.entity.LastFmCache

/**
 * Last.fm 缓存 DAO — 缓存第三方 API 响应以减少重复请求。
 * 通过 [deleteStaleCache] 定期清理过期数据。
 */
@Dao
interface LastFmCacheDao {

    @Query("SELECT * FROM lastfm_cache WHERE cache_key = :key")
    suspend fun getCached(key: String): LastFmCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCache(entry: LastFmCache)

    @Query("DELETE FROM lastfm_cache WHERE fetched_at < :before")
    suspend fun deleteStaleCache(before: Long)
}
