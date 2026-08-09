package com.rawsmusic.module.data.source.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceLyric
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceMediaType
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import android.os.SystemClock
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.InstalledMusicSource
import java.net.URI
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Main-process Binder/Messenger client for [MusicSourceRuntimeService]. */
object MusicSourceRuntimeClient {
    private const val BIND_TIMEOUT_MS = 5_000L
    private const val SEARCH_TIMEOUT_MS = 15_000L
    private const val RESOLVE_TIMEOUT_MS = 22_000L
    private const val LYRIC_TIMEOUT_MS = 18_000L

    private val requestIds = AtomicLong(1L)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<RuntimeResponse>>()
    private val bindMutex = Mutex()
    private val responseMessenger = Messenger(ResponseHandler(Looper.getMainLooper()))

    @Volatile
    private var remote: Messenger? = null
    @Volatile
    private var bindWaiter: CompletableDeferred<Messenger>? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val messenger = service?.let(::Messenger)
            if (messenger == null) {
                bindWaiter?.completeExceptionally(IllegalStateException("音源运行服务连接无效"))
                bindWaiter = null
                return
            }
            remote = messenger
            AppLogger.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_BOUND component=${name?.flattenToShortString().orEmpty()}")
            bindWaiter?.complete(messenger)
            bindWaiter = null
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            remote = null
            AppLogger.w(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_DISCONNECTED component=${name?.flattenToShortString().orEmpty()}")
            failPending("音源运行服务已断开")
        }

        override fun onBindingDied(name: ComponentName?) {
            remote = null
            AppLogger.e(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_BINDING_DIED component=${name?.flattenToShortString().orEmpty()}")
            bindWaiter?.completeExceptionally(IllegalStateException("音源运行服务绑定失效"))
            bindWaiter = null
            failPending("音源运行服务异常退出")
        }

        override fun onNullBinding(name: ComponentName?) {
            remote = null
            AppLogger.e(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_NULL_BINDING component=${name?.flattenToShortString().orEmpty()}")
            bindWaiter?.completeExceptionally(IllegalStateException("音源运行服务拒绝绑定"))
            bindWaiter = null
        }
    }

    suspend fun search(
        context: Context,
        source: InstalledMusicSource,
        query: String,
        page: Int = 1,
        type: RawSourceMediaType = RawSourceMediaType.Music,
    ): MusicSourceSearchGroup {
        val response = callRuntime(
            context = context,
            action = MusicSourceRuntimeWire.ACTION_SEARCH,
            timeoutMs = SEARCH_TIMEOUT_MS,
            timeoutMessage = "音源搜索超时",
        ) {
            putSource(source)
            putString(MusicSourceRuntimeWire.KEY_QUERY, query)
            putInt(MusicSourceRuntimeWire.KEY_PAGE, page.coerceAtLeast(1))
            putString(MusicSourceRuntimeWire.KEY_MEDIA_TYPE, type.toMusicFreeWireType())
        }
        if (!response.success) throw IllegalStateException(response.error.ifBlank { "音源搜索失败" })
        return parseSearchPayload(source, response.payload)
    }

    suspend fun resolveAudio(
        context: Context,
        source: InstalledMusicSource,
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource {
        require(item.sourceId == source.id) { "歌曲与音源不匹配" }
        val itemPayload = buildItemPayload(item)
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲原始数据超过运行器限制"
        }
        val response = callRuntime(
            context = context,
            action = MusicSourceRuntimeWire.ACTION_RESOLVE_AUDIO,
            timeoutMs = RESOLVE_TIMEOUT_MS,
            timeoutMessage = "播放地址解析超时",
        ) {
            putSource(source)
            putString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD, itemPayload)
            putString(MusicSourceRuntimeWire.KEY_QUALITY, quality.toMusicFreeWireQuality())
        }
        if (!response.success) throw IllegalStateException(response.error.ifBlank { "播放地址解析失败" })
        return parseResolvedAudio(response.payload, quality)
    }

    suspend fun getLyric(
        context: Context,
        source: InstalledMusicSource,
        item: RawSourceMediaItem,
    ): RawSourceLyric {
        require(item.sourceId == source.id) { "歌曲与音源不匹配" }
        val itemPayload = buildItemPayload(item)
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲原始数据超过运行器限制"
        }
        val response = callRuntime(
            context = context,
            action = MusicSourceRuntimeWire.ACTION_GET_LYRIC,
            timeoutMs = LYRIC_TIMEOUT_MS,
            timeoutMessage = "歌词获取超时",
        ) {
            putSource(source)
            putString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD, itemPayload)
        }
        if (!response.success) throw IllegalStateException(response.error.ifBlank { "歌词获取失败" })
        return parseLyric(response.payload)
    }


    suspend fun resolveLxAudio(
        context: Context,
        source: InstalledLxSource,
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource {
        require(item.sourceId == source.id) { "歌曲与 LX 解析源不匹配" }
        if (source.format.equals("renderApi", ignoreCase = true)) {
            return resolveLxRenderApi(source, item, quality)
        }
        val itemPayload = buildItemPayload(item)
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲原始数据超过运行器限制"
        }
        val response = callRuntime(
            context = context,
            action = MusicSourceRuntimeWire.ACTION_LX_RESOLVE_AUDIO,
            timeoutMs = RESOLVE_TIMEOUT_MS,
            timeoutMessage = "LX 播放地址解析超时",
        ) {
            putLxSource(source)
            putString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD, itemPayload)
            putString(MusicSourceRuntimeWire.KEY_QUALITY, quality.toLxWireQuality())
        }
        if (!response.success) throw IllegalStateException(response.error.ifBlank { "LX 播放地址解析失败" })
        return parseResolvedAudio(response.payload, quality)
    }

    suspend fun getLxLyric(
        context: Context,
        source: InstalledLxSource,
        item: RawSourceMediaItem,
    ): RawSourceLyric {
        require(item.sourceId == source.id) { "歌曲与 LX 解析源不匹配" }
        require(!source.format.equals("renderApi", ignoreCase = true)) { "当前 LX Render API 不提供歌词" }
        val itemPayload = buildItemPayload(item)
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲原始数据超过运行器限制"
        }
        val response = callRuntime(
            context = context,
            action = MusicSourceRuntimeWire.ACTION_LX_GET_LYRIC,
            timeoutMs = LYRIC_TIMEOUT_MS,
            timeoutMessage = "LX 歌词获取超时",
        ) {
            putLxSource(source)
            putString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD, itemPayload)
        }
        if (!response.success) throw IllegalStateException(response.error.ifBlank { "LX 歌词获取失败" })
        return parseLyric(response.payload)
    }

    private suspend fun callRuntime(
        context: Context,
        action: Int,
        timeoutMs: Long,
        timeoutMessage: String,
        fill: Bundle.() -> Unit,
    ): RuntimeResponse {
        val bindStartedAt = SystemClock.elapsedRealtime()
        val messenger = ensureBound(context.applicationContext)
        val requestId = requestIds.getAndIncrement()
        val deferred = CompletableDeferred<RuntimeResponse>()
        pending[requestId] = deferred
        val message = Message.obtain(null, action).apply {
            replyTo = responseMessenger
            data = Bundle().apply {
                putLong(MusicSourceRuntimeWire.KEY_REQUEST_ID, requestId)
                fill()
            }
        }
        val actionLabel = actionName(action)
        val startedAt = SystemClock.elapsedRealtime()
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_CALL_START id=$requestId action=$actionLabel " +
                "sourceId=${message.data.getString(MusicSourceRuntimeWire.KEY_SOURCE_ID).orEmpty()} " +
                "quality=${message.data.getString(MusicSourceRuntimeWire.KEY_QUALITY).orEmpty()} " +
                "bindMs=${startedAt - bindStartedAt} timeoutMs=$timeoutMs"
        )
        try {
            messenger.send(message)
            val response = withTimeout(timeoutMs) { deferred.await() }
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_CALL_END id=$requestId action=$actionLabel " +
                    "success=${response.success} payloadBytes=${response.payload.toByteArray(Charsets.UTF_8).size} " +
                    "error=${response.error.replace('\n', ' ').replace('\r', ' ').take(320)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            return response
        } catch (timeout: TimeoutCancellationException) {
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_CALL_TIMEOUT id=$requestId action=$actionLabel " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                timeout,
            )
            throw IllegalStateException(timeoutMessage, timeout)
        } catch (remoteError: RemoteException) {
            remote = null
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_CALL_REMOTE_FAIL id=$requestId action=$actionLabel " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                remoteError,
            )
            throw IllegalStateException("无法调用音源运行服务", remoteError)
        } finally {
            pending.remove(requestId)
        }
    }

    private fun Bundle.putSource(source: InstalledMusicSource) {
        putString(MusicSourceRuntimeWire.KEY_SCRIPT_PATH, source.scriptPath)
        putString(MusicSourceRuntimeWire.KEY_SCRIPT_SHA256, source.scriptSha256)
        putString(MusicSourceRuntimeWire.KEY_SOURCE_ID, source.id)
        putString(MusicSourceRuntimeWire.KEY_SOURCE_NAME, source.name)
    }


    private fun Bundle.putLxSource(source: InstalledLxSource) {
        putString(MusicSourceRuntimeWire.KEY_SCRIPT_PATH, source.scriptPath)
        putString(MusicSourceRuntimeWire.KEY_SCRIPT_SHA256, source.scriptSha256)
        putString(MusicSourceRuntimeWire.KEY_SOURCE_ID, source.id)
        putString(MusicSourceRuntimeWire.KEY_SOURCE_NAME, source.name)
    }

    private suspend fun ensureBound(context: Context): Messenger {
        remote?.let { return it }
        val waiter = bindMutex.withLock {
            remote?.let { connected ->
                return@withLock CompletableDeferred<Messenger>().apply { complete(connected) }
            }
            bindWaiter?.let { return@withLock it }
            CompletableDeferred<Messenger>().also { created ->
                bindWaiter = created
                AppLogger.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_BIND_START")
                val bound = withContext(Dispatchers.Main.immediate) {
                    context.bindService(
                        Intent(context, MusicSourceRuntimeService::class.java),
                        connection,
                        Context.BIND_AUTO_CREATE,
                    )
                }
                if (!bound) {
                    bindWaiter = null
                    AppLogger.e(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_BIND_FAIL")
                    created.completeExceptionally(IllegalStateException("无法启动音源运行服务"))
                }
            }
        }
        return withTimeout(BIND_TIMEOUT_MS) { waiter.await() }
    }


    private suspend fun resolveLxRenderApi(
        source: InstalledLxSource,
        item: RawSourceMediaItem,
        quality: RawSourceQuality,
    ): RawResolvedAudioSource = withContext(Dispatchers.IO) {
        val script = java.io.File(source.scriptPath).readText(Charsets.UTF_8)
        val apiUrl = Regex("""API_URL\s*=\s*['"]([^'"]+)['"]""").find(script)
            ?.groupValues?.getOrNull(1)?.trimEnd('/')
            ?: throw IllegalStateException("LX Render API 缺少 API_URL")
        val apiKey = Regex("""API_KEY\s*=\s*['"]([^'"]+)['"]""").find(script)
            ?.groupValues?.getOrNull(1)
            ?: throw IllegalStateException("LX Render API 缺少 API_KEY")
        val raw = runCatching { JsonParser.parseString(item.sourcePayload).asJsonObject }.getOrDefault(JsonObject())
        val platform = raw.stringOrEmpty("source").ifBlank { raw.stringOrEmpty("platform") }
        val songMid = raw.stringOrEmpty("songmid").ifBlank { item.remoteId.substringAfter(':', item.remoteId) }
        require(platform.isNotBlank() && songMid.isNotBlank()) { "LX 搜索结果缺少平台或歌曲 ID" }
        val wireQuality = quality.toLxWireQuality()
        val url = "$apiUrl/url/$platform/$songMid/$wireQuality"
        val startedAt = SystemClock.elapsedRealtime()
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} LX_RENDER_START sourceId=${source.id} quality=$quality " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(url)}"
        )
        val connection = URI(url).toURL().openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 22_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("User-Agent", "lx-music-mobile/1.0.0")
        connection.setRequestProperty("X-Request-Key", apiKey)
        try {
            val status = connection.responseCode
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} LX_RENDER_HTTP status=$status " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            require(status in 200..299) { "LX Render API 解析失败：HTTP $status" }
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            val root = JsonParser.parseString(body).asJsonObject
            val code = root.get("code")?.asInt ?: -1
            if (code != 0) {
                val message = root.stringOrEmpty("msg").ifBlank {
                    when (code) {
                        1 -> "LX Render API IP 受限"
                        2 -> "LX Render API 获取地址失败"
                        4 -> "LX Render API 内部错误"
                        5 -> "LX Render API 请求过于频繁"
                        6 -> "LX Render API 参数错误"
                        else -> "LX Render API 解析失败"
                    }
                }
                throw IllegalStateException(message)
            }
            val playableUrl = root.stringOrEmpty("url")
            require(playableUrl.startsWith("http://") || playableUrl.startsWith("https://")) {
                "LX Render API 没有返回有效播放地址"
            }
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} LX_RENDER_OK quality=$quality " +
                    "url=${OnlinePlaybackDiagnostics.safeUrl(playableUrl)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
            RawResolvedAudioSource(url = playableUrl, quality = quality)
        } catch (error: Throwable) {
            AppLogger.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} LX_RENDER_FAIL quality=$quality " +
                    "error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                error,
            )
            throw error
        } finally {
            connection.disconnect()
        }
    }

    private fun RawSourceQuality.toLxWireQuality(): String = when (this) {
        RawSourceQuality.Standard -> "128k"
        RawSourceQuality.High, RawSourceQuality.Super -> "320k"
        RawSourceQuality.Lossless -> "flac"
        RawSourceQuality.HiRes -> "flac24bit"
    }

    private fun parseSearchPayload(
        source: InstalledMusicSource,
        payload: String,
    ): MusicSourceSearchGroup {
        val root = runCatching { JsonParser.parseString(payload).asJsonObject }
            .getOrElse { throw IllegalStateException("音源返回了无效搜索数据", it) }
        val items = root.arrayOrEmpty("items").mapNotNull { element ->
            val item = element.asObjectOrNull() ?: return@mapNotNull null
            val remoteId = item.stringOrEmpty("id").trim()
            val title = item.stringOrEmpty("title").trim()
            if (remoteId.isBlank() || title.isBlank()) return@mapNotNull null
            RawSourceMediaItem(
                sourceId = source.id,
                remoteId = remoteId.take(512),
                mediaType = item.stringOrEmpty("type").toRawMediaType(),
                title = title.take(512),
                artists = item.stringOrEmpty("artist")
                    .split('/', '、', '&')
                    .map(String::trim)
                    .filter(String::isNotBlank)
                    .take(16),
                album = item.stringOrEmpty("album").take(512),
                durationMs = (item.doubleOrZero("durationSeconds") * 1_000.0)
                    .toLong()
                    .coerceIn(0L, 24L * 60L * 60L * 1_000L),
                artworkUrl = item.stringOrEmpty("artwork").take(4_096),
                availableQualities = item.arrayOrEmpty("qualityKeys")
                    .mapNotNull { it.stringOrNull() }
                    .mapTo(linkedSetOf(), RawSourceQuality::fromKey)
                    .ifEmpty { linkedSetOf(RawSourceQuality.Standard) },
                sourcePayload = item.stringOrEmpty("rawPayload")
                    .take(MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES / 4),
            )
        }
        return MusicSourceSearchGroup(
            sourceId = source.id,
            sourceName = source.name,
            items = items,
            isEnd = root.booleanOrDefault("isEnd", true),
        )
    }

    private fun parseResolvedAudio(
        payload: String,
        requestedQuality: RawSourceQuality,
    ): RawResolvedAudioSource {
        val root = runCatching { JsonParser.parseString(payload).asJsonObject }
            .getOrElse { throw IllegalStateException("音源返回了无效播放地址", it) }
        val url = root.stringOrEmpty("url").trim()
        require(url.isNotBlank()) { "音源没有返回播放地址" }
        val uri = runCatching { URI(url) }
            .getOrElse { throw IllegalStateException("音源返回的播放地址无效", it) }
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) {
            "当前在线播放仅接受 HTTP/HTTPS 地址"
        }
        require(!uri.host.isNullOrBlank()) { "播放地址缺少主机名" }
        val headers = root.objectOrNull("headers")
            ?.entrySet()
            ?.mapNotNull { (key, value) ->
                val normalizedKey = key.trim().take(128)
                val normalizedValue = value.stringOrNull()?.trim()?.take(8_192).orEmpty()
                if (normalizedKey.isBlank() || normalizedValue.isBlank()) null else normalizedKey to normalizedValue
            }
            ?.take(64)
            ?.toMap(linkedMapOf())
            .orEmpty()
        val resolvedQuality = RawSourceQuality.fromKey(root.stringOrEmpty("quality"))
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} RESOLVED_PAYLOAD quality=${root.stringOrEmpty("quality").ifBlank { requestedQuality.name }} " +
                "headers=${OnlinePlaybackDiagnostics.headerNames(headers)} " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(uri.toASCIIString())}"
        )
        return RawResolvedAudioSource(
            url = uri.toASCIIString().take(8_192),
            headers = headers,
            userAgent = headers.entries.firstOrNull { it.key.equals("user-agent", true) }?.value,
            quality = if (root.stringOrEmpty("quality").isBlank()) requestedQuality else resolvedQuality,
        )
    }

    private fun parseLyric(payload: String): RawSourceLyric {
        val root = runCatching { JsonParser.parseString(payload).asJsonObject }
            .getOrElse { throw IllegalStateException("音源返回了无效歌词数据", it) }
        return RawSourceLyric(
            original = root.stringOrEmpty("original").take(MusicSourceRuntimeWire.MAX_RUNTIME_RESPONSE_BYTES / 2),
            translation = root.stringOrEmpty("translation").take(MusicSourceRuntimeWire.MAX_RUNTIME_RESPONSE_BYTES / 4),
            romanization = root.stringOrEmpty("romanization").take(MusicSourceRuntimeWire.MAX_RUNTIME_RESPONSE_BYTES / 4),
            wordByWord = root.stringOrEmpty("wordByWord").take(MusicSourceRuntimeWire.MAX_RUNTIME_RESPONSE_BYTES / 4),
        )
    }

    private fun buildItemPayload(item: RawSourceMediaItem): String = JsonObject().apply {
        addProperty("sourceId", item.sourceId)
        addProperty("remoteId", item.remoteId)
        addProperty("type", item.mediaType.toMusicFreeWireType())
        addProperty("title", item.title)
        add("artists", JsonArray().also { array -> item.artists.forEach(array::add) })
        addProperty("album", item.album)
        addProperty("durationMs", item.durationMs)
        addProperty("artworkUrl", item.artworkUrl)
        add("availableQualities", JsonArray().also { array ->
            item.availableQualities.forEach { array.add(it.toMusicFreeWireQuality()) }
        })
        addProperty(
            "rawPayload",
            item.sourcePayload.take(MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES / 4),
        )
    }.toString()

    private fun JsonElement.asObjectOrNull(): JsonObject? =
        takeIf { it.isJsonObject }?.asJsonObject

    private fun JsonObject.objectOrNull(name: String): JsonObject? =
        get(name)?.asObjectOrNull()

    private fun JsonObject.arrayOrEmpty(name: String): List<JsonElement> =
        get(name)?.takeIf { it.isJsonArray }?.asJsonArray?.toList().orEmpty()

    private fun JsonObject.stringOrEmpty(name: String): String =
        get(name)?.stringOrNull().orEmpty()

    private fun JsonElement.stringOrNull(): String? = runCatching {
        takeUnless { isJsonNull }
            ?.takeIf { isJsonPrimitive }
            ?.asString
    }.getOrNull()

    private fun JsonObject.doubleOrZero(name: String): Double = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asDouble ?: 0.0
    }.getOrDefault(0.0)

    private fun JsonObject.booleanOrDefault(name: String, default: Boolean): Boolean = runCatching {
        get(name)?.takeUnless { it.isJsonNull }?.asBoolean ?: default
    }.getOrDefault(default)

    private fun String?.toRawMediaType(): RawSourceMediaType = when (this?.lowercase()) {
        "album" -> RawSourceMediaType.Album
        "artist" -> RawSourceMediaType.Artist
        "sheet", "playlist" -> RawSourceMediaType.Playlist
        else -> RawSourceMediaType.Music
    }

    private fun RawSourceMediaType.toMusicFreeWireType(): String = when (this) {
        RawSourceMediaType.Music -> "music"
        RawSourceMediaType.Album -> "album"
        RawSourceMediaType.Artist -> "artist"
        RawSourceMediaType.Playlist -> "sheet"
    }

    private fun RawSourceQuality.toMusicFreeWireQuality(): String = when (this) {
        RawSourceQuality.Standard -> "standard"
        RawSourceQuality.High -> "320k"
        RawSourceQuality.Super -> "super"
        RawSourceQuality.Lossless -> "flac"
        RawSourceQuality.HiRes -> "flac24bit"
    }

    private fun failPending(message: String) {
        pending.values.forEach { deferred ->
            deferred.completeExceptionally(IllegalStateException(message))
        }
        pending.clear()
    }

    private class ResponseHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (message.what != MusicSourceRuntimeWire.ACTION_RESPONSE) {
                super.handleMessage(message)
                return
            }
            val requestId = message.data.getLong(MusicSourceRuntimeWire.KEY_REQUEST_ID)
            pending.remove(requestId)?.complete(
                RuntimeResponse(
                    success = message.data.getBoolean(MusicSourceRuntimeWire.KEY_SUCCESS),
                    payload = message.data.getString(MusicSourceRuntimeWire.KEY_PAYLOAD).orEmpty(),
                    error = message.data.getString(MusicSourceRuntimeWire.KEY_ERROR).orEmpty(),
                )
            )
        }
    }

    private data class RuntimeResponse(
        val success: Boolean,
        val payload: String,
        val error: String,
    )

    private fun actionName(action: Int): String = when (action) {
        MusicSourceRuntimeWire.ACTION_SEARCH -> "search"
        MusicSourceRuntimeWire.ACTION_RESOLVE_AUDIO -> "resolve_audio"
        MusicSourceRuntimeWire.ACTION_GET_LYRIC -> "get_lyric"
        MusicSourceRuntimeWire.ACTION_LX_RESOLVE_AUDIO -> "lx_resolve_audio"
        MusicSourceRuntimeWire.ACTION_LX_GET_LYRIC -> "lx_get_lyric"
        else -> "unknown_$action"
    }

    private const val TAG = "MusicSourceRuntime"

}
