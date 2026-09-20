package com.rawsmusic.module.data.artist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ArtistImageCandidateSource {
    LastFm,
    Netease,
}

data class ArtistImageCandidate(
    val url: String,
    val source: ArtistImageCandidateSource,
)

/**
 * Artist-image discovery used by the artist fullscreen viewer.
 *
 * This intentionally returns remote identities only. BitmapProvider remains the single decoder,
 * source-cache and bitmap-wrapper owner, so artist images do not introduce a second image stack.
 */
object ArtistImageCandidateRepository {
    private const val CONNECT_TIMEOUT_MS = 8_000
    private const val READ_TIMEOUT_MS = 12_000
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 RawSMusic/0.9.61 beta"

    private val memoryCache = object : LinkedHashMap<String, List<ArtistImageCandidate>>(24, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, List<ArtistImageCandidate>>?,
        ): Boolean = size > 48
    }

    suspend fun fetchCandidates(artistName: String): List<ArtistImageCandidate> = withContext(Dispatchers.IO) {
        val normalized = artistName.trim()
        if (normalized.isBlank()) return@withContext emptyList()
        val cacheKey = normalized.lowercase(Locale.ROOT)
        synchronized(memoryCache) { memoryCache[cacheKey] }?.let { return@withContext it }

        val found = buildList {
            runCatching { fetchLastFm(normalized) }.getOrNull()?.let(::add)
            runCatching { fetchNetease(normalized) }.getOrNull()?.let(::add)
        }.distinctBy { it.url }
        synchronized(memoryCache) { memoryCache[cacheKey] = found }
        found
    }

    private fun fetchLastFm(artistName: String): ArtistImageCandidate? {
        val slug = URLEncoder.encode(artistName, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%2F", "/", ignoreCase = true)
        val html = requestText("https://www.last.fm/music/$slug")
        val url = parseOpenGraphImage(html) ?: return null
        return ArtistImageCandidate(url = url, source = ArtistImageCandidateSource.LastFm)
    }

    private fun fetchNetease(artistName: String): ArtistImageCandidate? {
        val encoded = URLEncoder.encode(artistName, Charsets.UTF_8.name())
        val raw = requestText(
            url = "https://music.163.com/api/search/get/web?s=$encoded&type=100&offset=0&limit=5",
            headers = mapOf("Referer" to "https://music.163.com/"),
        )
        val url = parseNeteaseArtistImageUrl(raw, artistName) ?: return null
        return ArtistImageCandidate(url = url, source = ArtistImageCandidateSource.Netease)
    }

    internal fun parseOpenGraphImage(html: String): String? {
        val patterns = listOf(
            Regex("""(?is)<meta[^>]+property\s*=\s*[\"']og:image[\"'][^>]+content\s*=\s*[\"']([^\"']+)[\"']"""),
            Regex("""(?is)<meta[^>]+content\s*=\s*[\"']([^\"']+)[\"'][^>]+property\s*=\s*[\"']og:image[\"']"""),
        )
        return patterns.asSequence()
            .mapNotNull { it.find(html)?.groupValues?.getOrNull(1) }
            .map(::decodeHtmlEntities)
            .firstOrNull(::isUsableImageUrl)
    }

    internal fun parseNeteaseArtistImageUrl(raw: String, artistName: String): String? {
        val artists = runCatching {
            JsonParser.parseString(raw).asJsonObject
                .getAsJsonObject("result")
                ?.getAsJsonArray("artists")
        }.getOrNull() ?: return null
        val requested = artistName.trim()
        val exact = mutableListOf<JsonObject>()
        val folded = mutableListOf<JsonObject>()
        artists.forEach { element ->
            val artist = runCatching { element.asJsonObject }.getOrNull() ?: return@forEach
            val name = artist.get("name")?.asString.orEmpty().trim()
            when {
                name == requested -> exact += artist
                name.equals(requested, ignoreCase = true) -> folded += artist
            }
        }
        return (exact + folded).asSequence()
            .flatMap { artist ->
                sequenceOf("picUrl", "img1v1Url", "coverUrl").mapNotNull { key ->
                    artist.get(key)?.asString?.trim()?.takeIf(::isUsableImageUrl)
                }
            }
            .firstOrNull()
    }

    private fun requestText(url: String, headers: Map<String, String> = emptyMap()): String {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Accept", "text/html,application/json;q=0.9,*/*;q=0.8")
            headers.forEach(::setRequestProperty)
        }
        return try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun isUsableImageUrl(value: String): Boolean {
        val url = value.trim()
        if (!url.startsWith("https://", ignoreCase = true) &&
            !url.startsWith("http://", ignoreCase = true)
        ) return false
        val lower = url.lowercase(Locale.ROOT)
        return "default_avatar" !in lower && "2a96cbd8b46e442fc41c2b86b821562f" !in lower
    }

    private fun decodeHtmlEntities(value: String): String = value
        .replace("&amp;", "&")
        .replace("&#38;", "&")
        .replace("&quot;", "\"")
        .trim()
}
