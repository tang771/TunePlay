package com.example.tuneplay.data.network

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.GET
import retrofit2.http.Query
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * 网易云音乐 API — 通过本地 NeteaseCloudMusicApi 代理 (localhost:3000) 访问。
 * 提供搜索、播放地址（多级音质回退）、歌曲详情、歌词等接口。
 * [create()] 工厂方法配置 Retrofit 实例，含 Cookie 持久化和请求日志。
 * 流媒体 URL 通过本机 IP 签发，模拟器需配合 stream-relay 中继避免 403。
 */
interface NeteaseApi {

    /** 搜索歌曲，type=1 表示单曲 */
    @GET("search")
    suspend fun search(
        @Query("keywords") keywords: String,
        @Query("limit") limit: Int = 30,
        @Query("type") type: Int = 1 // 1 = song
    ): SearchResponse

    /** 获取歌曲播放地址，[level] 为音质等级 (standard/higher/exhigh/lossless) */
    @GET("song/url/v1")
    suspend fun getSongUrl(
        @Query("id") songId: Long,
        @Query("level") level: String = "standard"
    ): SongUrlResponse

    /** 批量获取歌曲详情（封面、专辑信息），[songIds] 为逗号分隔的 ID 列表 */
    @GET("song/detail")
    suspend fun getSongDetail(
        @Query("ids") songIds: String
    ): SongDetailResponse

    /** 获取 LRC 格式歌词 */
    @GET("lyric")
    suspend fun getLyric(
        @Query("id") songId: Long
    ): LyricsResponse

    companion object {
        private const val TAG = "NeteaseApi"
        // Use 10.0.2.2 for Android emulator, replace with LAN IP for real device
        private const val BASE_URL = "http://10.0.2.2:3000/"

        /** Quality levels to try in order — first successful URL wins */
        private val LEVEL_FALLBACK = listOf("standard", "higher", "exhigh", "lossless")

        fun create(): NeteaseApi {
            val logging = HttpLoggingInterceptor().apply {
                level = HttpLoggingInterceptor.Level.BASIC
            }

            val cookieStore = ConcurrentHashMap<String, MutableList<Cookie>>()

            val client = OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .addInterceptor(logging)
                .cookieJar(object : CookieJar {
                    override fun loadForRequest(url: HttpUrl): List<Cookie> {
                        return cookieStore[url.host] ?: emptyList()
                    }

                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        cookieStore.getOrPut(url.host) { mutableListOf() }
                            .apply {
                                for (c in cookies) {
                                    removeIf { it.name == c.name }
                                    add(c)
                                }
                            }
                    }
                })
                .build()

            return Retrofit.Builder()
                .baseUrl(BASE_URL)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
                .create(NeteaseApi::class.java)
        }

    }
}

/** Quality levels to try in order — first successful URL wins */
private val LEVEL_FALLBACK = listOf("standard", "lossless")

/**
 * Try to get a playable song URL, falling back through quality levels.
 * Returns the first non-null URL, or null if all levels fail.
 */
suspend fun NeteaseApi.tryGetSongUrl(songId: Long): String? {
    for (level in LEVEL_FALLBACK) {
        try {
            val response = getSongUrl(songId, level)
            val url = response.data?.firstOrNull { !it.url.isNullOrBlank() }?.url
            if (url != null) {
                if (level != "standard") {
                    android.util.Log.d("NeteaseApi", "Got URL at level=$level for song $songId")
                }
                return url
            }
        } catch (_: Exception) {
            // try next level
        }
    }
    android.util.Log.w("NeteaseApi", "No playable URL for song $songId at any level")
    return null
}
