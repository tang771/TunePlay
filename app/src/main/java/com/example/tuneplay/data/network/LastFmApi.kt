package com.example.tuneplay.data.network

import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.TimeUnit

/**
 * Last.fm API — 通过 ws.audioscrobbler.com 获取音乐推荐数据。
 * 用于智能推荐：相似艺术家、相似曲目、标签热门曲目。
 * [apiKey] 需要从 strings.xml 的 lastfm_api_key 配置。
 */
interface LastFmApi {

    /** 获取相似艺术家列表 */
    @GET("2.0/")
    suspend fun getSimilarArtists(
        @Query("method") method: String = "artist.getsimilar",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 10
    ): LastFmArtistSimilarResponse

    /** 获取相似曲目 */
    @GET("2.0/")
    suspend fun getSimilarTracks(
        @Query("method") method: String = "track.getsimilar",
        @Query("track") track: String,
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 10
    ): LastFmTrackSimilarResponse

    /** 获取艺术家热门标签 */
    @GET("2.0/")
    suspend fun getArtistTopTags(
        @Query("method") method: String = "artist.gettoptags",
        @Query("artist") artist: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json"
    ): LastFmArtistTagsResponse

    /** 获取标签下的热门曲目 */
    @GET("2.0/")
    suspend fun getTagTopTracks(
        @Query("method") method: String = "tag.gettoptracks",
        @Query("tag") tag: String,
        @Query("api_key") apiKey: String,
        @Query("format") format: String = "json",
        @Query("limit") limit: Int = 20
    ): LastFmTagTopTracksResponse

    companion object {
        private const val BASE_URL = "https://ws.audioscrobbler.com/"

        fun create(apiKey: String): LastFmApi {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(LastFmApi::class.java)
        }
    }
}
