package com.rawsmusic.module.data.prefs

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.MessageDigest
import java.text.Normalizer

data class VideoCoverSearchCandidate(
    val id: String,
    val title: String,
    val artist: String,
    val album: String,
    val artworkUrl: String,
    val selectionUrl: String,
    val score: Int,
    val provider: String = "Spotify Canvas",
)

/**
 * Resolves real animated cover media without coupling the player to a catalog scraper.
 *
 * The remote catalog is Spotify Canvas as indexed by Canvas Downloader. Search results only
 * become candidates after a real canvaz MP4 URL has been found, so an artwork thumbnail can
 * never be mistaken for an importable animation. Direct MP4/WebP/WebM/HLS links remain
 * supported for manual imports.
 */
object VideoCoverRemoteRepository {
    private const val TAG = "VideoCoverRemote"
    private const val CANVAS_BASE = "https://www.canvasdownloader.com"
    private const val CANVAS_FALLBACK_BASE = "https://canvasdownloader.com"
    private const val GITHUB_CANVAS_DATASET =
        "https://raw.githubusercontent.com/kywagaha/spotify-canvases/main/canvases.json"
    private const val GITHUB_CANVAS_DATASET_FALLBACK =
        "https://cdn.jsdelivr.net/gh/kywagaha/spotify-canvases@main/canvases.json"
    private const val SPOTIFY_OEMBED = "https://open.spotify.com/oembed"
    private const val MAX_BYTES = 96L * 1024L * 1024L
    private const val MAX_CANDIDATES = 12
    private const val MAX_ARTIST_RESULTS = 3
    private const val MAX_TRACKS_PER_ARTIST = 24
    private const val MAX_ARTIST_PAGES = 3
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000

    suspend fun import(
        context: Context,
        artist: String,
        album: String,
        title: String,
        input: String,
    ): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val source = input.trim()
            Log.i(
                TAG,
                "import_start provider=spotify_canvas title=${title.take(80)} " +
                    "artist=${artist.take(80)} album=${album.take(80)} " +
                    "direct=${source.isDirectMediaUrl()}",
            )
            require(source.isNotBlank() || artist.isNotBlank() || title.isNotBlank()) {
                "请输入 Spotify 歌曲链接、媒体直链，或先补全歌曲信息"
            }

            val mediaUrls = when {
                source.isDirectMediaUrl() -> listOf(source)
                source.isSpotifyTrackUrl() -> findTrackCanvas(source.spotifyTrackId().orEmpty())
                source.isBlank() -> findAvailableCandidates(artist, album, title)
                    .map(VideoCoverSearchCandidate::selectionUrl)
                else -> emptyList()
            }
            Log.i(TAG, "match_candidates provider=spotify_canvas count=${mediaUrls.size}")
            val selected = mediaUrls.firstOrNull()
                ?: error("没有找到可用的 Spotify Canvas 动态封面")
            require(selected.isDirectMediaUrl()) { "Spotify Canvas 返回的资源不是可播放媒体" }
            Log.i(TAG, "match_selected provider=spotify_canvas type=${selected.mediaType()}")

            if (selected.isHlsUrl()) {
                selected
            } else {
                Uri.fromFile(download(context, selected)).toString()
            }
        }.onSuccess { uri ->
            Log.i(TAG, "import_success provider=spotify_canvas uri=${uri.take(160)}")
        }.onFailure { error ->
            Log.e(
                TAG,
                "import_failed provider=spotify_canvas type=${error.javaClass.simpleName} " +
                    "message=${error.message}",
                error,
            )
        }
    }

    /** Returns only Spotify candidates backed by an actual Canvas MP4 URL. */
    suspend fun searchCandidates(
        artist: String,
        album: String,
        title: String,
    ): Result<List<VideoCoverSearchCandidate>> = withContext(Dispatchers.IO) {
        runCatching {
            findAvailableCandidates(artist, album, title)
        }.onFailure { error ->
            Log.e(
                TAG,
                "preview_search_failed provider=spotify_canvas " +
                    "type=${error.javaClass.simpleName} message=${error.message}",
                error,
            )
        }
    }

    private fun findAvailableCandidates(
        artist: String,
        album: String,
        title: String,
    ): List<VideoCoverSearchCandidate> {
        val apiCandidates = runCatching {
            SpotifyCanvasApi.search(artist = artist, title = title, album = album)
                .map { it.toSearchCandidate() }
        }.onFailure { error ->
            Log.w(
                TAG,
                "spotify_api_unavailable type=${error.javaClass.simpleName} " +
                    "message=${error.message}",
            )
        }.getOrNull().orEmpty()
        if (apiCandidates.isNotEmpty()) return apiCandidates

        val liveCandidates = runCatching {
            findSpotifyCanvasCandidates(artist, album, title)
        }.onFailure { error ->
            Log.w(
                TAG,
                "spotify_canvas_unavailable type=${error.javaClass.simpleName} " +
                    "message=${error.message}",
            )
        }.getOrNull().orEmpty()
        if (liveCandidates.isNotEmpty()) return liveCandidates

        Log.i(TAG, "github_canvas_fallback reason=live_source_empty")
        return findGithubCanvasCandidates(artist, album, title)
    }

    private fun findTrackCanvas(trackId: String): List<String> {
        val apiMedia = runCatching { SpotifyCanvasApi.resolveTrack(trackId) }
            .onFailure { error ->
                Log.w(
                    TAG,
                    "spotify_api_track_unavailable track=$trackId " +
                        "type=${error.javaClass.simpleName} message=${error.message}",
                )
            }
            .getOrNull().orEmpty()
        if (apiMedia.isNotEmpty()) return apiMedia

        val liveMedia = runCatching {
            resolveSpotifyTrackCanvas("https://open.spotify.com/track/$trackId")
        }
            .onFailure { error ->
                Log.w(
                    TAG,
                    "spotify_canvas_track_unavailable track=$trackId " +
                        "type=${error.javaClass.simpleName} message=${error.message}",
                )
            }
            .getOrNull().orEmpty()
        if (liveMedia.isNotEmpty()) return liveMedia

        val cached = loadGithubCanvasEntries()
            .firstOrNull { it.trackId == trackId }
            ?.mediaUrl
            ?.let(::listOf)
            .orEmpty()
        if (cached.isNotEmpty()) {
            Log.i(TAG, "github_canvas_track_match track=$trackId")
        }
        return cached
    }

    private fun SpotifyCanvasApi.Match.toSearchCandidate(): VideoCoverSearchCandidate =
        VideoCoverSearchCandidate(
            id = id,
            title = title,
            artist = artist,
            album = album,
            artworkUrl = artworkUrl,
            selectionUrl = mediaUrl,
            score = score,
            provider = "Spotify Canvas API",
        )

    private fun findGithubCanvasCandidates(
        artist: String,
        album: String,
        title: String,
    ): List<VideoCoverSearchCandidate> {
        val entries = loadGithubCanvasEntries()
        val candidates = entries.mapNotNull { entry ->
            val titleScore = matchScore(title, entry.title)
            val artistScore = matchScore(artist, entry.artist)
            if (title.isNotBlank() && titleScore == 0) return@mapNotNull null
            if (artist.isNotBlank() && artistScore == 0) return@mapNotNull null
            val metadata = runCatching { querySpotifyOEmbed(entry.trackId) }
                .getOrNull()
            val score = titleScore * 8 + artistScore * 3 + matchScore(album, entry.title)
            VideoCoverSearchCandidate(
                id = entry.trackId,
                title = entry.title,
                artist = entry.artist,
                album = "GitHub Canvas Index",
                artworkUrl = metadata?.thumbnailUrl.orEmpty(),
                selectionUrl = entry.mediaUrl,
                score = score,
                provider = "GitHub Canvas Index",
            )
        }
            .sortedWith(compareByDescending<VideoCoverSearchCandidate> { it.score }.thenBy { it.title })
            .take(MAX_CANDIDATES)
        Log.i(TAG, "github_canvas_search_result count=${candidates.size}")
        candidates.forEachIndexed { index, candidate ->
            Log.d(
                TAG,
                "github_canvas_search_match rank=${index + 1} score=${candidate.score} " +
                    "title=${candidate.title.take(80)} track=${candidate.id}",
            )
        }
        return candidates
    }

    private fun loadGithubCanvasEntries(): List<GithubCanvasEntry> {
        githubDatasetCache?.let { cached ->
            if (System.currentTimeMillis() - cached.loadedAt < GITHUB_CACHE_TTL_MS) {
                return cached.entries
            }
        }
        synchronized(this) {
            githubDatasetCache?.let { cached ->
                if (System.currentTimeMillis() - cached.loadedAt < GITHUB_CACHE_TTL_MS) {
                    return cached.entries
                }
            }
            val dataset = runCatching {
                request(GITHUB_CANVAS_DATASET, provider = "github_canvas")
            }.getOrElse { error ->
                Log.w(
                    TAG,
                    "github_canvas_host_failed host=1 type=${error.javaClass.simpleName} " +
                        "message=${error.message}",
                )
                request(GITHUB_CANVAS_DATASET_FALLBACK, provider = "github_canvas")
            }
            val root = JSONObject(dataset)
            val array = root.optJSONArray("canvases") ?: return emptyList()
            val entries = buildList {
                for (index in 0 until array.length()) {
                    val item = array.optJSONObject(index) ?: continue
                    val trackId = item.optString("uri").spotifyTrackId() ?: continue
                    val mediaUrl = item.optString("canvas").trim()
                    if (!mediaUrl.isDirectMediaUrl()) continue
                    add(
                        GithubCanvasEntry(
                            trackId = trackId,
                            title = item.optString("title").trim(),
                            artist = item.optString("artist").trim(),
                            mediaUrl = mediaUrl,
                        ),
                    )
                }
            }
            githubDatasetCache = GithubCanvasDatasetCache(
                loadedAt = System.currentTimeMillis(),
                entries = entries,
            )
            Log.i(TAG, "github_canvas_dataset_loaded count=${entries.size}")
            return entries
        }
    }

    private fun findSpotifyCanvasCandidates(
        artist: String,
        album: String,
        title: String,
    ): List<VideoCoverSearchCandidate> {
        require(title.isNotBlank() || artist.isNotBlank()) { "歌曲标题或艺术家为空" }
        val artistResults = linkedMapOf<String, SpotifyArtist>()
        val artistQueries = linkedSetOf(
            artist,
            artist.substringBefore("[").trim(),
            artist.substringBeforeIgnoreCase(" feat").trim(),
            artist.substringBeforeIgnoreCase(" ft.").trim(),
        ).filter(String::isNotBlank)

        for (query in artistQueries) {
            Log.d(TAG, "spotify_canvas_artist_search query=${query.take(100)}")
            val root = JSONObject(requestCanvas("/api/search?q=${encode(query)}"))
            val results = root.optJSONArray("artists") ?: continue
            for (index in 0 until results.length()) {
                val item = results.optJSONObject(index) ?: continue
                val name = item.optString("name").trim()
                val slug = item.optString("slug").trim()
                if (name.isBlank() || slug.isBlank()) continue
                val score = matchScore(artist, name)
                val old = artistResults[slug]
                if (old == null || score > old.score) {
                    artistResults[slug] = SpotifyArtist(name, slug, score)
                }
            }
            if (artistResults.isNotEmpty()) break
        }

        val selectedArtists = artistResults.values
            .sortedByDescending(SpotifyArtist::score)
            .take(MAX_ARTIST_RESULTS)
        Log.i(TAG, "spotify_canvas_artist_matches count=${selectedArtists.size}")
        if (selectedArtists.isEmpty()) return emptyList()

        val candidates = linkedMapOf<String, VideoCoverSearchCandidate>()
        for (spotifyArtist in selectedArtists) {
            val tracks = findArtistCanvasTracks(spotifyArtist)
            for (track in tracks) {
                val metadata = runCatching { querySpotifyOEmbed(track.trackId) }
                    .onFailure { error ->
                        Log.d(
                            TAG,
                            "spotify_canvas_oembed_failed track=${track.trackId} " +
                                "type=${error.javaClass.simpleName} message=${error.message}",
                        )
                    }
                    .getOrNull()
                    ?: continue
                val titleScore = matchScore(title, metadata.title)
                val artistScore = maxOf(
                    matchScore(artist, spotifyArtist.name),
                    spotifyArtist.score,
                )
                val albumScore = matchScore(album, metadata.title)
                Log.d(
                    TAG,
                    "spotify_canvas_candidate_metadata track=${track.trackId} " +
                        "title=${metadata.title.take(100)} titleScore=$titleScore " +
                        "artistScore=$artistScore",
                )
                if (title.isNotBlank() && titleScore == 0) continue
                val score = titleScore * 8 + artistScore * 3 + albumScore
                val candidate = VideoCoverSearchCandidate(
                    id = track.trackId,
                    title = metadata.title,
                    artist = spotifyArtist.name,
                    album = "Spotify Canvas",
                    artworkUrl = metadata.thumbnailUrl,
                    selectionUrl = track.mediaUrl,
                    score = score,
                    provider = "Spotify Canvas",
                )
                val old = candidates[candidate.id]
                if (old == null || candidate.score > old.score) {
                    candidates[candidate.id] = candidate
                }
            }
        }

        val sorted = candidates.values
            .sortedWith(compareByDescending<VideoCoverSearchCandidate> { it.score }.thenBy { it.title })
            .take(MAX_CANDIDATES)
        Log.i(TAG, "preview_search_result provider=spotify_canvas count=${sorted.size}")
        sorted.forEachIndexed { index, candidate ->
            Log.d(
                TAG,
                "preview_search_match provider=spotify_canvas rank=${index + 1} " +
                    "score=${candidate.score} title=${candidate.title.take(80)} " +
                    "track=${candidate.id} media=${candidate.selectionUrl.take(180)}",
            )
        }
        return sorted
    }

    private fun findArtistCanvasTracks(artist: SpotifyArtist): List<SpotifyCanvasTrack> {
        val tracks = linkedMapOf<String, SpotifyCanvasTrack>()
        for (page in 1..MAX_ARTIST_PAGES) {
            val html = runCatching {
                requestCanvas(
                    "/artists/${encodePath(artist.slug)}?page=$page",
                    "text/html,application/xhtml+xml",
                )
            }
                .onFailure { error ->
                    Log.w(
                        TAG,
                        "spotify_canvas_artist_page_failed artist=${artist.name.take(80)} page=$page " +
                            "type=${error.javaClass.simpleName} message=${error.message}",
                    )
                }
                .getOrNull()
                ?: break
            val mediaUrls = CANVAS_MEDIA_REGEX.findAll(html)
                .map { unescapeHtml(it.groupValues[1]).substringBefore('#') }
                .filter { it.isDirectMediaUrl() }
                .distinct()
                .toList()
            val trackIds = TRACK_LINK_REGEX.findAll(html)
                .map { it.groupValues[1] }
                .toList()
            val count = minOf(mediaUrls.size, trackIds.size)
            for (index in 0 until count) {
                val trackId = trackIds[index]
                val mediaUrl = mediaUrls[index].substringBefore('#')
                if (trackId.isBlank() || !mediaUrl.isDirectMediaUrl()) continue
                tracks.putIfAbsent(trackId, SpotifyCanvasTrack(trackId, mediaUrl))
            }
            if (tracks.size >= MAX_TRACKS_PER_ARTIST || count == 0) break
        }
        Log.i(
            TAG,
            "spotify_canvas_artist_tracks artist=${artist.name.take(80)} count=${tracks.size}",
        )
        return tracks.values.take(MAX_TRACKS_PER_ARTIST)
    }

    private fun resolveSpotifyTrackCanvas(source: String): List<String> {
        val trackId = source.spotifyTrackId() ?: return emptyList()
        val normalizedUrl = "https://open.spotify.com/track/$trackId"
        Log.i(TAG, "spotify_canvas_track_lookup track=$trackId")
        val html = requestCanvas(
            "/canvas?link=${encode(normalizedUrl)}",
            "text/html,application/xhtml+xml",
        )
        val media = CANVAS_MEDIA_REGEX.findAll(html)
            .map { unescapeHtml(it.groupValues[1]).substringBefore('#') }
            .filter { it.isDirectMediaUrl() }
            .distinct()
            .toList()
        Log.i(TAG, "spotify_canvas_track_result track=$trackId count=${media.size}")
        return media
    }

    private fun querySpotifyOEmbed(trackId: String): SpotifyTrackMetadata {
        val trackUrl = "https://open.spotify.com/track/$trackId"
        val endpoint = "$SPOTIFY_OEMBED?url=${encode(trackUrl)}"
        val root = JSONObject(request(endpoint))
        val title = root.optString("title").trim()
        require(title.isNotBlank()) { "Spotify oEmbed 未返回歌曲标题" }
        return SpotifyTrackMetadata(
            title = title,
            thumbnailUrl = root.optString("thumbnail_url").trim(),
        )
    }

    private fun requestCanvas(path: String, accept: String = "application/json"): String {
        var lastError: Throwable? = null
        for ((index, base) in listOf(CANVAS_BASE, CANVAS_FALLBACK_BASE).withIndex()) {
            val endpoint = "$base$path"
            try {
                return request(endpoint, accept)
            } catch (error: Throwable) {
                lastError = error
                Log.w(
                    TAG,
                    "spotify_canvas_host_failed host=${index + 1} " +
                        "type=${error.javaClass.simpleName} message=${error.message}",
                )
            }
        }
        throw lastError ?: error("Spotify Canvas 查询失败")
    }

    private fun request(
        endpoint: String,
        accept: String = "application/json",
        userAgent: String = DEFAULT_USER_AGENT,
        provider: String = "spotify_canvas",
    ): String {
        var lastError: Throwable? = null
        repeat(3) { attempt ->
            try {
                Log.d(TAG, "http_start provider=$provider attempt=${attempt + 1} url=${endpoint.take(220)}")
                val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    setRequestProperty("Accept", accept)
                    setRequestProperty("User-Agent", userAgent)
                    setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
                    setRequestProperty("Cache-Control", "no-cache")
                    setRequestProperty("Connection", "close")
                    instanceFollowRedirects = true
                }
                return connection.useAndRead { inputStream.bufferedReader().use { it.readText() } }
            } catch (error: Throwable) {
                lastError = error
                Log.w(
                    TAG,
                    "http_failed provider=$provider attempt=${attempt + 1} " +
                        "type=${error.javaClass.simpleName} message=${error.message}",
                    error,
                )
                if (attempt < 2) Thread.sleep(700L * (attempt + 1))
            }
        }
        throw lastError ?: error("Spotify Canvas 查询失败")
    }

    private fun download(context: Context, source: String): File {
        val directory = File(context.filesDir, "video_covers_v1").apply { mkdirs() }
        require(directory.isDirectory) { "无法创建动态封面目录" }
        val extension = when {
            source.contains(".webp", ignoreCase = true) -> "webp"
            source.contains(".webm", ignoreCase = true) -> "webm"
            else -> "mp4"
        }
        val target = File(directory, "${sha256(source)}.$extension")
        if (target.isFile && target.length() > 0L) return target
        val temporary = File(directory, "${target.name}.part")
        val connection = (URL(source).openConnection() as HttpURLConnection).apply {
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("Connection", "close")
        }
        try {
            require(connection.responseCode in 200..299) {
                "动态封面下载失败：HTTP ${connection.responseCode}"
            }
            require(connection.contentLengthLong <= 0L || connection.contentLengthLong <= MAX_BYTES) {
                "动态封面超过 96 MB，已拒绝下载"
            }
            BufferedInputStream(connection.inputStream).use { input ->
                FileOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        require(total <= MAX_BYTES) { "动态封面超过 96 MB，已拒绝下载" }
                        output.write(buffer, 0, count)
                    }
                }
            }
            require(temporary.length() > 0L) { "动态封面为空" }
            if (!temporary.renameTo(target)) {
                temporary.copyTo(target, overwrite = true)
                temporary.delete()
            }
            return target
        } finally {
            connection.disconnect()
            if (temporary.exists() && temporary.length() == 0L) temporary.delete()
        }
    }

    private fun matchScore(expected: String, actual: String): Int {
        if (expected.isBlank() || actual.isBlank()) return 0
        var best = 0
        for (left in matchVariants(expected)) {
            for (right in matchVariants(actual)) {
                if (left.isBlank() || right.isBlank()) continue
                best = maxOf(
                    best,
                    when {
                        left == right -> 100
                        left.contains(right) || right.contains(left) -> 70
                        else -> 0
                    },
                )
            }
        }
        return best
    }

    /** Match the recording title before feature/remix/release annotations. */
    private fun matchVariants(value: String): List<String> {
        val normalized = normalizeMatchText(value)
        val base = value
            .substringBeforeIgnoreCase(" feat")
            .substringBeforeIgnoreCase(" ft.")
            .substringBeforeIgnoreCase(" with ")
            .substringBefore('(')
            .substringBefore('[')
            .substringBefore(" - ")
            .substringBefore(" – ")
        return linkedSetOf(normalized, normalizeMatchText(base))
            .filter(String::isNotBlank)
    }

    private fun normalizeMatchText(value: String): String = Normalizer
        .normalize(value, Normalizer.Form.NFKC)
        .lowercase()
        .filter { it.isLetterOrDigit() }

    private fun String.substringBeforeIgnoreCase(delimiter: String): String {
        val index = lowercase().indexOf(delimiter.lowercase())
        return if (index >= 0) substring(0, index) else this
    }

    private fun String.isSpotifyTrackUrl(): Boolean = spotifyTrackId() != null

    private fun String.spotifyTrackId(): String? = SPOTIFY_TRACK_REGEX
        .find(this)
        ?.groupValues
        ?.getOrNull(1)

    private fun String.isDirectMediaUrl(): Boolean {
        val lower = lowercase()
        return (startsWith("http://") || startsWith("https://")) &&
            (lower.contains(".m3u8") || lower.contains(".mp4") ||
                lower.contains(".webp") || lower.contains(".webm"))
    }

    private fun String.isHlsUrl(): Boolean = lowercase().contains(".m3u8")

    private fun String.mediaType(): String = when {
        isHlsUrl() -> "hls"
        lowercase().contains(".webp") -> "webp"
        lowercase().contains(".webm") -> "webm"
        else -> "mp4"
    }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun encodePath(value: String): String = encode(value).replace("+", "%20")

    private fun unescapeHtml(value: String): String = value
        .replace("&amp;", "&")
        .replace("&quot;", "\"")
        .replace("&#x27;", "'")

    private fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    private const val GITHUB_CACHE_TTL_MS = 6L * 60L * 60L * 1000L

    @Volatile
    private var githubDatasetCache: GithubCanvasDatasetCache? = null

    private inline fun <T> HttpURLConnection.useAndRead(block: HttpURLConnection.() -> T): T {
        try {
            Log.d(TAG, "http_response provider=spotify_canvas code=$responseCode contentLength=$contentLengthLong")
            require(responseCode in 200..299) { "Spotify Canvas 查询失败：HTTP $responseCode" }
            return block()
        } finally {
            disconnect()
        }
    }

    private data class SpotifyArtist(
        val name: String,
        val slug: String,
        val score: Int,
    )

    private data class SpotifyCanvasTrack(
        val trackId: String,
        val mediaUrl: String,
    )

    private data class SpotifyTrackMetadata(
        val title: String,
        val thumbnailUrl: String,
    )

    private data class GithubCanvasEntry(
        val trackId: String,
        val title: String,
        val artist: String,
        val mediaUrl: String,
    )

    private data class GithubCanvasDatasetCache(
        val loadedAt: Long,
        val entries: List<GithubCanvasEntry>,
    )

    private val SPOTIFY_TRACK_REGEX = Regex(
        "(?:open\\.spotify\\.com/(?:intl-[^/]+/)?track/|spotify:track:)([A-Za-z0-9]+)",
        RegexOption.IGNORE_CASE,
    )
    private val CANVAS_MEDIA_REGEX = Regex(
        "(?:src|saveFile)\\s*(?:=|\\()\\s*[\\\"'](https://canvaz\\.scdn\\.co/[^\\\"'<>\\s]+\\.mp4(?:#[^\\\"'<>\\s]+)?)",
        RegexOption.IGNORE_CASE,
    )
    private val TRACK_LINK_REGEX = Regex(
        "href\\s*=\\s*[\\\"']spotify:track:([A-Za-z0-9]+)[\\\"']",
        RegexOption.IGNORE_CASE,
    )
}
