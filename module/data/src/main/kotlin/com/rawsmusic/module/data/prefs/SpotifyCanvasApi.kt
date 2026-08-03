package com.rawsmusic.module.data.prefs

import android.util.Log
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/**
 * Small direct client for Spotify's public web Canvas endpoint.
 *
 * The endpoint returns protobuf rather than JSON, so this file keeps the wire format local and
 * avoids adding a large protobuf runtime only for one request/response pair.
 */
internal object SpotifyCanvasApi {
    private const val TAG = "VideoCoverRemote"
    private const val TOKEN_URL = "https://open.spotify.com/get_access_token?reason=transport"
    private const val SEARCH_URL = "https://api.spotify.com/v1/search"
    private val CANVAS_HOSTS = listOf(
        "https://gew1-spclient.spotify.com/canvaz-cache/v0/canvases",
        "https://gue1-spclient.spotify.com/canvaz-cache/v0/canvases",
    )
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 45_000
    private const val MAX_TRACKS = 12

    @Volatile
    private var cachedToken: AccessToken? = null

    fun search(artist: String, title: String, album: String): List<Match> {
        val token = accessToken()
        val query = buildString {
            if (artist.isNotBlank()) append("artist:$artist ")
            if (title.isNotBlank()) append("track:$title ")
            if (album.isNotBlank()) append("album:$album")
        }.trim()
        if (query.isBlank()) return emptyList()

        val endpoint = "$SEARCH_URL?q=${encode(query)}&type=track&limit=20&market=US"
        val root = JSONObject(requestText(endpoint, token = token, provider = "spotify_search"))
        val items = root.optJSONObject("tracks")?.optJSONArray("items") ?: return emptyList()
        val tracks = buildList {
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val id = item.optString("id").trim()
                val name = item.optString("name").trim()
                val itemAlbum = item.optJSONObject("album")?.optString("name").orEmpty().trim()
                val itemArtist = item.optJSONArray("artists")
                    ?.let { artists ->
                        (0 until artists.length())
                            .mapNotNull { artists.optJSONObject(it)?.optString("name")?.trim() }
                            .filter(String::isNotBlank)
                            .joinToString(", ")
                    }
                    .orEmpty()
                if (id.isBlank() || name.isBlank() || itemArtist.isBlank()) continue
                val artwork = item.optJSONObject("album")
                    ?.optJSONArray("images")
                    ?.optJSONObject(0)
                    ?.optString("url")
                    .orEmpty()
                val score = score(title, name) * 8 + score(artist, itemArtist) * 3 + score(album, itemAlbum)
                if (title.isNotBlank() && score(title, name) == 0) continue
                if (artist.isNotBlank() && score(artist, itemArtist) == 0) continue
                add(Match(id, name, itemArtist, itemAlbum, artwork, score))
            }
        }
            .sortedWith(compareByDescending<Match> { it.score }.thenBy { it.title })
            .take(MAX_TRACKS)

        val withCanvas = buildList {
            tracks.forEach { track ->
                val media = runCatching { resolveTrack(track.id, token) }
                    .onFailure { error ->
                        Log.d(
                            TAG,
                            "spotify_api_canvas_failed track=${track.id} " +
                                "type=${error.javaClass.simpleName} message=${error.message}",
                        )
                    }
                    .getOrNull()
                    .orEmpty()
                media.forEach { url ->
                    add(track.copy(mediaUrl = url))
                }
            }
        }
        Log.i(TAG, "spotify_api_search_result tracks=${tracks.size} canvases=${withCanvas.size}")
        return withCanvas
    }

    fun resolveTrack(trackId: String): List<String> {
        return resolveTrack(trackId, accessToken())
    }

    private fun resolveTrack(trackId: String, token: String): List<String> {
        require(trackId.isNotBlank()) { "Spotify track ID is empty" }
        val body = encodeRequest(trackId)
        var lastError: Throwable? = null
        for ((index, endpoint) in CANVAS_HOSTS.withIndex()) {
            try {
                Log.d(TAG, "spotify_api_canvas_request host=${index + 1} track=$trackId")
                val response = requestBinary(endpoint, body, token)
                val media = decodeResponse(response)
                    .mapNotNull { it.url.ifBlank { it.canvasUri }.takeIf(::isDirectMediaUrl) }
                    .distinct()
                Log.i(
                    TAG,
                    "spotify_api_canvas_result host=${index + 1} track=$trackId count=${media.size}",
                )
                if (media.isNotEmpty()) return media
            } catch (error: Throwable) {
                lastError = error
                Log.w(
                    TAG,
                    "spotify_api_canvas_host_failed host=${index + 1} track=$trackId " +
                        "type=${error.javaClass.simpleName} message=${error.message}",
                )
            }
        }
        throw lastError ?: IllegalStateException("Spotify Canvas response is empty")
    }

    private fun accessToken(): String {
        cachedToken?.let { cached ->
            if (cached.expiresAtMs > System.currentTimeMillis()) return cached.value
        }
        synchronized(this) {
            cachedToken?.let { cached ->
                if (cached.expiresAtMs > System.currentTimeMillis()) return cached.value
            }
            val root = JSONObject(requestText(TOKEN_URL, provider = "spotify_token"))
            val token = root.optString("accessToken").trim()
            require(token.isNotBlank()) { "Spotify web token is empty" }
            val expiresInMs = root.optLong("accessTokenExpirationTimestampMs", 0L)
                .takeIf { it > System.currentTimeMillis() }
                ?: (System.currentTimeMillis() + 5 * 60 * 1000L)
            cachedToken = AccessToken(token, expiresInMs - 30_000L)
            Log.d(TAG, "spotify_token_ready expiresAt=$expiresInMs")
            return token
        }
    }

    private fun requestText(
        endpoint: String,
        token: String? = null,
        provider: String,
    ): String {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            setRequestProperty("Connection", "close")
            if (!token.isNullOrBlank()) setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            Log.d(TAG, "spotify_api_http_start provider=$provider url=${endpoint.take(220)}")
            require(connection.responseCode in 200..299) {
                "Spotify API HTTP ${connection.responseCode}"
            }
            Log.d(TAG, "spotify_api_http_response provider=$provider code=${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun requestBinary(endpoint: String, body: ByteArray, token: String): ByteArray {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/x-protobuf")
            setRequestProperty("Content-Type", "application/x-protobuf")
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("User-Agent", DEFAULT_USER_AGENT)
            setRequestProperty("Connection", "close")
        }
        try {
            connection.outputStream.use { it.write(body) }
            require(connection.responseCode in 200..299) {
                "Spotify Canvas HTTP ${connection.responseCode}"
            }
            return connection.inputStream.use { readAll(it) }
        } finally {
            connection.disconnect()
        }
    }

    private fun encodeRequest(trackId: String): ByteArray {
        val entity = encodeStringField(1, "spotify:track:$trackId")
        return encodeBytesField(1, entity)
    }

    private fun decodeResponse(data: ByteArray): List<Canvas> {
        val reader = ProtoReader(data)
        val result = mutableListOf<Canvas>()
        while (reader.hasRemaining()) {
            val tag = reader.readVarint().toInt()
            val field = tag ushr 3
            val wireType = tag and 7
            if (field == 1 && wireType == 2) {
                result += decodeCanvas(reader.readBytes())
            } else {
                reader.skip(wireType)
            }
        }
        return result
    }

    private fun decodeCanvas(data: ByteArray): Canvas {
        val reader = ProtoReader(data)
        var url = ""
        var canvasUri = ""
        while (reader.hasRemaining()) {
            val tag = reader.readVarint().toInt()
            when (tag ushr 3) {
                2 -> if ((tag and 7) == 2) url = reader.readString() else reader.skip(tag and 7)
                11 -> if ((tag and 7) == 2) canvasUri = reader.readString() else reader.skip(tag and 7)
                else -> reader.skip(tag and 7)
            }
        }
        return Canvas(url, canvasUri)
    }

    private fun encodeStringField(field: Int, value: String): ByteArray =
        encodeBytesField(field, value.toByteArray(Charsets.UTF_8))

    private fun encodeBytesField(field: Int, value: ByteArray): ByteArray =
        concat(encodeVarint((field shl 3 or 2).toLong()), encodeVarint(value.size.toLong()), value)

    private fun encodeVarint(value: Long): ByteArray {
        var current = value
        val output = ByteArrayOutputStream()
        do {
            var next = (current and 0x7f).toInt()
            current = current ushr 7
            if (current != 0L) next = next or 0x80
            output.write(next)
        } while (current != 0L)
        return output.toByteArray()
    }

    private fun concat(vararg parts: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        parts.forEach { output.write(it) }
        return output.toByteArray()
    }

    private fun readAll(input: InputStream): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    private fun score(expected: String, actual: String): Int {
        if (expected.isBlank() || actual.isBlank()) return 0
        val left = normalize(expected)
        val right = normalize(actual)
        return when {
            left == right -> 100
            left.contains(right) || right.contains(left) -> 70
            else -> 0
        }
    }

    private fun normalize(value: String): String = value
        .lowercase(Locale.ROOT)
        .substringBefore(" feat")
        .substringBefore(" ft.")
        .substringBefore(" with ")
        .filter(Char::isLetterOrDigit)

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

    private fun isDirectMediaUrl(value: String): Boolean {
        val lower = value.lowercase(Locale.ROOT)
        return lower.startsWith("https://") &&
            (lower.contains(".mp4") || lower.contains(".webm") || lower.contains(".webp"))
    }

    private data class AccessToken(val value: String, val expiresAtMs: Long)

    data class Match(
        val id: String,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUrl: String,
        val score: Int,
        val mediaUrl: String = "",
    )

    private data class Canvas(val url: String, val canvasUri: String)

    private class ProtoReader(private val data: ByteArray) {
        private var position = 0

        fun hasRemaining(): Boolean = position < data.size

        fun readVarint(): Long {
            var value = 0L
            var shift = 0
            while (position < data.size && shift < 64) {
                val byte = data[position++].toInt() and 0xff
                value = value or ((byte and 0x7f).toLong() shl shift)
                if ((byte and 0x80) == 0) return value
                shift += 7
            }
            throw IllegalStateException("Invalid protobuf varint")
        }

        fun readBytes(): ByteArray {
            val length = readVarint().toInt()
            require(length >= 0 && position + length <= data.size) { "Invalid protobuf length" }
            return data.copyOfRange(position, position + length).also { position += length }
        }

        fun readString(): String = readBytes().toString(Charsets.UTF_8)

        fun skip(wireType: Int) {
            when (wireType) {
                0 -> readVarint()
                1 -> position = (position + 8).coerceAtMost(data.size)
                2 -> readBytes()
                5 -> position = (position + 4).coerceAtMost(data.size)
                else -> throw IllegalStateException("Unsupported protobuf wire type $wireType")
            }
        }
    }

    private const val DEFAULT_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"
}
