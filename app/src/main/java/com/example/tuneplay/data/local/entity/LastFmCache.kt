package com.example.tuneplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Last.fm API 响应缓存 — 避免重复请求第三方 API。
 * [cacheKey] 为请求 URL 的哈希或标准化字符串，[responseJson] 为原始 JSON 响应。
 */
@Entity(
    tableName = "lastfm_cache",
    indices = [Index(value = ["cache_key"], unique = true)]
)
data class LastFmCache(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "cache_key")
    val cacheKey: String,

    @ColumnInfo(name = "response_json")
    val responseJson: String,

    @ColumnInfo(name = "fetched_at")
    val fetchedAt: Long = System.currentTimeMillis()
)
