package com.example.tuneplay.data.recommend

import com.example.tuneplay.data.local.dao.HistoryDao
import com.example.tuneplay.data.local.dao.LastFmCacheDao
import com.example.tuneplay.data.local.dao.PlaylistDao
import com.example.tuneplay.data.local.dao.SongDao
import com.example.tuneplay.data.local.entity.LastFmCache
import com.example.tuneplay.data.local.entity.Song
import com.example.tuneplay.data.network.LastFmApi
import com.google.gson.Gson
import kotlinx.coroutines.flow.firstOrNull

/** 带分数的歌曲，用于推荐排序 */
data class ScoredSong(val song: Song, val score: Double)

/** 引擎生成的推荐歌单 */
data class GeneratedPlaylist(
    val name: String,
    val songs: List<Song>,
    val source: PlaylistSource
)

/** 歌单来源类型 */
enum class PlaylistSource { TOP_ARTIST, RECENT_FAVORITES, LASTFM_TAG, REDISCOVER, RANDOM }

/**
 * 推荐引擎 — 基于播放历史和收藏数据生成个性化推荐。
 * 策略：新艺术家发现 > 已知艺术家未听歌曲 > 曲库填充。
 * 支持 Last.fm 集成获取相似艺术家和曲目（需配置 API key）。
 */
class RecommendationEngine(
    private val songDao: SongDao,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val lastFmApi: LastFmApi?,
    private val lastFmCacheDao: LastFmCacheDao,
    private val apiKey: String
) {
    companion object {
        private const val CACHE_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }

    private val gson = Gson()

    @Volatile
    private var cachedLikedSongIds: Set<Long>? = null

    @Volatile
    private var cachedAllSongs: List<Song>? = null

    /** 清除内部缓存 — 在数据库变更后调用以确保推荐数据为最新状态 */
    fun invalidateCache() {
        cachedLikedSongIds = null
        cachedAllSongs = null
        cachedExcludeTitles = null
    }

    // ═══════════════════════════════════════════════════════════════
    // Shared helpers
    // ═══════════════════════════════════════════════════════════════

    /** 获取全量歌曲列表（带缓存），减少重复数据库查询 */
    private suspend fun getAllSongs(): List<Song> {
        cachedAllSongs?.let { return it }
        val songs = songDao.getAllSongsList()
        cachedAllSongs = songs
        return songs
    }

    /** 获取"我喜欢"歌单中的歌曲 ID 集合（带缓存） */
    private suspend fun getLikedSongIds(): Set<Long> {
        cachedLikedSongIds?.let { return it }
        val playlist = playlistDao.getPlaylistByName("我喜欢") ?: return emptySet()
        val songs = playlistDao.getSongsInPlaylist(playlist.id).firstOrNull() ?: emptyList()
        val ids = songs.map { it.id }.toSet()
        cachedLikedSongIds = ids
        return ids
    }

    /** Exclude all songs user has ever played or liked — recommendations should be fresh discoveries */
    private suspend fun getExcludeIds(): Set<Long> {
        val likedIds = getLikedSongIds()
        val allPlayedIds = try {
            historyDao.getTopSongsSince(0L, 2000).map { it.id }.toSet()
        } catch (_: Exception) { emptySet() }
        return allPlayedIds + likedIds
    }

    /** Normalize title for fuzzy comparison: lowercase, remove brackets/parens content, collapse whitespace */
    private fun normalizeTitle(title: String): String {
        return title.lowercase()
            .replace(Regex("""[（(][^）)]*[）)]"""), "")  // Remove (content) and （content）
            .replace(Regex("""\[.*?]"""), "")               // Remove [content]
            .replace(Regex("""\s+"""), " ")                 // Collapse whitespace
            .trim()
    }

    /** Known song titles (normalized) to avoid recommending covers/remixes of played songs */
    @Volatile
    private var cachedExcludeTitles: Set<String>? = null

    private suspend fun getExcludeTitles(): Set<String> {
        cachedExcludeTitles?.let { return it }
        val titles = mutableSetOf<String>()
        try {
            val playedSongs = historyDao.getTopSongsSince(0L, 2000)
            titles += playedSongs.map { normalizeTitle(it.title) }
        } catch (_: Exception) { }
        try {
            val allSongs = getAllSongs()
            val likedIds = getLikedSongIds()
            titles += allSongs.filter { it.id in likedIds }.map { normalizeTitle(it.title) }
        } catch (_: Exception) { }
        cachedExcludeTitles = titles
        return titles
    }

    private fun Song.isExcluded(excludeIds: Set<Long>, excludeTitles: Set<String>): Boolean {
        return id in excludeIds || normalizeTitle(title) in excludeTitles
    }

    /** Top artist names from playback history */
    private suspend fun getTopArtistNames(count: Int = 10): List<String> {
        return try {
            historyDao.getTopArtists(count).map { it.artist }.filter { it.isNotBlank() }
        } catch (_: Exception) { emptyList() }
    }

    /** Liked artist names (artists of liked songs) */
    private suspend fun getLikedArtistNames(): Set<String> {
        val likedIds = getLikedSongIds()
        if (likedIds.isEmpty()) return emptySet()
        val allSongs = getAllSongs()
        return allSongs.filter { it.id in likedIds }
            .map { it.artist }.filter { it.isNotBlank() }.toSet()
    }

    // ═══════════════════════════════════════════════════════════════
    // "猜你喜欢" — 从曲库发现与用户口味匹配的未听歌曲
    // ═══════════════════════════════════════════════════════════════

    suspend fun getGuessYouLike(limit: Int = 2): List<Song> {
        val allSongs = getAllSongs()
        if (allSongs.isEmpty()) return emptyList()

        val excludeIds = getExcludeIds()
        val excludeTitles = getExcludeTitles()
        val knownArtists = getTopArtistNames(30).toSet() + getLikedArtistNames()
        val candidates = mutableListOf<Song>()
        val usedArtists = mutableSetOf<String>()
        val usedTitles = mutableSetOf<String>()

        fun tryAdd(song: Song): Boolean {
            val artistKey = song.artist.lowercase()
            val titleKey = normalizeTitle(song.title)
            if (artistKey in usedArtists) return false
            if (titleKey in usedTitles) return false
            if (song.isExcluded(excludeIds, excludeTitles)) return false
            if (candidates.any { it.id == song.id }) return false
            candidates.add(song)
            usedArtists.add(artistKey)
            usedTitles.add(titleKey)
            return true
        }

        // Priority 1: NEW artists (user has never listened to) — true discovery
        val newArtistSongs = allSongs.filter {
            it.artist.isNotBlank() &&
            it.artist !in knownArtists &&
            !it.isExcluded(excludeIds, excludeTitles)
        }.shuffled()
        for (s in newArtistSongs) {
            if (candidates.size >= limit) break
            tryAdd(s)
        }

        // Priority 2: known artists but completely different songs
        if (candidates.size < limit) {
            for (artist in knownArtists.shuffled()) {
                if (candidates.size >= limit) break
                if (artist.lowercase() in usedArtists) continue
                val songs = allSongs.filter {
                    it.artist.equals(artist, ignoreCase = true) &&
                    !it.isExcluded(excludeIds, excludeTitles)
                }.shuffled()
                for (s in songs) {
                    if (candidates.size >= limit) break
                    tryAdd(s)
                }
            }
        }

        // Priority 3: fill with any unheard song (unique artist + unique title)
        if (candidates.size < limit) {
            val unheard = allSongs.filter {
                !it.isExcluded(excludeIds, excludeTitles) &&
                it.id !in candidates.map { s -> s.id }
            }.shuffled()
            for (s in unheard) {
                if (candidates.size >= limit) break
                tryAdd(s)
            }
        }

        return candidates.take(limit)
    }

    // ═══════════════════════════════════════════════════════════════
    // "发现更多" — 探索未听过的歌：已知艺人新歌 + 全新艺人
    // ═══════════════════════════════════════════════════════════════

    suspend fun getDiscoverMore(limit: Int = 7): List<Song> {
        val allSongs = getAllSongs()
        if (allSongs.isEmpty()) return emptyList()

        val excludeIds = getExcludeIds()
        val excludeTitles = getExcludeTitles()
        val knownArtists = getTopArtistNames(30).toSet() + getLikedArtistNames()
        val discovered = mutableListOf<Song>()
        val usedArtists = mutableSetOf<String>()
        val usedTitles = mutableSetOf<String>()

        fun tryAdd(song: Song): Boolean {
            val artistKey = song.artist.lowercase()
            val titleKey = normalizeTitle(song.title)
            if (artistKey in usedArtists) return false
            if (titleKey in usedTitles) return false
            if (song.isExcluded(excludeIds, excludeTitles)) return false
            if (discovered.any { it.id == song.id }) return false
            discovered.add(song)
            usedArtists.add(artistKey)
            usedTitles.add(titleKey)
            return true
        }

        // Strategy 1: NEW artists first — true exploration
        val newArtistSongs = allSongs.filter {
            it.artist.isNotBlank() &&
            it.artist !in knownArtists &&
            !it.isExcluded(excludeIds, excludeTitles)
        }.shuffled()
        for (s in newArtistSongs) {
            if (discovered.size >= limit) break
            tryAdd(s)
        }

        // Strategy 2: known artists but unique titles
        if (discovered.size < limit) {
            for (artist in knownArtists.shuffled()) {
                if (discovered.size >= limit) break
                val songs = allSongs.filter {
                    it.artist.equals(artist, ignoreCase = true) &&
                    !it.isExcluded(excludeIds, excludeTitles)
                }.shuffled()
                for (s in songs) {
                    if (discovered.size >= limit) break
                    tryAdd(s)
                }
            }
        }

        // Strategy 3: fill remaining with any unheard song
        if (discovered.size < limit) {
            val unheard = allSongs.filter {
                !it.isExcluded(excludeIds, excludeTitles) &&
                it.id !in discovered.map { s -> s.id }
            }.shuffled()
            for (s in unheard) {
                if (discovered.size >= limit) break
                tryAdd(s)
            }
        }

        return discovered.take(limit)
    }

    // ═══════════════════════════════════════════════════════════════
    // "推荐歌单" — 动态生成主题歌单
    // ═══════════════════════════════════════════════════════════════

    suspend fun getPlaylistRecommendations(): List<GeneratedPlaylist> {
        val allSongs = getAllSongs()
        if (allSongs.isEmpty()) return emptyList()

        // Categorize songs by artist language / style
        val electronic = mutableListOf<Song>()
        val korean = mutableListOf<Song>()
        val japanese = mutableListOf<Song>()
        val chinese = mutableListOf<Song>()
        val western = mutableListOf<Song>()

        for (song in allSongs) {
            val artist = song.artist
            val title = song.title

            // Electronic keywords → pull into electronic playlist
            if (isElectronic(artist, title)) {
                electronic.add(song)
                continue
            }

            // Language-based classification
            when {
                artist.containsHangul() || title.containsHangul() -> korean.add(song)
                artist.containsJapanese() || title.containsJapanese() -> japanese.add(song)
                artist.containsCJK() || title.containsCJK() -> chinese.add(song)
                else -> western.add(song)
            }
        }

        // If categories are empty, fill from western pool (largest)
        fun fillFrom(songs: MutableList<Song>, source: MutableList<Song>, count: Int) {
            val taken = source.shuffled().take(count)
            songs.addAll(taken)
            source.removeAll(taken.toSet())
        }

        // Ensure each category has at least 5 songs
        if (electronic.size < 5) fillFrom(electronic, western, 5 - electronic.size)
        if (korean.size < 5) fillFrom(korean, western, 5 - korean.size)
        if (japanese.size < 5) fillFrom(japanese, western, 5 - japanese.size)
        if (chinese.size < 5) fillFrom(chinese, western, 5 - chinese.size)

        val playlists = mutableListOf<GeneratedPlaylist>()

        // Build playlists — each with 20 songs shuffled, skip if < 3 songs
        data class Category(val name: String, val songs: List<Song>, val source: PlaylistSource)
        val categories = listOf(
            Category("欧美经典", western.shuffled(), PlaylistSource.RANDOM),
            Category("电子未来", electronic.shuffled(), PlaylistSource.RANDOM),
            Category("韩流来袭", korean.shuffled(), PlaylistSource.RANDOM),
            Category("日系清新", japanese.shuffled(), PlaylistSource.RANDOM),
            Category("华语潮流", chinese.shuffled(), PlaylistSource.RANDOM)
        )

        for (cat in categories) {
            if (cat.songs.size >= 3) {
                playlists.add(
                    GeneratedPlaylist(cat.name, cat.songs.take(20), cat.source)
                )
            }
        }

        return playlists.take(5)
    }

    // ── Character classification helpers ──

    private fun String.containsHangul(): Boolean =
        any { it in '가'..'힯' || it in 'ᄀ'..'ᇿ' }

    private fun String.containsJapanese(): Boolean =
        any { it in '぀'..'ゟ' || it in '゠'..'ヿ' }

    private fun String.containsCJK(): Boolean =
        any { it in '一'..'鿿' || it in '㐀'..'䶿' }

    private fun isElectronic(artist: String, title: String): Boolean {
        val keywords = listOf(
            "electronic", "electronica", "edm", "dance", "house", "techno",
            "trance", "dubstep", "dnb", "drum and bass", "ambient", "synth",
            "electro", "rave", "club", "bass", "beat", "disco", "funk",
            "remix", "dj", "mix", "drop", "电子", "电音", "舞曲"
        )
        val combined = "$artist $title".lowercase()
        return keywords.any { combined.contains(it) }
    }

    // ═══════════════════════════════════════════════════════════════
    // Last.fm integration (cached, optional)
    // ═══════════════════════════════════════════════════════════════

    private val apiAvailable: Boolean
        get() = lastFmApi != null && apiKey.isNotBlank()

    /** 带缓存的 API 调用 — 优先返回 LocalDB 缓存，缓存未命中或过期时重新请求 */
    private suspend fun cachedApiCall(
        cacheKey: String,
        fetcher: suspend () -> String
    ): String? {
        val cached = lastFmCacheDao.getCached(cacheKey)
        if (cached != null) {
            val age = System.currentTimeMillis() - cached.fetchedAt
            if (age < CACHE_TTL_MS) return cached.responseJson
        }
        if (!apiAvailable) return cached?.responseJson
        return try {
            val json = fetcher()
            lastFmCacheDao.insertCache(
                LastFmCache(cacheKey = cacheKey, responseJson = json)
            )
            json
        } catch (_: Exception) {
            cached?.responseJson
        }
    }

    suspend fun getSimilarArtists(artistName: String): List<String> {
        if (artistName.isBlank() || !apiAvailable) return emptyList()
        val cacheKey = "similar_artist:$artistName"
        val json = cachedApiCall(cacheKey) {
            val response = lastFmApi!!.getSimilarArtists(artist = artistName, apiKey = apiKey)
            gson.toJson(response)
        } ?: return emptyList()
        return try {
            val response = gson.fromJson(
                json, com.example.tuneplay.data.network.LastFmArtistSimilarResponse::class.java
            )
            response.similarArtists?.artist?.map { it.name } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getSimilarTracks(title: String, artist: String): List<Pair<String, String>> {
        if (title.isBlank() || artist.isBlank() || !apiAvailable) return emptyList()
        val cacheKey = "similar_track:$title:$artist"
        val json = cachedApiCall(cacheKey) {
            val response = lastFmApi!!.getSimilarTracks(track = title, artist = artist, apiKey = apiKey)
            gson.toJson(response)
        } ?: return emptyList()
        return try {
            val response = gson.fromJson(
                json, com.example.tuneplay.data.network.LastFmTrackSimilarResponse::class.java
            )
            response.similarTracks?.track?.map {
                Pair(it.name, it.artist?.name ?: artist)
            } ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }

    suspend fun getArtistTags(artistName: String): List<String> {
        if (artistName.isBlank() || !apiAvailable) return emptyList()
        val cacheKey = "artist_tags:$artistName"
        val json = cachedApiCall(cacheKey) {
            val response = lastFmApi!!.getArtistTopTags(artist = artistName, apiKey = apiKey)
            gson.toJson(response)
        } ?: return emptyList()
        return try {
            val response = gson.fromJson(
                json, com.example.tuneplay.data.network.LastFmArtistTagsResponse::class.java
            )
            response.topTags?.tag
                ?.filter { it.count > 0 }
                ?.sortedByDescending { it.count }
                ?.take(3)
                ?.map { it.name }
                ?: emptyList()
        } catch (_: Exception) { emptyList() }
    }
}
