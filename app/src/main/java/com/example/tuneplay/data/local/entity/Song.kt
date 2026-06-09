package com.example.tuneplay.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 歌曲实体 — 核心数据模型，覆盖本地文件和在线流媒体歌曲。
 * [filePath] 对本地歌曲是文件路径，对在线歌曲是解析后的播放 URL。
 * [neteaseId] > 0 表示在线歌曲，关联网易云音乐 ID。
 */
@Entity(tableName = "songs")
data class Song(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,

    @ColumnInfo(name = "title")
    val title: String,

    @ColumnInfo(name = "artist")
    val artist: String,

    @ColumnInfo(name = "album")
    val album: String,

    @ColumnInfo(name = "album_artist")
    val albumArtist: String = "",

    @ColumnInfo(name = "duration")
    val duration: Long = 0,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "cover_art_path")
    val coverArtPath: String = "",

    @ColumnInfo(name = "file_size")
    val fileSize: Long = 0,

    @ColumnInfo(name = "date_added")
    val dateAdded: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "date_modified")
    val dateModified: Long = 0,

    @ColumnInfo(name = "mime_type")
    val mimeType: String = "",

    @ColumnInfo(name = "track_number")
    val trackNumber: Int = 0,

    @ColumnInfo(name = "year")
    val year: Int = 0,

    @ColumnInfo(name = "genre")
    val genre: String = "",

    @ColumnInfo(name = "sample_rate")
    val sampleRate: Int = 0,

    @ColumnInfo(name = "bitrate")
    val bitrate: Int = 0,

    /** 本地歌词文件路径，在线歌词通过 API 获取 */
    @ColumnInfo(name = "lrc_path")
    val lrcPath: String? = null,

    /** MediaStore 中的音频文件 ID，用于本地文件关联 */
    @ColumnInfo(name = "media_store_id")
    val mediaStoreId: Long = 0,

    /** 网易云音乐歌曲 ID，> 0 表示在线歌曲，用于 URL 解析和歌词获取 */
    @ColumnInfo(name = "netease_id")
    val neteaseId: Long = 0
)
