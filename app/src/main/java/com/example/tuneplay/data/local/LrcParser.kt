package com.example.tuneplay.data.local

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 单行歌词数据 — [timeMs] 为毫秒级时间戳，[text] 为歌词文本 */
data class LrcLine(val timeMs: Long, val text: String)

/**
 * LRC 歌词解析器 — 解析标准 LRC 格式文本。
 * 支持 [mm:ss.xx] 和 [mm:ss:xx] 两种时间戳分隔符，忽略元数据标签。
 */
object LrcParser {

    /** LRC 时间戳正则：匹配 [mm:ss.xx] 或 [mm:ss:xx] 格式 */
    private val LINE_REGEX = Regex("""\[(\d{2}):(\d{2})(?:[.:](\d{2,3}))?](.*)""")

    /** 解析 LRC 文本内容，返回按时间排序的歌词行列表 */
    fun parse(content: String): List<LrcLine> {
        return content.lines().mapNotNull { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@mapNotNull null

            // Skip metadata tags like [ti:...], [ar:...], [al:...]
            if (trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
                trimmed.startsWith("[offset:") || trimmed.startsWith("[length:")) {
                return@mapNotNull null
            }

            LINE_REGEX.matchEntire(trimmed)?.let { match ->
                val min = match.groupValues[1].toLong()
                val sec = match.groupValues[2].toLong()
                val centiOrMilli = match.groupValues[3]
                val ms = if (centiOrMilli.length <= 2) {
                    centiOrMilli.padEnd(3, '0').take(3).toLong()
                } else {
                    centiOrMilli.toLong()
                }
                val text = match.groupValues[4].trim()
                if (text.isEmpty()) null else LrcLine(min * 60000 + sec * 1000 + ms, text)
            }
        }.sortedBy { it.timeMs }
    }

    /** 从文件路径读取并解析 LRC 文件，在 IO 线程上执行文件读取 */
    suspend fun parseFromFile(filePath: String): List<LrcLine>? {
        return try {
            val content = withContext(Dispatchers.IO) {
                java.io.File(filePath).readText(Charsets.UTF_8)
            }
            parse(content).ifEmpty { null }
        } catch (e: Exception) {
            null
        }
    }
}
