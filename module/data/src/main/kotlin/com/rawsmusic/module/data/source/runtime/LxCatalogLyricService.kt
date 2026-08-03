package com.rawsmusic.module.data.source.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.source.RawSourceLyric
import com.rawsmusic.core.common.source.RawSourceMediaItem
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Built-in lyric fallback for the catalogs used by [LxCatalogSearchService]. */
internal object LxCatalogLyricService {
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000
    private const val MAX_RESPONSE_BYTES = 4 * 1024 * 1024
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 16) RawSMusic/0.9.61 beta"

    data class Result(
        val lyric: RawSourceLyric,
        val providerLabel: String,
    )

    suspend fun getLyric(item: RawSourceMediaItem): Result = withContext(Dispatchers.IO) {
        val descriptor = descriptor(item)
        when (descriptor.platform) {
            "wy" -> Result(getNetease(descriptor.songId), "网易云目录歌词")
            "kw" -> Result(getKuwo(descriptor.songId), "酷我目录歌词")
            else -> throw IllegalStateException("当前 LX 目录平台不支持歌词：${descriptor.platform.ifBlank { "未知" }}")
        }
    }

    private fun getNetease(songId: String): RawSourceLyric {
        require(songId.all(Char::isDigit)) { "网易云歌曲 ID 无效" }
        val url = "https://music.163.com/api/song/lyric?id=${URLEncoder.encode(songId, "UTF-8")}&lv=1&kv=1&tv=-1"
        val root = requestJson(url, referer = "https://music.163.com/")
        val original = root.objectString("lrc", "lyric")
        val translation = root.objectString("tlyric", "lyric")
        val romanization = root.objectString("romalrc", "lyric")
        val wordByWord = root.objectString("klyric", "lyric")
        require(original.isNotBlank() || wordByWord.isNotBlank()) { "网易云没有返回可用歌词" }
        return RawSourceLyric(
            original = original,
            translation = translation,
            romanization = romanization,
            wordByWord = wordByWord,
        )
    }

    private fun getKuwo(songId: String): RawSourceLyric {
        val normalized = songId.removePrefix("MUSIC_").trim()
        require(normalized.isNotBlank()) { "酷我歌曲 ID 无效" }
        val url = "http://m.kuwo.cn/newh5/singles/songinfoandlrc?musicId=${URLEncoder.encode(normalized, "UTF-8")}&httpsStatus=1"
        val root = requestJson(url, referer = "http://m.kuwo.cn/")
        val list = root.getAsJsonObject("data")?.getAsJsonArray("lrclist")
            ?: root.getAsJsonArray("lrclist")
            ?: throw IllegalStateException("酷我没有返回歌词列表")
        val lines = list.mapNotNull { element ->
            val obj = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val text = obj.string("lineLyric").ifBlank { obj.string("line") }.trim()
            val seconds = obj.string("time").toDoubleOrNull()
                ?: obj.get("time")?.let { runCatching { it.asDouble }.getOrNull() }
            if (text.isBlank() || seconds == null) return@mapNotNull null
            formatLrcTime((seconds * 1_000.0).toLong().coerceAtLeast(0L)) + text
        }
        require(lines.isNotEmpty()) { "酷我没有返回可用歌词" }
        return RawSourceLyric(original = lines.joinToString("\n"))
    }

    private data class Descriptor(val platform: String, val songId: String)

    private fun descriptor(item: RawSourceMediaItem): Descriptor {
        val payload = runCatching { JsonParser.parseString(item.sourcePayload).asJsonObject }.getOrNull()
        val platform = payload?.string("platform")
            ?.ifBlank { payload.string("source") }
            ?.lowercase(Locale.ROOT)
            ?.ifBlank { null }
            ?: item.remoteId.substringBefore(':').lowercase(Locale.ROOT)
        val songId = payload?.string("songmid")
            ?.ifBlank { payload.string("songMid") }
            ?.ifBlank { payload.string("id").substringAfterLast('_') }
            ?.ifBlank { null }
            ?: item.remoteId.substringAfter(':', item.remoteId).substringAfterLast('_')
        return Descriptor(platform, songId)
    }

    private fun requestJson(url: String, referer: String): JsonObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Referer", referer)
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*")
        return try {
            val status = connection.responseCode
            require(status in 200..299) { "歌词接口失败：HTTP $status" }
            val bytes = connection.inputStream.use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(16 * 1024)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    require(output.size() + count <= MAX_RESPONSE_BYTES) { "歌词响应过大" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun formatLrcTime(timestampMs: Long): String {
        val minutes = timestampMs / 60_000L
        val seconds = (timestampMs % 60_000L) / 1_000L
        val millis = timestampMs % 1_000L
        return String.format(Locale.US, "[%02d:%02d.%03d]", minutes, seconds, millis)
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asString }.getOrNull() }.orEmpty()

    private fun JsonObject.objectString(objectName: String, name: String): String =
        getAsJsonObject(objectName)?.string(name).orEmpty()
}
