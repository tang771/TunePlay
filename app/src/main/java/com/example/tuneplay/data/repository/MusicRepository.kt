package com.example.tuneplay.data.repository

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
import com.example.tuneplay.data.network.LastFmApi
import com.example.tuneplay.data.recommend.GeneratedPlaylist
import com.example.tuneplay.data.recommend.RecommendationEngine
import kotlinx.coroutines.flow.Flow

/**
 * 音乐仓库 — 音乐数据访问的统一入口。
 * 封装所有 DAO 操作，协调本地数据库、推荐引擎和 Last.fm API。
 * 可选参数 (searchHistoryDao/lastFmCacheDao/lastFmApi) 允许渐进式初始化。
 */
class MusicRepository(
    private val songDao: SongDao,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val searchHistoryDao: SearchHistoryDao? = null,
    private val lastFmCacheDao: LastFmCacheDao? = null,
    private val lastFmApi: LastFmApi? = null,
    private val lastFmApiKey: String = ""
) {
    private val recommendationEngine: RecommendationEngine? by lazy {
        if (lastFmCacheDao == null) return@lazy null
        RecommendationEngine(
            songDao = songDao,
            historyDao = historyDao,
            playlistDao = playlistDao,
            lastFmApi = lastFmApi,
            lastFmCacheDao = lastFmCacheDao,
            apiKey = lastFmApiKey
        )
    }

    private fun requireEngine(): RecommendationEngine? = recommendationEngine

    // ---- Song operations ----

    fun getAllSongs(): Flow<List<Song>> = songDao.getAllSongs()

    suspend fun getSongById(id: Long): Song? = songDao.getSongById(id)

    suspend fun getSongByPath(path: String): Song? = songDao.getSongByPath(path)

    fun searchSongs(query: String): Flow<List<Song>> = songDao.searchSongs(query)

    fun getSongsByAlbum(album: String): Flow<List<Song>> = songDao.getSongsByAlbum(album)

    fun getSongsByArtist(artist: String): Flow<List<Song>> = songDao.getSongsByArtist(artist)

    fun getAllAlbums(): Flow<List<String>> = songDao.getAllAlbums()

    fun getAllArtists(): Flow<List<String>> = songDao.getAllArtists()

    fun getRecentlyAdded(limit: Int = 50): Flow<List<Song>> = songDao.getRecentlyAdded(limit)

    suspend fun insertSongs(songs: List<Song>): List<Long> = songDao.insertSongs(songs)

    suspend fun updateSong(song: Song) = songDao.updateSong(song)

    suspend fun deleteSong(song: Song) = songDao.deleteSong(song)

    suspend fun deleteAllSongs() = songDao.deleteAllSongs()

    suspend fun getSongCount(): Int = songDao.getSongCount()

    /** 获取全部本地歌曲（非网易云来源），用于本地音乐列表页 */
    fun getLocalSongs(): Flow<List<Song>> = songDao.getLocalSongs()

    /** 本地歌曲总数，用于个人主页本地音乐入口卡片展示 */
    suspend fun getLocalSongCount(): Int = songDao.getLocalSongCount()

    // ---- History operations ----

    fun getAllHistory(): Flow<List<PlaybackHistory>> = historyDao.getAllHistory()

    fun getRecentHistory(limit: Int = 20): Flow<List<PlaybackHistory>> =
        historyDao.getRecentHistory(limit)

    fun getHistoryBySong(songId: Long): Flow<List<PlaybackHistory>> =
        historyDao.getHistoryBySong(songId)

    fun getMostPlayedSongs(limit: Int = 20) = historyDao.getMostPlayedSongs(limit)

    suspend fun clearOldHistory(beforeTimestamp: Long) =
        historyDao.deleteHistoryBefore(beforeTimestamp)

    suspend fun clearAllHistory() = historyDao.deleteAllHistory()

    fun getMostPlayedSongsWithDetails(limit: Int = 10): Flow<List<Song>> =
        historyDao.getMostPlayedSongsWithDetails(limit)

    fun getRecentlyPlayedSongs(limit: Int = 10): Flow<List<Song>> =
        historyDao.getRecentlyPlayedSongs(limit)

    suspend fun getTotalPlayCount(): Int = historyDao.getTotalPlayCount()

    suspend fun getTotalPlayDuration(): Long = historyDao.getTotalPlayDuration()

    suspend fun updatePlayDuration(historyId: Long, duration: Long) {
        historyDao.updatePlayDuration(historyId, duration)
    }

    // ---- Playlist operations ----

    fun getAllPlaylists(): Flow<List<Playlist>> = playlistDao.getAllPlaylists()

    suspend fun createPlaylist(name: String): Long {
        return playlistDao.insertPlaylist(Playlist(name = name))
    }

    suspend fun deletePlaylist(playlist: Playlist) = playlistDao.deletePlaylist(playlist)

    suspend fun updatePlaylistSortOrder(playlistId: Long, sortOrder: Int) {
        playlistDao.updateSortOrder(playlistId, sortOrder)
    }

    suspend fun addSongToPlaylist(playlistId: Long, songId: Long): Long {
        return playlistDao.addSongToPlaylist(
            PlaylistSong(playlistId = playlistId, songId = songId)
        )
    }

    suspend fun removeSongFromPlaylist(playlistId: Long, songId: Long) {
        val entry = playlistDao.getPlaylistSong(playlistId, songId)
        entry?.let { playlistDao.removeSongFromPlaylist(it) }
    }

    suspend fun isSongInPlaylist(playlistId: Long, songId: Long): Boolean {
        return playlistDao.isSongInPlaylist(playlistId, songId)
    }

    /** 切换歌曲在歌单中的状态 — 存在则移除，不存在则添加。返回操作后的状态（true=已收藏） */
    suspend fun toggleSongInPlaylist(playlistId: Long, songId: Long): Boolean {
        return if (playlistDao.isSongInPlaylist(playlistId, songId)) {
            removeSongFromPlaylist(playlistId, songId)
            false
        } else {
            addSongToPlaylist(playlistId, songId)
            true
        }
    }

    fun getSongsInPlaylist(playlistId: Long): Flow<List<Song>> {
        return playlistDao.getSongsInPlaylist(playlistId)
    }

    fun getPlaylistsContainingSong(songId: Long): Flow<List<Playlist>> {
        return playlistDao.getPlaylistsContainingSong(songId)
    }

    suspend fun getPlaylistByName(name: String): Playlist? {
        return playlistDao.getPlaylistByName(name)
    }

    suspend fun getSongCountInPlaylist(playlistId: Long): Int {
        return playlistDao.getSongCountInPlaylist(playlistId)
    }

    // ---- Online song operations ----

    suspend fun getSongByNeteaseId(neteaseId: Long): Song? {
        return songDao.getSongByNeteaseId(neteaseId)
    }

    /**
     * 智能插入/更新在线歌曲 — 先按网易云 ID 匹配，再按标题+艺术家匹配本地歌曲。
     * 匹配到本地歌曲时保留其 ID 和收藏状态，只更新在线播放信息。
     * @return 最终存入数据库的 Song 对象（可能带有新的或已有的 ID）
     */
    suspend fun upsertOnlineSong(song: Song): Song {
        // 1. 通过网易云 ID 匹配已有在线歌曲
        val existing = if (song.neteaseId > 0) {
            songDao.getSongByNeteaseId(song.neteaseId)
        } else null
        if (existing != null) {
            songDao.updateFilePath(existing.id, song.filePath)
            return existing.copy(filePath = song.filePath)
        }
        // 2. Fallback: match local song by title + artist to preserve like status
        val localMatch = songDao.getLocalSongByTitleAndArtist(song.title, song.artist)
        if (localMatch != null) {
            val updated = localMatch.copy(
                filePath = song.filePath,
                coverArtPath = song.coverArtPath.ifBlank { localMatch.coverArtPath },
                neteaseId = song.neteaseId,
                duration = if (song.duration > 0) song.duration else localMatch.duration
            )
            songDao.updateSong(updated)
            return updated
        }
        // 3. No match — insert new song
        val id = songDao.insertSong(song)
        return song.copy(id = id)
    }

    // ---- Search history operations ----

    fun getRecentSearches(limit: Int = 10): Flow<List<SearchHistory>> {
        return searchHistoryDao?.getRecentSearches(limit) ?: kotlinx.coroutines.flow.flowOf(emptyList())
    }

    suspend fun saveSearchQuery(query: String) {
        searchHistoryDao?.insertSearch(SearchHistory(query = query))
    }

    suspend fun deleteSearchQuery(query: String) {
        searchHistoryDao?.deleteSearch(query)
    }

    suspend fun clearAllSearchHistory() {
        searchHistoryDao?.deleteAllSearches()
    }

    suspend fun deleteOldSearches(keep: Int) {
        searchHistoryDao?.deleteOldSearches(keep)
    }

    suspend fun deduplicateSearches() {
        searchHistoryDao?.deduplicateSearches()
    }

    // ---- Recommendation operations ----

    suspend fun getGuessYouLike(limit: Int = 2): List<Song> {
        return requireEngine()?.getGuessYouLike(limit) ?: emptyList()
    }

    suspend fun getPlaylistRecommendations(): List<GeneratedPlaylist> {
        return requireEngine()?.getPlaylistRecommendations() ?: emptyList()
    }

    suspend fun getDiscoverMore(limit: Int = 8): List<Song> {
        return requireEngine()?.getDiscoverMore(limit) ?: emptyList()
    }

    /** 清除推荐引擎缓存 — 在歌单或播放历史变更后调用 */
    fun invalidateRecommendations() {
        try {
            requireEngine()?.invalidateCache()
        } catch (_: Exception) { }
    }

    /** 将引擎生成的推荐歌单持久化到数据库，同名歌单会先删后建 */
    suspend fun saveGeneratedPlaylist(playlist: GeneratedPlaylist): Long {
        // Upsert: delete existing playlist with same name, then re-create
        val existing = playlistDao.getPlaylistByName(playlist.name)
        if (existing != null) {
            playlistDao.clearPlaylist(existing.id)
            playlistDao.deletePlaylist(existing)
        }
        val newId = playlistDao.insertPlaylist(
            Playlist(name = playlist.name, createdAt = System.currentTimeMillis())
        )
        for ((i, song) in playlist.songs.withIndex()) {
            playlistDao.addSongToPlaylist(
                PlaylistSong(playlistId = newId, songId = song.id, sortOrder = i)
            )
        }
        return newId
    }

    // ---- Cache maintenance 缓存维护 ----

    /** 清理超过 7 天的 Last.fm 缓存数据 */
    suspend fun cleanStaleCache() {
        lastFmCacheDao?.deleteStaleCache(
            System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        )
    }
}
