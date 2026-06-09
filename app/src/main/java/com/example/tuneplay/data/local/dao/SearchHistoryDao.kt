package com.example.tuneplay.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.tuneplay.data.local.entity.SearchHistory
import kotlinx.coroutines.flow.Flow

/**
 * 搜索历史 DAO — 记录用户的搜索关键词。
 * [deduplicateSearches] 自动清理重复记录，[deleteOldSearches] 限制保存条数。
 */
@Dao
interface SearchHistoryDao {

    @Query("SELECT * FROM search_history ORDER BY searched_at DESC LIMIT :limit")
    fun getRecentSearches(limit: Int = 10): Flow<List<SearchHistory>>

    @Query("SELECT * FROM search_history ORDER BY searched_at DESC")
    fun getAllSearches(): Flow<List<SearchHistory>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearch(searchHistory: SearchHistory)

    @Query("DELETE FROM search_history WHERE query = :query")
    suspend fun deleteSearch(query: String)

    @Query("DELETE FROM search_history WHERE id NOT IN (SELECT MIN(id) FROM search_history GROUP BY query)")
    suspend fun deduplicateSearches()

    @Query("DELETE FROM search_history WHERE id NOT IN (SELECT id FROM search_history ORDER BY searched_at DESC LIMIT :keep)")
    suspend fun deleteOldSearches(keep: Int)

    @Query("DELETE FROM search_history")
    suspend fun deleteAllSearches()
}
