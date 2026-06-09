package com.example.tuneplay.data.network

import com.google.gson.annotations.SerializedName

// =============================================================================
// 网易云音乐 API 数据模型
// 通过本地代理服务 (NeteaseCloudMusicApi) 获取数据
// =============================================================================

// ---- Search 搜索 ----

/** 搜索响应 — 包含搜索结果和状态码 */
data class SearchResponse(
    val result: SearchResult?,
    val code: Int
)

/** 搜索结果，包含歌曲列表和总数 */
data class SearchResult(
    val songs: List<OnlineSong>?,
    val songCount: Int = 0
)

/** 在线歌曲 — 网易云搜索结果中的歌曲条目。封面通过伴生对象缓存避免破坏 data class 语义。 */
data class OnlineSong(
    val id: Long,
    val name: String,
    val artists: List<ArtistInfo>?,
    val album: AlbumInfo?,
    val duration: Long = 0,
    val fee: Int = 0,
    val mvid: Long = 0
) {
    companion object {
        /** /song/detail API 解析后的高清封面缓存，key 为歌曲 ID */
        private val resolvedCoverCache = java.util.concurrent.ConcurrentHashMap<Long, String>()

        fun setResolvedCover(songId: Long, url: String) {
            resolvedCoverCache[songId] = url
        }

        fun getResolvedCover(songId: Long): String? = resolvedCoverCache[songId]
    }

    fun artistNames(): String = artists?.joinToString(" / ") { it.name } ?: "Unknown"

    fun coverUrl(): String {
        // Priority 1: resolved from /song/detail API
        getResolvedCover(id)?.let { return it }
        // Priority 2: album picUrl from search response
        album?.picUrl?.let { return it }
        // Priority 3: artist avatar
        artists?.firstOrNull()?.img1v1Url?.ifBlank { null }?.let { return it }
        // Fallback: hardcoded default cover
        return "https://p2.music.126.net/6y-UleORITEDbvrOLV0Q8A==/5639395138885805.jpg"
    }
}

/** 艺术家信息 */
data class ArtistInfo(
    val id: Long,
    val name: String,
    val img1v1Url: String = ""
)

/** 专辑信息，[picUrl] 由搜索接口直接返回时可能为 null，需通过 /song/detail 补全 */
data class AlbumInfo(
    val id: Long,
    val name: String,
    @SerializedName("picId")
    val picId: Long = 0,
    @SerializedName("picUrl")
    val picUrl: String? = null
)

// ---- Song URL 播放地址 ----

/** 歌曲播放地址响应 */
data class SongUrlResponse(
    val data: List<SongUrlData>?,
    val code: Int
)

/** 单条播放地址数据，[url] 为实际播放链接（可能为 null 表示无版权） */
data class SongUrlData(
    val id: Long,
    val url: String?,
    val br: Long = 0,
    val type: String = "mp3",
    val code: Int = 200
)

// ---- Song Detail 歌曲详情（封面、专辑） ----

/** 歌曲详情响应，用于获取高清封面 */
data class SongDetailResponse(
    val songs: List<SongDetail>?,
    val code: Int
)

/** 歌曲详情，包含高分辨率专辑封面信息 */
data class SongDetail(
    val id: Long,
    val name: String,
    @SerializedName("al")
    val album: SongDetailAlbum?,
    @SerializedName("ar")
    val artists: List<ArtistInfo>?
)

/** 歌曲详情中的专辑信息，[picUrl] 通常为非 null */
data class SongDetailAlbum(
    val id: Long,
    val name: String,
    @SerializedName("picUrl")
    val picUrl: String?
)

// ---- Lyrics 歌词 ----

/** 歌词响应 */
data class LyricsResponse(
    @SerializedName("lrc")
    val lrc: LyricsData?,
    val code: Int
)

/** 歌词数据，[lyric] 为 LRC 格式文本 */
data class LyricsData(
    val lyric: String?,
    val version: Int = 0
)
