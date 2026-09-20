package com.rawsmusic.module.data.artist

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.module.data.prefs.AppPreferences
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class ArtistBiographySource {
    Wikipedia,
    LastFm,
    Netease,
}

data class ArtistBiography(
    val artistName: String,
    val text: String,
    val source: ArtistBiographySource,
    val language: String,
    val sourceUrl: String,
)

object ArtistBiographyPreferences {
    private const val KEY_SOURCE = "artist_biography_source"
    private const val KEY_LANGUAGE = "artist_biography_language"

    var source: ArtistBiographySource
        get() {
            val raw = AppPreferences.storage.decodeString(KEY_SOURCE, ArtistBiographySource.Wikipedia.name)
            return ArtistBiographySource.entries.firstOrNull { it.name == raw }
                ?: ArtistBiographySource.Wikipedia
        }
        set(value) {
            AppPreferences.storage.encode(KEY_SOURCE, value.name)
        }

    var language: String
        get() = AppPreferences.storage.decodeString(KEY_LANGUAGE, "zh") ?: "zh"
        set(value) {
            AppPreferences.storage.encode(KEY_LANGUAGE, value)
        }
}

/**
 * Artist biography provider shared by the library UI.
 *
 * The selected provider stays authoritative. A provider failure is surfaced to the caller instead
 * of silently replacing the requested source with another service. Matching is intentionally
 * conservative: exact artist-name matches win, followed only by case-insensitive exact matches.
 */
object ArtistBiographyRepository {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val CACHE_LIMIT = 64
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 RawSMusic/0.9.61 beta"

    private data class CacheKey(
        val artistName: String,
        val source: ArtistBiographySource,
        val language: String,
    )

    private val memoryCache = object : LinkedHashMap<CacheKey, ArtistBiography>(16, 0.75f, true) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<CacheKey, ArtistBiography>?
        ): Boolean = size > CACHE_LIMIT
    }

    suspend fun fetch(
        artistName: String,
        source: ArtistBiographySource,
        language: String = "zh",
    ): ArtistBiography = withContext(Dispatchers.IO) {
        val normalizedArtist = artistName.trim()
        require(normalizedArtist.isNotBlank()) { "艺术家名称为空" }
        val normalizedLanguage = normalizeLanguage(language)
        val key = CacheKey(
            artistName = normalizedArtist.lowercase(Locale.ROOT),
            source = source,
            language = normalizedLanguage,
        )
        synchronized(memoryCache) { memoryCache[key] }?.let { return@withContext it }

        val result = when (source) {
            ArtistBiographySource.Wikipedia -> fetchWikipedia(normalizedArtist, normalizedLanguage)
            ArtistBiographySource.LastFm -> fetchLastFm(normalizedArtist, normalizedLanguage)
            ArtistBiographySource.Netease -> fetchNetease(normalizedArtist)
        }
        synchronized(memoryCache) { memoryCache[key] = result }
        result
    }

    private fun fetchWikipedia(artistName: String, language: String): ArtistBiography {
        val wikiLanguage = when (language) {
            "zh", "zh-cn", "zh-hk", "zh-tw" -> "zh"
            else -> language
        }
        val variant = when (language) {
            "zh", "zh-cn" -> "zh-cn"
            "zh-hk" -> "zh-hk"
            "zh-tw" -> "zh-tw"
            else -> null
        }
        val apiRoot = "https://$wikiLanguage.wikipedia.org/w/api.php"
        val searchParams = linkedMapOf(
            "action" to "query",
            "list" to "search",
            "srsearch" to artistName,
            "srlimit" to "10",
            "format" to "json",
            "utf8" to "1",
        )
        variant?.let { searchParams["variant"] = it }
        val searchRaw = requestText(buildUrl(apiRoot, searchParams))
        val title = parseWikipediaSearchTitle(searchRaw, artistName)
            ?: error("Wikipedia 未找到与“$artistName”精确匹配的艺术家页面")

        val extractParams = linkedMapOf(
            "action" to "query",
            "prop" to "extracts",
            "exlimit" to "1",
            "explaintext" to "1",
            "redirects" to "1",
            "titles" to title,
            "format" to "json",
            "utf8" to "1",
        )
        variant?.let { extractParams["variant"] = it }
        val extractRaw = requestText(buildUrl(apiRoot, extractParams))
        val extract = parseWikipediaExtract(extractRaw)
            ?: error("Wikipedia 页面没有可用的传记正文")
        val pageTitle = extract.second.ifBlank { title }
        val encodedTitle = encodePathSegment(pageTitle.replace(' ', '_'))
        val sourceUrl = if (wikiLanguage == "zh" && variant != null) {
            "https://zh.wikipedia.org/$variant/$encodedTitle"
        } else {
            "https://$wikiLanguage.wikipedia.org/wiki/$encodedTitle"
        }
        return ArtistBiography(
            artistName = artistName,
            text = extract.first,
            source = ArtistBiographySource.Wikipedia,
            language = language,
            sourceUrl = sourceUrl,
        )
    }

    private fun fetchLastFm(artistName: String, language: String): ArtistBiography {
        val prefix = when (language) {
            "en" -> ""
            "zh-cn", "zh-hk", "zh-tw" -> "zh"
            else -> language
        }
        val host = if (prefix.isBlank()) "https://www.last.fm" else "https://www.last.fm/$prefix"
        val slug = encodeLastFmArtistSlug(artistName)
        val artistUrl = "$host/music/$slug"
        val wikiUrl = "$artistUrl/+wiki"
        val html = requestText(
            wikiUrl,
            headers = mapOf("Accept-Language" to acceptLanguage(language)),
        )
        if (isBotChallengeHtml(html)) {
            error("Last.fm 返回了安全验证页面，请稍后重试或切换来源")
        }
        val text = parseLastFmWikiHtml(html)
        if (text.isBlank()) error("Last.fm 页面未包含该艺术家的传记内容")
        return ArtistBiography(
            artistName = artistName,
            text = text,
            source = ArtistBiographySource.LastFm,
            language = language,
            sourceUrl = artistUrl,
        )
    }

    private fun fetchNetease(artistName: String): ArtistBiography {
        val searchUrl = buildUrl(
            "https://music.163.com/api/search/get/web",
            linkedMapOf(
                "s" to artistName,
                "type" to "100",
                "offset" to "0",
                "limit" to "5",
            ),
        )
        val artistId = parseNeteaseArtistId(
            requestText(searchUrl, headers = mapOf("Referer" to "https://music.163.com/")),
            artistName,
        ) ?: error("网易云音乐未找到与“$artistName”精确匹配的艺术家")
        val biographyUrl = buildUrl(
            "https://music.163.com/api/artist/introduction",
            mapOf("id" to artistId),
        )
        val text = parseNeteaseBiography(
            requestText(biographyUrl, headers = mapOf("Referer" to "https://music.163.com/"))
        )
        if (text.isBlank()) error("网易云音乐没有该艺术家的传记内容")
        return ArtistBiography(
            artistName = artistName,
            text = text,
            source = ArtistBiographySource.Netease,
            language = "zh",
            sourceUrl = "https://y.music.163.com/m/artist?id=$artistId",
        )
    }

    internal fun parseWikipediaSearchTitle(raw: String, artistName: String): String? {
        val search = raw.asObjectOrNull()
            ?.getAsJsonObject("query")
            ?.getAsJsonArray("search")
            ?: return null
        val requested = artistName.trim()
        val titles = search.mapNotNull { item ->
            item.takeIf { it.isJsonObject }
                ?.asJsonObject
                ?.string("title")
                ?.trim()
                ?.takeIf(String::isNotBlank)
        }
        return titles.firstOrNull { it == requested }
            ?: titles.firstOrNull { it.equals(requested, ignoreCase = true) }
    }

    internal fun parseWikipediaExtract(raw: String): Pair<String, String>? {
        val pages = raw.asObjectOrNull()
            ?.getAsJsonObject("query")
            ?.getAsJsonObject("pages")
            ?: return null
        for ((_, element) in pages.entrySet()) {
            val page = element.takeIf { it.isJsonObject }?.asJsonObject ?: continue
            if (page.has("missing")) continue
            val extract = page.string("extract").trim()
            if (extract.isBlank()) continue
            return extract to page.string("title").trim()
        }
        return null
    }

    internal fun parseNeteaseArtistId(raw: String, artistName: String): String? {
        val artists = raw.asObjectOrNull()
            ?.getAsJsonObject("result")
            ?.getAsJsonArray("artists")
            ?: return null
        val requested = artistName.trim()
        fun find(ignoreCase: Boolean): String? {
            artists.forEach { element ->
                val artist = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                if (artist.string("name").trim().equals(requested, ignoreCase = ignoreCase)) {
                    val id = runCatching { artist.get("id")?.asLong ?: 0L }.getOrDefault(0L)
                    if (id > 0L) return id.toString()
                }
            }
            return null
        }
        return find(ignoreCase = false) ?: find(ignoreCase = true)
    }

    internal fun parseNeteaseBiography(raw: String): String {
        val root = raw.asObjectOrNull() ?: return ""
        val sections = buildList {
            root.string("briefDesc").trim().takeIf(String::isNotBlank)?.let(::add)
            root.getAsJsonArray("introduction")?.forEach { element ->
                val section = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@forEach
                val title = section.string("ti").trim()
                val text = section.string("txt").trim()
                if (text.isNotBlank()) {
                    add(listOf(title, text).filter(String::isNotBlank).joinToString("\n"))
                }
            }
        }
        return sections.distinct().joinToString("\n\n").trim()
    }

    internal fun parseLastFmWikiHtml(html: String): String {
        val block = wikiHtmlBlock(html) ?: return ""
        val text = block
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p>"), "\n\n")
            .replace(Regex("(?i)</li>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), "")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#34;", "\"")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&apos;", "'")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n[ \\t]+"), "\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
        return stripLastFmLicenseFooter(text)
    }

    private fun wikiHtmlBlock(html: String): String? {
        val patterns = listOf(
            Regex("""(?is)<div[^>]*class=[\"'][^\"']*wiki-content[^\"']*[\"'][^>]*>(.*?)</div>"""),
            Regex("""(?is)<div[^>]*id=[\"']wiki[\"'][^>]*>(.*?)</div>"""),
            Regex("""(?is)<section[^>]*class=[\"'][^\"']*wiki[^\"']*[\"'][^>]*>(.*?)</section>"""),
        )
        return patterns.firstNotNullOfOrNull { pattern ->
            pattern.find(html)?.groupValues?.getOrNull(1)?.takeIf(String::isNotBlank)
        }
    }

    private fun stripLastFmLicenseFooter(text: String): String {
        val markers = listOf(
            "User-contributed text is available under the Creative Commons",
            "用户贡献的文本在知识共享",
            "ユーザーが投稿したテキストはクリエイティブ・コモンズ",
        )
        var result = text
        markers.forEach { marker ->
            val index = result.indexOf(marker, ignoreCase = true)
            if (index >= 0) result = result.substring(0, index).trim()
        }
        return result
    }

    private fun isBotChallengeHtml(html: String): Boolean =
        html.contains("Just a moment...", ignoreCase = true) ||
            html.contains("Client Challenge", ignoreCase = true) ||
            html.contains("cf-browser-verification", ignoreCase = true) ||
            html.contains("challenges.cloudflare.com", ignoreCase = true) ||
            html.contains("Attention Required! | Cloudflare", ignoreCase = true)

    private fun requestText(
        url: String,
        headers: Map<String, String> = emptyMap(),
    ): String {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        connection.setRequestProperty("Accept", "application/json,text/html;q=0.9,*/*;q=0.8")
        headers.forEach(connection::setRequestProperty)
        return try {
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            require(status in 200..299) { "HTTP $status" }
            body
        } finally {
            connection.disconnect()
        }
    }

    private fun buildUrl(base: String, params: Map<String, String>): String {
        val query = params.entries.joinToString("&") { (key, value) ->
            "${encodeQuery(key)}=${encodeQuery(value)}"
        }
        return if (query.isBlank()) base else "$base?$query"
    }

    private fun encodeQuery(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

    private fun encodePathSegment(value: String): String =
        URLEncoder.encode(value, Charsets.UTF_8.name())
            .replace("+", "%20")
            .replace("%2F", "/", ignoreCase = true)

    private fun encodeLastFmArtistSlug(value: String): String =
        URLEncoder.encode(value.trim(), Charsets.UTF_8.name()).replace("%20", "+", ignoreCase = true)

    private fun normalizeLanguage(value: String): String = when (value.trim().lowercase(Locale.ROOT)) {
        "zh", "zh-cn" -> "zh"
        "zh-hk" -> "zh-hk"
        "zh-tw" -> "zh-tw"
        "ja" -> "ja"
        "ko" -> "ko"
        else -> "en"
    }

    private fun acceptLanguage(language: String): String = when (normalizeLanguage(language)) {
        "zh" -> "zh-CN,zh;q=0.9,en;q=0.6"
        "zh-hk" -> "zh-HK,zh;q=0.9,en;q=0.6"
        "zh-tw" -> "zh-TW,zh;q=0.9,en;q=0.6"
        "ja" -> "ja-JP,ja;q=0.9,en;q=0.6"
        "ko" -> "ko-KR,ko;q=0.9,en;q=0.6"
        else -> "en-US,en;q=0.9"
    }

    private fun String.asObjectOrNull(): JsonObject? = runCatching {
        JsonParser.parseString(this).takeIf { it.isJsonObject }?.asJsonObject
    }.getOrNull()

    private fun JsonObject.string(key: String): String = runCatching {
        get(key)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
    }.getOrDefault("")
}
