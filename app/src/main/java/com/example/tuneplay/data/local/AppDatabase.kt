package com.example.tuneplay.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.example.tuneplay.data.local.dao.HistoryDao
import com.example.tuneplay.data.local.dao.LastFmCacheDao
import com.example.tuneplay.data.local.dao.PlaylistDao
import com.example.tuneplay.data.local.dao.SearchHistoryDao
import com.example.tuneplay.data.local.dao.SongDao
import com.example.tuneplay.data.local.entity.LastFmCache
import com.example.tuneplay.data.local.entity.PlaybackHistory
import com.example.tuneplay.data.local.entity.Playlist
import com.example.tuneplay.data.local.entity.PlaylistSong
import com.example.tuneplay.data.local.entity.SearchHistory
import com.example.tuneplay.data.local.entity.Song

/**
 * Room 数据库 — 单例模式，包含所有实体和 DAO。
 * 当前版本 6，迁移链: 1→2(歌单) → 3(lrc_path) → 4(media_store_id) → 5(netease_id+搜索历史) → 6(lastfm_cache)
 */
@Database(
    entities = [Song::class, PlaybackHistory::class, Playlist::class, PlaylistSong::class, SearchHistory::class, LastFmCache::class],
    version = 6,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {

    abstract fun songDao(): SongDao
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun searchHistoryDao(): SearchHistoryDao
    abstract fun lastFmCacheDao(): LastFmCacheDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        /** v5 → v6: 新增 lastfm_cache 缓存表 */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS lastfm_cache (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        cache_key TEXT NOT NULL,
                        response_json TEXT NOT NULL,
                        fetched_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_lastfm_cache_cache_key
                    ON lastfm_cache (cache_key)
                    """.trimIndent()
                )
            }
        }

        /** v4 → v5: 新增 netease_id 字段 + 搜索历史表 */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN netease_id INTEGER NOT NULL DEFAULT 0")
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS search_history (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        query TEXT NOT NULL,
                        searched_at INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_search_history_searched_at
                    ON search_history (searched_at)
                    """.trimIndent()
                )
            }
        }

        /** v3 → v4: 新增 media_store_id 字段用于关联 MediaStore */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN media_store_id INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** v2 → v3: 新增 lrc_path 歌词文件路径字段 */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE songs ADD COLUMN lrc_path TEXT")
            }
        }

        /** v1 → v2: 新增歌单和歌单-歌曲关联表，同时创建默认"我喜欢"歌单 */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlists (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        name TEXT NOT NULL,
                        created_at INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_playlists_name
                    ON playlists (name)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS playlist_songs (
                        id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        playlist_id INTEGER NOT NULL,
                        song_id INTEGER NOT NULL,
                        added_at INTEGER NOT NULL DEFAULT 0,
                        sort_order INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY (playlist_id) REFERENCES playlists(id) ON DELETE CASCADE,
                        FOREIGN KEY (song_id) REFERENCES songs(id) ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_playlist_songs_playlist_id
                    ON playlist_songs (playlist_id)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE INDEX IF NOT EXISTS index_playlist_songs_song_id
                    ON playlist_songs (song_id)
                    """.trimIndent()
                )
                db.execSQL(
                    """
                    CREATE UNIQUE INDEX IF NOT EXISTS index_playlist_songs_playlist_song
                    ON playlist_songs (playlist_id, song_id)
                    """.trimIndent()
                )
                val now = System.currentTimeMillis()
                db.execSQL(
                    "INSERT OR IGNORE INTO playlists (name, created_at, sort_order) VALUES ('我喜欢', $now, 0)"
                )
            }
        }

        fun getInstance(context: Context): AppDatabase {
            // 双重检查锁定单例
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "tuneplay_database"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
