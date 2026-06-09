package com.example.tuneplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 搜索历史 — 记录用户的搜索关键词，用于搜索建议和快速回查。
 */
@Entity(
    tableName = "search_history",
    indices = [Index(value = ["searched_at"])]
)
data class SearchHistory(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "query")
    val query: String,

    @ColumnInfo(name = "searched_at")
    val searchedAt: Long = System.currentTimeMillis()
)
