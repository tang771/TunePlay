package com.example.tuneplay.data.network

import com.google.gson.annotations.SerializedName

// =============================================================================
// Last.fm API 数据模型 — 用于推荐引擎的第三方音乐数据
// https://www.last.fm/api
// =============================================================================

// ── artist.getSimilar 相似艺术家 ──

/** 相似艺术家响应，[error] 非 null 时表示 API 调用失败 */
data class LastFmArtistSimilarResponse(
    @SerializedName("similarartists") val similarArtists: LastFmSimilarArtists? = null,
    val error: Int? = null,
    val message: String? = null
)

data class LastFmSimilarArtists(
    val artist: List<LastFmSimilarArtist> = emptyList()
)

data class LastFmSimilarArtist(
    val name: String,
    val match: String = "0"
)

// ── track.getSimilar 相似曲目 ──

/** 相似曲目响应 */
data class LastFmTrackSimilarResponse(
    @SerializedName("similartracks") val similarTracks: LastFmSimilarTracks? = null,
    val error: Int? = null,
    val message: String? = null
)

data class LastFmSimilarTracks(
    val track: List<LastFmSimilarTrack> = emptyList()
)

data class LastFmSimilarTrack(
    val name: String,
    val artist: LastFmArtistName? = null,
    val match: String = "0"
)

data class LastFmArtistName(
    val name: String
)

// ── artist.getTopTags 艺术家标签 ──

/** 艺术家热门标签响应 */
data class LastFmArtistTagsResponse(
    @SerializedName("toptags") val topTags: LastFmTopTags? = null,
    val error: Int? = null,
    val message: String? = null
)

data class LastFmTopTags(
    val tag: List<LastFmTag> = emptyList()
)

data class LastFmTag(
    val name: String,
    val count: Int = 0
)

// ── tag.getTopTracks 标签热门曲目 ──

/** 标签热门曲目响应 */
data class LastFmTagTopTracksResponse(
    val tracks: LastFmTagTracks? = null,
    val error: Int? = null,
    val message: String? = null
)

data class LastFmTagTracks(
    val track: List<LastFmTagTrack> = emptyList()
)

data class LastFmTagTrack(
    val name: String,
    val artist: LastFmArtistName? = null
)
