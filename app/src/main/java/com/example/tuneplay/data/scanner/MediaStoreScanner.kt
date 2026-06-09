package com.example.tuneplay.data.scanner

import android.content.ContentResolver
import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore
import com.example.tuneplay.data.local.entity.Song
import java.io.File

/**
 * 本地音乐扫描器 — 通过 MediaStore ContentProvider 查询设备上的音频文件。
 * 同时提取专辑封面 URI 并尝试在歌曲同目录下查找 .lrc 歌词文件。
 */
class MediaStoreScanner(private val contentResolver: ContentResolver) {

    /** 扫描 MediaStore 中所有音乐文件，返回 Song 列表 */
    fun scan(): List<Song> {
        val songs = mutableListOf<Song>()

        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.DATA,
            MediaStore.Audio.Media.DATE_ADDED,
            MediaStore.Audio.Media.DATE_MODIFIED,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.TRACK,
            MediaStore.Audio.Media.YEAR
        )

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
        val sortOrder = "${MediaStore.Audio.Media.TITLE} ASC"

        val cursor = contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            selection,
            null,
            sortOrder
        )

        cursor?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val dataCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA)
            val dateAddedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val dateModifiedCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val mimeTypeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)

            while (c.moveToNext()) {
                val mediaStoreId = c.getLong(idCol)
                val albumId = c.getLong(albumIdCol)
                val contentUri = ContentUris.withAppendedId(
                    MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, mediaStoreId
                )
                val filePath = contentUri.toString()
                val rawFilePath = c.getString(dataCol) ?: ""
                val coverArtPath = if (albumId > 0) {
                    getAlbumArtUri(albumId).toString()
                } else ""
                val lrcPath = findLrcFile(rawFilePath)

                songs.add(
                    Song(
                        title = c.getString(titleCol) ?: "Unknown Title",
                        artist = c.getString(artistCol) ?: "Unknown Artist",
                        album = c.getString(albumCol) ?: "Unknown Album",
                        duration = c.getLong(durationCol),
                        filePath = filePath,
                        coverArtPath = coverArtPath,
                        lrcPath = lrcPath,
                        fileSize = c.getLong(sizeCol),
                        dateAdded = c.getLong(dateAddedCol) * 1000,
                        dateModified = c.getLong(dateModifiedCol) * 1000,
                        mimeType = c.getString(mimeTypeCol) ?: "",
                        trackNumber = c.getInt(trackCol),
                        year = c.getInt(yearCol),
                        mediaStoreId = mediaStoreId
                    )
                )
            }
        }

        return songs
    }

    /** 在同目录查找与音频文件同名的 .lrc 歌词文件 */
    private fun findLrcFile(filePath: String): String? {
        if (filePath.isBlank()) return null
        val lrcPath = filePath.substringBeforeLast('.') + ".lrc"
        return if (File(lrcPath).exists()) lrcPath else null
    }

    /** 通过 albumId 构造 MediaStore 专辑封面 URI */
    private fun getAlbumArtUri(albumId: Long): Uri {
        return ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )
    }
}
