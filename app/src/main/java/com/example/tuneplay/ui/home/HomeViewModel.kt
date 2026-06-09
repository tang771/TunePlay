package com.example.tuneplay.ui.home

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.asLiveData
import androidx.lifecycle.switchMap
import androidx.lifecycle.viewModelScope
import com.example.tuneplay.data.local.AppDatabase
import com.example.tuneplay.R
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.data.network.LastFmApi
import com.example.tuneplay.data.network.NeteaseApi
import com.example.tuneplay.data.network.OnlineSong
import com.example.tuneplay.data.network.tryGetSongUrl
import com.example.tuneplay.data.recommend.GeneratedPlaylist
import com.example.tuneplay.data.repository.MusicRepository
import com.example.tuneplay.data.scanner.MediaStoreScanner
import com.example.tuneplay.player.MusicController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 首页 ViewModel — 管理本地/在线搜索、推荐系统和媒体扫描。
 * 使用 LiveData 驱动 UI，通过 switchMap 实现搜索过滤。
 * [resolvedUrlCache] 缓存已解析的流媒体 URL 避免重复 API 请求。
 */
class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val database = AppDatabase.getInstance(application)
    private val lastFmApiKey = application.getString(R.string.lastfm_api_key)
    private val lastFmApi: LastFmApi? = if (lastFmApiKey.isNotBlank()) LastFmApi.create(lastFmApiKey) else null
    private val repository = MusicRepository(
        database.songDao(),
        database.historyDao(),
        database.playlistDao(),
        database.searchHistoryDao(),
        database.lastFmCacheDao(),
        lastFmApi,
        lastFmApiKey
    )
    private val scanner = MediaStoreScanner(application.contentResolver)
    private val api = NeteaseApi.create()

    private val _isScanning = MutableLiveData(false)
    val isScanning: LiveData<Boolean> = _isScanning

    private val _isEmpty = MutableLiveData(true)
    val isEmpty: LiveData<Boolean> = _isEmpty

    private val _searchQuery = MutableLiveData("")

    // Local songs filtered by search query
    val displaySongs: LiveData<List<Song>> = _searchQuery.switchMap { query ->
        if (query.isNullOrBlank()) {
            repository.getAllSongs().asLiveData()
        } else {
            repository.searchSongs(query).asLiveData()
        }
    }

    // Online search results
    private val _onlineResults = MutableLiveData<List<OnlineSong>>(emptyList())
    val onlineResults: LiveData<List<OnlineSong>> = _onlineResults

    // Combined unified list — single source of truth
    private val _combinedItems = MutableLiveData<List<SongItem>>(emptyList())
    val combinedItems: LiveData<List<SongItem>> = _combinedItems

    private val _isSearchingOnline = MutableLiveData(false)
    val isSearchingOnline: LiveData<Boolean> = _isSearchingOnline

    val recommendedSongs: LiveData<List<Song>> =
        repository.getMostPlayedSongsWithDetails(10).asLiveData()

    val searchHistory: LiveData<List<com.example.tuneplay.data.local.entity.SearchHistory>> =
        repository.getRecentSearches(50).asLiveData()

    private val _likedSongs = MutableLiveData<List<Song>>(emptyList())
    val likedSongs: LiveData<List<Song>> = _likedSongs

    // Personalized recommendations
    private val _guessYouLike = MutableLiveData<List<Song>>(emptyList())
    val guessYouLike: LiveData<List<Song>> = _guessYouLike

    private val _playlistRecommendations = MutableLiveData<List<GeneratedPlaylist>>(emptyList())
    val playlistRecommendations: LiveData<List<GeneratedPlaylist>> = _playlistRecommendations

    private val _discoverMore = MutableLiveData<List<Song>>(emptyList())
    val discoverMore: LiveData<List<Song>> = _discoverMore

    private var onlineSearchJob: Job? = null

    // Cache last-known data so we don't need observers racing in the fragment
    private var lastLocalSongs: List<Song> = emptyList()
    private var lastOnlineSongs: List<OnlineSong> = emptyList()
    private var lastQuery: String = ""
    private var coverUrlCache: Map<Long, String> = emptyMap()

    // Cache resolved song URLs to avoid re-fetching (neteaseId → streamUrl)
    private val resolvedUrlCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

    private val songObserver = androidx.lifecycle.Observer<List<Song>> { songs ->
        lastLocalSongs = songs
        emitCombined()
    }

    init {
        viewModelScope.launch {
            val count = repository.getSongCount()
            _isEmpty.value = count == 0
            repository.deduplicateSearches()
            repository.cleanStaleCache()
            launchLikedSongsObserver()
            refreshRecommendations()
        }
        displaySongs.observeForever(songObserver)
    }

    /** 刷新推荐数据 — 猜你喜欢、推荐歌单、发现更多 */
    fun refreshRecommendations() {
        viewModelScope.launch {
            try {
                val guess = repository.getGuessYouLike(2)
                val playlists = repository.getPlaylistRecommendations()
                val discover = repository.getDiscoverMore(10)
                _guessYouLike.postValue(guess)
                _playlistRecommendations.postValue(playlists)
                _discoverMore.postValue(discover)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to refresh recommendations", e)
            }
        }
    }

    suspend fun saveGeneratedPlaylist(playlist: GeneratedPlaylist): Long {
        return repository.saveGeneratedPlaylist(playlist)
    }

    private fun launchLikedSongsObserver() {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val likedPlaylist = repository.getPlaylistByName(app.getString(R.string.playlist_liked))
            if (likedPlaylist != null) {
                repository.getSongsInPlaylist(likedPlaylist.id).collect { songs ->
                    _likedSongs.postValue(songs)
                }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        displaySongs.removeObserver(songObserver)
    }

    /** 搜索查询变更回调 — 500ms 防抖后触发在线搜索，同时保存搜索历史 */
    fun onSearchQueryChanged(query: String) {
        _searchQuery.value = query
        lastQuery = query
        onlineSearchJob?.cancel()
        if (query.isNotBlank()) {
            onlineSearchJob = viewModelScope.launch {
                delay(500)
                searchOnline(query)
                // Save to search history — trim + deduplicate
                val trimmed = query.trim()
                repository.deleteSearchQuery(trimmed)
                repository.saveSearchQuery(trimmed)
                repository.deduplicateSearches()
                repository.deleteOldSearches(50)
            }
        } else {
            _onlineResults.value = emptyList()
            lastOnlineSongs = emptyList()
            emitCombined()
        }
    }

    private suspend fun searchOnline(query: String) {
        _isSearchingOnline.value = true
        try {
            val response = withContext(Dispatchers.IO) {
                api.search(query, limit = 20)
            }
            if (response.code == 200) {
                val songs = response.result?.songs ?: emptyList()
                lastOnlineSongs = songs
                _onlineResults.postValue(songs)
                // Batch-fetch song details to get real album cover picUrl
                fetchCoverUrls(songs)
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Online search failed: $query", e)
        } finally {
            _isSearchingOnline.postValue(false)
            emitCombined()
        }
    }

    private suspend fun fetchCoverUrls(songs: List<OnlineSong>) {
        if (songs.isEmpty()) return
        val ids = songs.joinToString(",") { it.id.toString() }
        try {
            val detailResponse = withContext(Dispatchers.IO) {
                api.getSongDetail(ids)
            }
            if (detailResponse.code == 200) {
                val map = mutableMapOf<Long, String>()
                detailResponse.songs?.forEach { detail ->
                    detail.album?.picUrl?.let { picUrl ->
                        map[detail.id] = picUrl
                    }
                }
                coverUrlCache = map
                // Populate resolved cover cache so player/mini-player get the real cover
                map.forEach { (songId, picUrl) ->
                    OnlineSong.setResolvedCover(songId, picUrl)
                }
                Log.d("HomeViewModel", "Fetched ${map.size} cover URLs from song/detail")
            }
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to fetch cover URLs", e)
        }
    }

    private fun emitCombined() {
        val items = mutableListOf<SongItem>()
        for (s in lastLocalSongs) items.add(SongItem.Local(s))
        if (lastQuery.isNotBlank()) {
            items.add(SongItem.Section("在线搜索"))
            for (s in lastOnlineSongs) {
                items.add(SongItem.Online(s, coverUrl = coverUrlCache[s.id]))
            }
        }
        _combinedItems.postValue(items)
    }

    /** 设置扫描状态（供 Fragment 调用） */
    fun setScanning(scanning: Boolean) {
        _isScanning.value = scanning
    }

    /** 扫描 MediaStore 并导入 — 扫描结果为空时不会删除现有数据 */
    fun scanMediaStore(onResult: (Int) -> Unit = {}) {
        viewModelScope.launch {
            _isScanning.value = true
            try {
                val scannedSongs = withContext(Dispatchers.IO) {
                    scanner.scan()
                }
                if (scannedSongs.isEmpty()) {
                    onResult(0)
                    return@launch
                }
                withContext(Dispatchers.IO) {
                    repository.deleteAllSongs()
                    repository.insertSongs(scannedSongs)
                }
                _isEmpty.value = false
                onResult(scannedSongs.size)
            } finally {
                _isScanning.value = false
            }
        }
    }

    /** 插入单首歌曲 — 根据 filePath 去重，更新 isEmpty 状态 */
    suspend fun insertSong(song: Song) {
        withContext(Dispatchers.IO) {
            val existing = repository.getSongByPath(song.filePath)
            if (existing == null) {
                repository.insertSongs(listOf(song))
            }
        }
        val count = repository.getSongCount()
        _isEmpty.postValue(count == 0)
    }

    /** 解析在线歌曲为可播放的 Song 对象 — 优先走缓存，未命中时多级音质回退获取 URL 并 upsert 入库 */
    suspend fun resolveOnlineSong(online: OnlineSong): Song? {
        // 先查缓存，避免重复 API 请求
        resolvedUrlCache[online.id]?.let { cachedUrl ->
            return buildOnlineSong(online, cachedUrl)
        }

        return withContext(Dispatchers.IO) {
            try {
                // Use multi-level fallback to maximize URL availability
                val streamUrl = api.tryGetSongUrl(online.id)
                    ?: return@withContext null

                // Cache for reuse
                resolvedUrlCache[online.id] = streamUrl

                buildOnlineSong(online, streamUrl)
            } catch (e: Exception) {
                Log.e("HomeViewModel", "Failed to resolve online song: ${online.id}", e)
                null
            }
        }
    }

    private suspend fun buildOnlineSong(online: OnlineSong, streamUrl: String): Song {
        var song = Song(
            title = online.name,
            artist = online.artistNames(),
            album = online.album?.name ?: "",
            duration = online.duration,
            filePath = streamUrl,
            coverArtPath = online.coverUrl(),
            neteaseId = online.id
        )
        // DB save is non-critical for playback — don't block or fail
        try {
            song = repository.upsertOnlineSong(song)
        } catch (e: Exception) {
            Log.e("HomeViewModel", "Failed to save online song to DB", e)
        }
        return song
    }

    /** Batch-resolve online songs with rate limiting (sequential, 300ms apart) */
    suspend fun resolveOnlineSongsBatched(songs: List<OnlineSong>): Map<Long, Song?> {
        val results = mutableMapOf<Long, Song?>()
        for ((i, song) in songs.withIndex()) {
            if (i > 0) delay(300) // rate limit: 300ms between requests
            results[song.id] = resolveOnlineSong(song)
        }
        return results
    }

    /** Resolve remaining songs in background (survives fragment navigation) */
    fun resolveAndAppendToPlaylist(songs: List<OnlineSong>) {
        viewModelScope.launch {
            val resolvedMap = resolveOnlineSongsBatched(songs)
            for (song in songs) {
                resolvedMap[song.id]?.let { MusicController.appendToPlaylist(it) }
            }
        }
    }

    /** 重新解析在线歌曲的流媒体 URL（用于 DB 缓存的 URL 已过期的情况）。本地歌曲原样返回。 */
    suspend fun refreshSongUrl(song: Song): Song {
        if (song.neteaseId <= 0) return song
        val freshUrl = withContext(Dispatchers.IO) {
            api.tryGetSongUrl(song.neteaseId)
        }
        return if (freshUrl != null) song.copy(filePath = freshUrl) else song
    }

    suspend fun fetchOnlineLyrics(songId: Long): String? {
        return withContext(Dispatchers.IO) {
            try {
                api.getLyric(songId).lrc?.lyric
            } catch (_: Exception) { null }
        }
    }

    fun deleteSearchHistory(query: String) {
        viewModelScope.launch {
            repository.deleteSearchQuery(query)
        }
    }

    fun clearSearchHistory() {
        viewModelScope.launch {
            repository.clearAllSearchHistory()
        }
    }
}
