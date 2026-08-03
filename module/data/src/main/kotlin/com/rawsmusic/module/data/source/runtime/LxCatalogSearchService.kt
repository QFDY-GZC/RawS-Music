package com.rawsmusic.module.data.source.runtime

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.module.data.source.InstalledLxSource
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

/**
 * Catalog search used by LX resolver sources.
 *
 * LX User API scripts resolve media URLs; they do not expose search. Hacylon pairs the selected
 * resolver with built-in Kuwo and Netease catalogs. RawSMusic keeps the same split while converting
 * every result into the existing [RawSourceMediaItem] protocol.
 */
internal object LxCatalogSearchService {
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 20_000
    private const val PAGE_SIZE = 30
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 15) AppleWebKit/537.36 RawSMusic/0.9.61 beta"

    private enum class Platform(
        val wireName: String,
        val displayName: String,
    ) {
        Kuwo("kw", "酷我"),
        Netease("wy", "网易云"),
    }

    suspend fun search(
        source: InstalledLxSource,
        query: String,
        page: Int,
    ): List<MusicSourceSearchGroup> = coroutineScope {
        Platform.entries
            .filter { platform -> source.platforms.isEmpty() || platform.wireName in source.platforms }
            .map { platform ->
            async {
                runCatching {
                    val items = when (platform) {
                        Platform.Kuwo -> searchKuwo(source, query, page)
                        Platform.Netease -> searchNetease(source, query, page)
                    }
                    MusicSourceSearchGroup(
                        sourceId = "${source.id}:${platform.wireName}",
                        sourceName = "${source.name} · ${platform.displayName}",
                        items = items,
                        isEnd = items.size < PAGE_SIZE,
                    )
                }.getOrElse { error ->
                    MusicSourceSearchGroup(
                        sourceId = "${source.id}:${platform.wireName}",
                        sourceName = "${source.name} · ${platform.displayName}",
                        items = emptyList(),
                        error = error.message.orEmpty().ifBlank { "LX ${platform.displayName}搜索失败" }.take(1_024),
                    )
                }
            }
        }.awaitAll()
    }


    /** Hacylon-compatible direct fallback used only after the imported LX resolver fails. */
    suspend fun resolveFallback(
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): com.rawsmusic.core.common.source.RawResolvedAudioSource = withContext(Dispatchers.IO) {
        val raw = runCatching { JsonParser.parseString(item.sourcePayload).asJsonObject }.getOrDefault(JsonObject())
        val platform = raw.string("source").ifBlank { raw.string("platform") }
            .ifBlank { item.remoteId.substringBefore(':') }
        val songMid = raw.string("songmid").ifBlank { item.remoteId.substringAfter(':', item.remoteId) }
        require(songMid.isNotBlank()) { "LX 搜索结果缺少歌曲 ID" }
        when (platform) {
            Platform.Netease.wireName -> com.rawsmusic.core.common.source.RawResolvedAudioSource(
                url = "https://music.163.com/song/media/outer/url?id=$songMid.mp3",
                headers = mapOf("Referer" to "https://music.163.com/", "User-Agent" to USER_AGENT),
                quality = RawSourceQuality.Standard,
            )
            Platform.Kuwo.wireName -> {
                val format = if (quality == RawSourceQuality.Lossless || quality == RawSourceQuality.HiRes) "flac" else "mp3"
                val requestUrl = "http://antiserver.kuwo.cn/anti.s?type=convert_url&rid=MUSIC_$songMid&format=$format&response=url"
                val connection = URI(requestUrl).toURL().openConnection() as HttpURLConnection
                connection.instanceFollowRedirects = true
                connection.connectTimeout = CONNECT_TIMEOUT_MS
                connection.readTimeout = READ_TIMEOUT_MS
                connection.requestMethod = "GET"
                connection.setRequestProperty("User-Agent", USER_AGENT)
                try {
                    val status = connection.responseCode
                    require(status in 200..299) { "酷我备用地址解析失败：HTTP $status" }
                    val playableUrl = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }.trim()
                    require(playableUrl.startsWith("http://") || playableUrl.startsWith("https://")) {
                        "酷我备用地址解析失败"
                    }
                    com.rawsmusic.core.common.source.RawResolvedAudioSource(
                        url = playableUrl,
                        headers = mapOf("User-Agent" to USER_AGENT),
                        quality = quality,
                    )
                } finally {
                    connection.disconnect()
                }
            }
            else -> throw IllegalStateException("当前 LX 平台没有备用播放地址")
        }
    }

    private suspend fun searchKuwo(
        source: InstalledLxSource,
        query: String,
        page: Int,
    ): List<RawSourceMediaItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val pageIndex = (page - 1).coerceAtLeast(0)
        val url = "http://search.kuwo.cn/r.s?client=kt&all=$encoded&pn=$pageIndex&rn=$PAGE_SIZE" +
            "&uid=794762570&ver=kwplayer_ar_9.2.2.1&vipver=1&show_copyright_off=1&newver=1" +
            "&ft=music&cluster=0&strategy=2012&encoding=utf8&rformat=json&vermerge=1&mobi=1&issubtitle=1"
        val root = requestJson(url)
        val list = root.getAsJsonArray("abslist") ?: return@withContext emptyList()
        list.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val songMid = item.string("MUSICRID").removePrefix("MUSIC_")
                .ifBlank { item.string("DC_TARGETID") }
            val title = decodeHtml(item.string("SONGNAME"))
            if (songMid.isBlank() || title.isBlank()) return@mapNotNull null
            val artist = decodeHtml(item.string("ARTIST")).replace("&", "、")
            val album = decodeHtml(item.string("ALBUM"))
            val durationMs = item.long("DURATION").coerceAtLeast(0L) * 1_000L
            val artwork = buildKuwoCoverUrl(item.string("web_albumpic_short"))
            val qualities = kuwoQualities(item.string("N_MINFO"))
            lxItem(
                resolver = source,
                platform = Platform.Kuwo,
                songMid = songMid,
                title = title,
                artist = artist,
                album = album,
                durationMs = durationMs,
                artwork = artwork,
                qualities = qualities,
            )
        }
    }

    private suspend fun searchNetease(
        source: InstalledLxSource,
        query: String,
        page: Int,
    ): List<RawSourceMediaItem> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), Charsets.UTF_8.name())
        val offset = (page - 1).coerceAtLeast(0) * PAGE_SIZE
        val url = "https://music.163.com/api/search/get/web?csrf_token=&hlpretag=&hlposttag=&s=$encoded" +
            "&type=1&offset=$offset&total=true&limit=$PAGE_SIZE"
        val root = requestJson(url, referer = "https://music.163.com/")
        val songs = root.getAsJsonObject("result")?.getAsJsonArray("songs") ?: return@withContext emptyList()
        songs.mapNotNull { element ->
            val item = element.takeIf { it.isJsonObject }?.asJsonObject ?: return@mapNotNull null
            val songMid = item.long("id").takeIf { it > 0L }?.toString().orEmpty()
            val title = decodeHtml(item.string("name"))
            if (songMid.isBlank() || title.isBlank()) return@mapNotNull null
            val artists = item.getAsJsonArray("artists")
                ?.mapNotNull { artistElement ->
                    artistElement.takeIf { it.isJsonObject }?.asJsonObject?.string("name")
                        ?.let(::decodeHtml)
                        ?.takeIf(String::isNotBlank)
                }
                .orEmpty()
            val albumObject = item.getAsJsonObject("album")
            val album = decodeHtml(albumObject?.string("name").orEmpty())
            val artwork = albumObject?.string("picUrl").orEmpty()
            lxItem(
                resolver = source,
                platform = Platform.Netease,
                songMid = songMid,
                title = title,
                artist = artists.joinToString("、"),
                album = album,
                durationMs = item.long("duration").coerceAtLeast(0L),
                artwork = artwork,
                qualities = linkedSetOf(
                    RawSourceQuality.Standard,
                    RawSourceQuality.High,
                    RawSourceQuality.Lossless,
                ),
            )
        }
    }

    private fun lxItem(
        resolver: InstalledLxSource,
        platform: Platform,
        songMid: String,
        title: String,
        artist: String,
        album: String,
        durationMs: Long,
        artwork: String,
        qualities: Set<RawSourceQuality>,
    ): RawSourceMediaItem {
        val payload = JsonObject().apply {
            addProperty("protocol", "lx")
            addProperty("source", platform.wireName)
            addProperty("platform", platform.wireName)
            addProperty("songmid", songMid)
            addProperty("id", "${platform.wireName}_$songMid")
            addProperty("name", title)
            addProperty("title", title)
            addProperty("singer", artist)
            addProperty("artist", artist)
            addProperty("albumName", album)
            addProperty("album", album)
            addProperty("interval", formatDuration(durationMs))
            addProperty("durationMs", durationMs)
            addProperty("artwork", artwork)
            addProperty("pic", artwork)
        }.toString()
        return RawSourceMediaItem(
            sourceId = resolver.id,
            remoteId = "${platform.wireName}:$songMid",
            mediaType = RawSourceMediaType.Music,
            title = title,
            artists = artist.split('、', '/', '&').map(String::trim).filter(String::isNotBlank),
            album = album,
            durationMs = durationMs,
            artworkUrl = artwork,
            availableQualities = qualities.ifEmpty { setOf(RawSourceQuality.Standard) },
            sourcePayload = payload,
        )
    }

    private fun requestJson(url: String, referer: String = ""): JsonObject {
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.instanceFollowRedirects = true
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("User-Agent", USER_AGENT)
        if (referer.isNotBlank()) connection.setRequestProperty("Referer", referer)
        return try {
            val status = connection.responseCode
            require(status in 200..299) { "目录搜索失败：HTTP $status" }
            val text = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            JsonParser.parseString(text).asJsonObject
        } finally {
            connection.disconnect()
        }
    }

    private fun kuwoQualities(raw: String): Set<RawSourceQuality> = linkedSetOf<RawSourceQuality>().apply {
        add(RawSourceQuality.Standard)
        if ("bitrate:320" in raw || "bitrate:2000" in raw || "bitrate:4000" in raw) add(RawSourceQuality.High)
        if ("bitrate:2000" in raw || "bitrate:4000" in raw) add(RawSourceQuality.Lossless)
        if ("bitrate:4000" in raw) add(RawSourceQuality.HiRes)
    }

    private fun buildKuwoCoverUrl(path: String): String {
        val normalized = path.trim()
        return when {
            normalized.startsWith("http://") || normalized.startsWith("https://") -> normalized
            normalized.isNotBlank() -> {
                val highResPath = normalized.replace(Regex("""^\d+/"""), "500/")
                "https://img1.kuwo.cn/star/albumcover/$highResPath"
            }
            else -> ""
        }
    }

    private fun decodeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&nbsp;", " ")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .trim()

    private fun formatDuration(durationMs: Long): String {
        val totalSeconds = (durationMs / 1_000L).coerceAtLeast(0L)
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun JsonObject.string(name: String): String =
        get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()

    private fun JsonObject.long(name: String): Long =
        get(name)?.takeUnless { it.isJsonNull }?.let { runCatching { it.asLong }.getOrNull() } ?: 0L
}
