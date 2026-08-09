package com.rawsmusic.module.data.source.runtime

import android.annotation.SuppressLint
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.RemoteException
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.rawsmusic.core.common.source.lx.LxSourceScriptParser
import com.rawsmusic.core.common.source.musicfree.MusicFreePluginScriptParser
import android.util.Log
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import android.os.SystemClock
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.min

/**
 * Dedicated-process JavaScript host for imported MusicFree sources.
 *
 * The WebView never joins the app UI hierarchy. Direct WebView networking, file access,
 * content access and window creation are disabled. Imported scripts can only use the small
 * CommonJS environment and the host-proxied axios-compatible bridge defined below.
 */
class MusicSourceRuntimeService : Service() {
    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val incoming = Messenger(IncomingHandler(Looper.getMainLooper()))
    private val evaluationCallbacks = ConcurrentHashMap<String, (Result<String>) -> Unit>()
    private lateinit var webView: WebView
    private var runtimeReady = false
    private val queuedMessages = ArrayDeque<Message>()
    private val mountedScripts = ConcurrentHashMap<String, String>()
    private val mountedLxScripts = ConcurrentHashMap<String, String>()

    override fun onBind(intent: Intent?) = incoming.binder

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_SERVICE_CREATE pid=${android.os.Process.myPid()}")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { WebView.setDataDirectorySuffix("music_source_runtime") }
        }
        createRuntimeWebView()
    }

    @SuppressLint("SetJavaScriptEnabled", "AddJavascriptInterface")
    private fun createRuntimeWebView() {
        runtimeReady = false
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.domStorageEnabled = false
            settings.databaseEnabled = false
            settings.setGeolocationEnabled(false)
            settings.javaScriptCanOpenWindowsAutomatically = false
            settings.setSupportMultipleWindows(false)
            settings.mediaPlaybackRequiresUserGesture = true
            settings.blockNetworkLoads = true
            addJavascriptInterface(RuntimeBridge(), "RawSourceHost")
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(
                    view: WebView?,
                    request: WebResourceRequest?,
                ): WebResourceResponse? = blockedResponse()

                @Deprecated("Deprecated in Java")
                override fun shouldInterceptRequest(view: WebView?, url: String?): WebResourceResponse? =
                    blockedResponse()

                override fun onPageFinished(view: WebView?, url: String?) {
                    runtimeReady = true
                    Log.i(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_SERVICE_READY queued=${queuedMessages.size}"
                    )
                    while (queuedMessages.isNotEmpty()) {
                        handleRequest(queuedMessages.removeFirst())
                    }
                }

                override fun onRenderProcessGone(
                    view: WebView?,
                    detail: RenderProcessGoneDetail?,
                ): Boolean {
                    runtimeReady = false
                    Log.e(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_RENDERER_GONE " +
                            "didCrash=${detail?.didCrash()} priority=${detail?.rendererPriorityAtExit()}"
                    )
                    mountedScripts.clear()
                    mountedLxScripts.clear()
                    evaluationCallbacks.values.forEach {
                        it(Result.failure(IllegalStateException("音源 JavaScript 渲染进程已重启")))
                    }
                    evaluationCallbacks.clear()
                    view?.removeJavascriptInterface("RawSourceHost")
                    view?.destroy()
                    mainHandler.post { createRuntimeWebView() }
                    return true
                }
            }
            loadDataWithBaseURL(
                "https://rawsmusic.invalid/",
                RUNTIME_HTML,
                "text/html",
                "UTF-8",
                null,
            )
        }
    }

    override fun onDestroy() {
        Log.w(TAG, "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_SERVICE_DESTROY pending=${evaluationCallbacks.size}")
        queuedMessages.clear()
        mountedScripts.clear()
        mountedLxScripts.clear()
        evaluationCallbacks.values.forEach { it(Result.failure(IllegalStateException("音源运行服务已关闭"))) }
        evaluationCallbacks.clear()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("RawSourceHost")
            webView.stopLoading()
            webView.destroy()
        }
        super.onDestroy()
    }

    private inner class IncomingHandler(looper: Looper) : Handler(looper) {
        override fun handleMessage(message: Message) {
            if (
                message.what != MusicSourceRuntimeWire.ACTION_SEARCH &&
                message.what != MusicSourceRuntimeWire.ACTION_RESOLVE_AUDIO &&
                message.what != MusicSourceRuntimeWire.ACTION_GET_LYRIC &&
                message.what != MusicSourceRuntimeWire.ACTION_LX_RESOLVE_AUDIO &&
                message.what != MusicSourceRuntimeWire.ACTION_LX_GET_LYRIC
            ) {
                super.handleMessage(message)
                return
            }
            val copied = Message.obtain(message).apply {
                replyTo = message.replyTo
                data = Bundle(message.data)
            }
            if (!runtimeReady) {
                queuedMessages.add(copied)
                Log.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_QUEUE id=${copied.data.getLong(MusicSourceRuntimeWire.KEY_REQUEST_ID)} " +
                        "action=${actionName(copied.what)} queued=${queuedMessages.size}"
                )
            } else {
                handleRequest(copied)
            }
        }
    }

    private fun handleRequest(message: Message) {
        val requestId = message.data.getLong(MusicSourceRuntimeWire.KEY_REQUEST_ID)
        val replyTo = message.replyTo
        val startedAt = SystemClock.elapsedRealtime()
        val actionLabel = actionName(message.what)
        Log.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_REQUEST_START id=$requestId action=$actionLabel " +
                "sourceId=${message.data.getString(MusicSourceRuntimeWire.KEY_SOURCE_ID).orEmpty()} " +
                "quality=${message.data.getString(MusicSourceRuntimeWire.KEY_QUALITY).orEmpty()}"
        )
        runCatching {
            val sourceId = message.data.getString(MusicSourceRuntimeWire.KEY_SOURCE_ID).orEmpty()
            val sourceName = message.data.getString(MusicSourceRuntimeWire.KEY_SOURCE_NAME).orEmpty()
            val scriptPath = message.data.getString(MusicSourceRuntimeWire.KEY_SCRIPT_PATH).orEmpty()
            val scriptSha = message.data.getString(MusicSourceRuntimeWire.KEY_SCRIPT_SHA256).orEmpty()

            require(sourceId.isNotBlank()) { "音源 ID 为空" }
            require(sourceName.isNotBlank()) { "音源名称为空" }
            require(scriptSha.isNotBlank()) { "音源脚本哈希为空" }
            val isLxAction = message.what == MusicSourceRuntimeWire.ACTION_LX_RESOLVE_AUDIO ||
                message.what == MusicSourceRuntimeWire.ACTION_LX_GET_LYRIC
            val script = readApprovedScript(scriptPath, isLx = isLxAction)
            val preparedScript = if (isLxAction) script else normalizeModuleSyntax(script)
            val mount = if (isLxAction) ::mountLxIfNeeded else ::mountIfNeeded

            mount(sourceId, sourceName, scriptSha, preparedScript) { mountResult ->
                mountResult.fold(
                    onSuccess = {
                        when (message.what) {
                            MusicSourceRuntimeWire.ACTION_SEARCH -> invokeSearch(
                                sourceId = sourceId,
                                sourceName = sourceName,
                                query = message.data.getString(MusicSourceRuntimeWire.KEY_QUERY).orEmpty(),
                                page = message.data.getInt(MusicSourceRuntimeWire.KEY_PAGE, 1).coerceAtLeast(1),
                                mediaType = message.data
                                    .getString(MusicSourceRuntimeWire.KEY_MEDIA_TYPE)
                                    .orEmpty()
                                    .ifBlank { "music" },
                                requestId = requestId,
                                replyTo = replyTo,
                            )
                            MusicSourceRuntimeWire.ACTION_RESOLVE_AUDIO -> invokeResolveAudio(
                                sourceId = sourceId,
                                sourceName = sourceName,
                                itemPayload = message.data
                                    .getString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD)
                                    .orEmpty(),
                                quality = message.data
                                    .getString(MusicSourceRuntimeWire.KEY_QUALITY)
                                    .orEmpty()
                                    .ifBlank { "standard" },
                                requestId = requestId,
                                replyTo = replyTo,
                            )
                            MusicSourceRuntimeWire.ACTION_GET_LYRIC -> invokeGetLyric(
                                sourceId = sourceId,
                                sourceName = sourceName,
                                itemPayload = message.data
                                    .getString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD)
                                    .orEmpty(),
                                requestId = requestId,
                                replyTo = replyTo,
                            )
                            MusicSourceRuntimeWire.ACTION_LX_RESOLVE_AUDIO -> invokeLxResolveAudio(
                                sourceId = sourceId,
                                sourceName = sourceName,
                                itemPayload = message.data
                                    .getString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD)
                                    .orEmpty(),
                                quality = message.data
                                    .getString(MusicSourceRuntimeWire.KEY_QUALITY)
                                    .orEmpty()
                                    .ifBlank { "128k" },
                                requestId = requestId,
                                replyTo = replyTo,
                            )
                            MusicSourceRuntimeWire.ACTION_LX_GET_LYRIC -> invokeLxGetLyric(
                                sourceId = sourceId,
                                sourceName = sourceName,
                                itemPayload = message.data
                                    .getString(MusicSourceRuntimeWire.KEY_ITEM_PAYLOAD)
                                    .orEmpty(),
                                requestId = requestId,
                                replyTo = replyTo,
                            )
                        }
                    },
                    onFailure = { error ->
                        Log.e(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_REQUEST_FAIL id=$requestId action=$actionLabel " +
                                "stage=mount error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                            error,
                        )
                        sendFailure(replyTo, requestId, error.message ?: "音源挂载失败")
                    },
                )
            }
        }.onFailure { error ->
            Log.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_REQUEST_FAIL id=$requestId action=$actionLabel " +
                    "stage=dispatch error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                error,
            )
            sendFailure(replyTo, requestId, error.message ?: "音源运行失败")
        }
    }

    private fun mountIfNeeded(
        sourceId: String,
        sourceName: String,
        scriptSha: String,
        script: String,
        onReady: (Result<Unit>) -> Unit,
    ) {
        if (mountedScripts[sourceId] == scriptSha) {
            onReady(Result.success(Unit))
            return
        }
        val js = "window.__rawMusicSource.mount(" +
            jsString(sourceId) + "," + jsString(scriptSha) + "," + jsString(script) + ")"
        evaluateStringResult(js) { result ->
            onReady(
                result.map {
                    mountedScripts[sourceId] = scriptSha
                    Unit
                }
            )
        }
    }


    private fun mountLxIfNeeded(
        sourceId: String,
        sourceName: String,
        scriptSha: String,
        script: String,
        onReady: (Result<Unit>) -> Unit,
    ) {
        if (mountedLxScripts[sourceId] == scriptSha) {
            onReady(Result.success(Unit))
            return
        }
        val js = "window.__rawLxSource.mount(" +
            jsString(sourceId) + "," + jsString(sourceName) + "," +
            jsString(scriptSha) + "," + jsString(script) + ")"
        evaluateStringResult(js) { result ->
            onReady(
                result.map {
                    mountedLxScripts[sourceId] = scriptSha
                    Unit
                }
            )
        }
    }

    private fun invokeSearch(
        sourceId: String,
        sourceName: String,
        query: String,
        page: Int,
        mediaType: String,
        requestId: Long,
        replyTo: Messenger?,
    ) {
        val js = "window.__rawMusicSource.search(" +
            jsString(sourceId) + "," +
            jsString(sourceName) + "," +
            jsString(query) + "," +
            page + "," +
            jsString(mediaType) + ")"
        evaluateStringResult(js) { result ->
            result.fold(
                onSuccess = { payload -> sendSuccess(replyTo, requestId, payload) },
                onFailure = { error -> sendFailure(replyTo, requestId, error.message ?: "音源搜索失败") },
            )
        }
    }

    private fun invokeResolveAudio(
        sourceId: String,
        sourceName: String,
        itemPayload: String,
        quality: String,
        requestId: Long,
        replyTo: Messenger?,
    ) {
        require(itemPayload.isNotBlank()) { "歌曲数据为空" }
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲数据超过运行器限制"
        }
        val js = "window.__rawMusicSource.resolveAudio(" +
            jsString(sourceId) + "," +
            jsString(sourceName) + "," +
            jsString(itemPayload) + "," +
            jsString(quality) + ")"
        val startedAt = SystemClock.elapsedRealtime()
        evaluateStringResult(js) { result ->
            result.fold(
                onSuccess = { payload ->
                    Log.i(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_RESOLVE_OK id=$requestId backend=musicfree " +
                            "sourceId=$sourceId quality=$quality payloadBytes=${payload.toByteArray(Charsets.UTF_8).size} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    sendSuccess(replyTo, requestId, payload)
                },
                onFailure = { error ->
                    Log.e(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_RESOLVE_FAIL id=$requestId backend=musicfree " +
                            "sourceId=$sourceId quality=$quality error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                        error,
                    )
                    sendFailure(replyTo, requestId, error.message ?: "播放地址解析失败")
                },
            )
        }
    }

    private fun invokeGetLyric(
        sourceId: String,
        sourceName: String,
        itemPayload: String,
        requestId: Long,
        replyTo: Messenger?,
    ) {
        require(itemPayload.isNotBlank()) { "歌曲数据为空" }
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲数据超过运行器限制"
        }
        val js = "window.__rawMusicSource.getLyric(" +
            jsString(sourceId) + "," +
            jsString(sourceName) + "," +
            jsString(itemPayload) + ")"
        evaluateStringResult(js) { result ->
            result.fold(
                onSuccess = { payload -> sendSuccess(replyTo, requestId, payload) },
                onFailure = { error -> sendFailure(replyTo, requestId, error.message ?: "歌词获取失败") },
            )
        }
    }


    private fun invokeLxResolveAudio(
        sourceId: String,
        sourceName: String,
        itemPayload: String,
        quality: String,
        requestId: Long,
        replyTo: Messenger?,
    ) {
        require(itemPayload.isNotBlank()) { "歌曲数据为空" }
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲数据超过运行器限制"
        }
        val js = "window.__rawLxSource.resolveAudio(" +
            jsString(sourceId) + "," + jsString(sourceName) + "," +
            jsString(itemPayload) + "," + jsString(quality) + ")"
        val startedAt = SystemClock.elapsedRealtime()
        evaluateStringResult(js) { result ->
            result.fold(
                onSuccess = { payload ->
                    Log.i(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_RESOLVE_OK id=$requestId backend=lx " +
                            "sourceId=$sourceId quality=$quality payloadBytes=${payload.toByteArray(Charsets.UTF_8).size} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
                    )
                    sendSuccess(replyTo, requestId, payload)
                },
                onFailure = { error ->
                    Log.e(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_RESOLVE_FAIL id=$requestId backend=lx " +
                            "sourceId=$sourceId quality=$quality error=${OnlinePlaybackDiagnostics.errorSummary(error)} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
                        error,
                    )
                    sendFailure(replyTo, requestId, error.message ?: "LX 播放地址解析失败")
                },
            )
        }
    }

    private fun invokeLxGetLyric(
        sourceId: String,
        sourceName: String,
        itemPayload: String,
        requestId: Long,
        replyTo: Messenger?,
    ) {
        require(itemPayload.isNotBlank()) { "歌曲数据为空" }
        require(itemPayload.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_ITEM_BYTES) {
            "歌曲数据超过运行器限制"
        }
        val js = "window.__rawLxSource.getLyric(" +
            jsString(sourceId) + "," + jsString(sourceName) + "," + jsString(itemPayload) + ")"
        evaluateStringResult(js) { result ->
            result.fold(
                onSuccess = { payload -> sendSuccess(replyTo, requestId, payload) },
                onFailure = { error -> sendFailure(replyTo, requestId, error.message ?: "LX 歌词获取失败") },
            )
        }
    }

    private fun evaluateStringResult(
        script: String,
        callback: (Result<String>) -> Unit,
    ) {
        val evaluationId = UUID.randomUUID().toString()
        evaluationCallbacks[evaluationId] = callback
        mainHandler.postDelayed({
            evaluationCallbacks.remove(evaluationId)?.invoke(
                Result.failure(IllegalStateException("JavaScript 执行超时"))
            )
        }, 28_000L)
        val wrapped = "Promise.resolve(" + script + ")" +
            ".then(function(value){RawSourceHost.complete(" + jsString(evaluationId) + ",String(value));})" +
            ".catch(function(error){RawSourceHost.complete(" + jsString(evaluationId) + ",JSON.stringify({ok:false,error:String(error&&error.message||error)}));});"
        webView.evaluateJavascript(wrapped, null)
    }

    private fun finishEvaluation(evaluationId: String, encoded: String) {
        val callback = evaluationCallbacks.remove(evaluationId) ?: return
        val result = runCatching {
            require(encoded.toByteArray(Charsets.UTF_8).size <= MusicSourceRuntimeWire.MAX_RUNTIME_RESPONSE_BYTES) {
                "音源返回数据超过 2 MiB 限制"
            }
            val envelope = JsonParser.parseString(encoded).asJsonObject
            if (!envelope.get("ok")?.asBoolean.orFalse()) {
                throw IllegalStateException(
                    envelope.get("error")?.asString?.take(1_024).orEmpty().ifBlank { "音源脚本执行失败" }
                )
            }
            envelope.get("payload")?.toString() ?: "{}"
        }
        result.exceptionOrNull()?.let { error ->
            Log.e(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_JS_FAIL evaluationId=${evaluationId.take(12)} " +
                    "error=${OnlinePlaybackDiagnostics.errorSummary(error)}",
                error,
            )
        }
        callback(result)
    }

    private fun sendSuccess(replyTo: Messenger?, requestId: Long, payload: String) {
        sendResponse(replyTo, requestId, success = true, payload = payload, error = "")
    }

    private fun sendFailure(replyTo: Messenger?, requestId: Long, error: String) {
        sendResponse(replyTo, requestId, success = false, payload = "", error = error.take(1_024))
    }

    private fun sendResponse(
        replyTo: Messenger?,
        requestId: Long,
        success: Boolean,
        payload: String,
        error: String,
    ) {
        if (replyTo == null) return
        val response = Message.obtain(null, MusicSourceRuntimeWire.ACTION_RESPONSE).apply {
            data = Bundle().apply {
                putLong(MusicSourceRuntimeWire.KEY_REQUEST_ID, requestId)
                putBoolean(MusicSourceRuntimeWire.KEY_SUCCESS, success)
                putString(MusicSourceRuntimeWire.KEY_PAYLOAD, payload)
                putString(MusicSourceRuntimeWire.KEY_ERROR, error)
            }
        }
        try {
            replyTo.send(response)
        } catch (_: RemoteException) {
        }
    }

    private fun readApprovedScript(path: String, isLx: Boolean): String {
        val root = File(filesDir, if (isLx) "music_sources/lx" else "music_sources/musicfree").canonicalFile
        val file = File(path).canonicalFile
        require(file.path.startsWith(root.path + File.separator)) { "拒绝读取音源目录之外的文件" }
        require(file.isFile) { "音源脚本不存在" }
        val maxBytes = if (isLx) LxSourceScriptParser.MAX_SCRIPT_BYTES else MusicFreePluginScriptParser.MAX_SCRIPT_BYTES
        require(file.length() in 1..maxBytes.toLong()) { "音源脚本大小无效" }
        return file.readText(Charsets.UTF_8)
    }

    private fun normalizeModuleSyntax(script: String): String {
        val trimmed = script.removePrefix("\uFEFF")
        return if (Regex("""\bexport\s+default\b""").containsMatchIn(trimmed)) {
            trimmed.replaceFirst(Regex("""\bexport\s+default\b"""), "module.exports =")
        } else {
            trimmed
        }
    }

    private fun jsString(value: String): String = gson.toJson(value)

    private fun blockedResponse(): WebResourceResponse = WebResourceResponse(
        "text/plain",
        "UTF-8",
        403,
        "Blocked",
        emptyMap(),
        "Direct network access is disabled".byteInputStream(),
    )

    private fun Boolean?.orFalse(): Boolean = this == true

    private inner class RuntimeBridge {
        @JavascriptInterface
        fun complete(evaluationId: String, encoded: String) {
            mainHandler.post { finishEvaluation(evaluationId, encoded) }
        }

        @JavascriptInterface
        fun http(requestJson: String): String {
            return runCatching { executeHttp(requestJson) }
                .fold(
                    onSuccess = { gson.toJson(mapOf("ok" to true, "response" to it)) },
                    onFailure = {
                        Log.e(
                            TAG,
                            "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_HTTP_FAIL error=${OnlinePlaybackDiagnostics.errorSummary(it)}",
                            it,
                        )
                        gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "网络请求失败").take(1_024)))
                    },
                )
        }

        @JavascriptInterface
        fun crypto(requestJson: String): String {
            return runCatching { executeCrypto(requestJson) }
                .fold(
                    onSuccess = { gson.toJson(mapOf("ok" to true, "output" to it)) },
                    onFailure = { gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "加密操作失败").take(1_024))) },
                )
        }

        @JavascriptInterface
        fun zlib(requestJson: String): String {
            return runCatching { executeZlib(requestJson) }
                .fold(
                    onSuccess = { gson.toJson(mapOf("ok" to true, "output" to it)) },
                    onFailure = { gson.toJson(mapOf("ok" to false, "error" to (it.message ?: "压缩操作失败").take(1_024))) },
                )
        }
    }

    private fun executeZlib(requestJson: String): String {
        val request = JsonParser.parseString(requestJson).asJsonObject
        val operation = request.get("operation")?.asString.orEmpty().lowercase()
        val input = Base64.decode(request.get("data")?.asString.orEmpty(), Base64.DEFAULT)
        require(input.size <= 4 * 1024 * 1024) { "压缩输入超过 4 MiB 限制" }
        val output = when (operation) {
            "inflate" -> inflateBytes(input, nowrap = false)
            "inflateraw" -> inflateBytes(input, nowrap = true)
            "ungzip", "unzip" -> GZIPInputStream(ByteArrayInputStream(input)).use { it.readBoundedBytes() }
            "deflate" -> deflateBytes(input, nowrap = false, request.get("level")?.asInt ?: Deflater.DEFAULT_COMPRESSION)
            "deflateraw" -> deflateBytes(input, nowrap = true, request.get("level")?.asInt ?: Deflater.DEFAULT_COMPRESSION)
            "gzip" -> ByteArrayOutputStream().use { outputStream ->
                GZIPOutputStream(outputStream).use { it.write(input) }
                outputStream.toByteArray()
            }
            else -> throw IllegalArgumentException("不支持的 pako 操作：$operation")
        }
        require(output.size <= 8 * 1024 * 1024) { "压缩结果超过 8 MiB 限制" }
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    private fun inflateBytes(input: ByteArray, nowrap: Boolean): ByteArray =
        InflaterInputStream(ByteArrayInputStream(input), Inflater(nowrap)).use { it.readBoundedBytes() }

    private fun deflateBytes(input: ByteArray, nowrap: Boolean, level: Int): ByteArray =
        ByteArrayOutputStream().use { output ->
            DeflaterOutputStream(output, Deflater(level.coerceIn(Deflater.DEFAULT_COMPRESSION, Deflater.BEST_COMPRESSION), nowrap)).use {
                it.write(input)
            }
            output.toByteArray()
        }

    private fun java.io.InputStream.readBoundedBytes(): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(16 * 1024)
        while (true) {
            val count = read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size() <= 8 * 1024 * 1024) { "解压结果超过 8 MiB 限制" }
        }
        return output.toByteArray()
    }

    private fun executeCrypto(requestJson: String): String {
        val request = JsonParser.parseString(requestJson).asJsonObject
        val operation = request.get("operation")?.asString.orEmpty()
        val output = when (operation) {
            "digest" -> {
                val algorithm = request.get("algorithm")?.asString.orEmpty()
                require(algorithm in setOf("MD5", "SHA-1", "SHA-256", "SHA-512")) {
                    "不支持的摘要算法：$algorithm"
                }
                MessageDigest.getInstance(algorithm).digest(request.decodeBytes("data"))
            }
            "hmac" -> {
                val algorithm = request.get("algorithm")?.asString.orEmpty()
                require(algorithm in setOf("HmacMD5", "HmacSHA1", "HmacSHA256", "HmacSHA512")) {
                    "不支持的 HMAC 算法：$algorithm"
                }
                val key = request.decodeBytes("key")
                require(key.isNotEmpty() && key.size <= 4_096) { "HMAC 密钥大小无效" }
                Mac.getInstance(algorithm).run {
                    init(SecretKeySpec(key, algorithm))
                    doFinal(request.decodeBytes("data"))
                }
            }
            "random" -> {
                val size = request.get("size")?.asInt?.coerceIn(0, 64 * 1024) ?: 0
                ByteArray(size).also(SecureRandom()::nextBytes)
            }
            "cipher" -> executeCipher(request)
            "rsa" -> executeRsa(request)
            else -> throw IllegalArgumentException("不支持的加密操作：$operation")
        }
        require(output.size <= 4 * 1024 * 1024) { "加密结果超过 4 MiB 限制" }
        return Base64.encodeToString(output, Base64.NO_WRAP)
    }

    private fun executeRsa(request: com.google.gson.JsonObject): ByteArray {
        val keyBytes = request.decodeBytes("key")
        require(keyBytes.isNotEmpty() && keyBytes.size <= 16 * 1024) { "RSA 公钥大小无效" }
        val publicKey = KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(keyBytes))
        val padding = request.get("padding")?.asString.orEmpty().uppercase()
        val transformation = when (padding) {
            "OAEP", "OAEPWITHSHA1ANDMGF1PADDING" -> "RSA/ECB/OAEPWithSHA-1AndMGF1Padding"
            else -> "RSA/ECB/NoPadding"
        }
        val cipher = Cipher.getInstance(transformation)
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)
        return cipher.doFinal(request.decodeBytes("data"))
    }

    private fun executeCipher(request: com.google.gson.JsonObject): ByteArray {
        val algorithm = request.get("algorithm")?.asString.orEmpty().uppercase()
        val mode = request.get("mode")?.asString.orEmpty().ifBlank { "CBC" }.uppercase()
        val padding = request.get("padding")?.asString.orEmpty().ifBlank { "PKCS7" }.uppercase()
        require(algorithm in setOf("AES", "DES", "DESEDE")) { "不支持的对称算法：$algorithm" }
        require(mode in setOf("CBC", "ECB", "CFB", "CTR", "OFB")) { "不支持的分组模式：$mode" }
        require(padding in setOf("PKCS7", "NOPADDING")) { "不支持的填充方式：$padding" }
        val keyAlgorithm = if (algorithm == "DESEDE") "DESede" else algorithm
        var key = request.decodeBytes("key")
        if (algorithm == "DESEDE" && key.size == 16) {
            key = key + key.copyOfRange(0, 8)
        }
        when (algorithm) {
            "AES" -> require(key.size in setOf(16, 24, 32)) { "AES 密钥必须为 16、24 或 32 字节" }
            "DES" -> require(key.size == 8) { "DES 密钥必须为 8 字节" }
            "DESEDE" -> require(key.size == 24) { "TripleDES 密钥必须为 16 或 24 字节" }
        }
        val javaPadding = if (padding == "PKCS7") "PKCS5Padding" else "NoPadding"
        val transformation = "$keyAlgorithm/$mode/$javaPadding"
        val cipher = Cipher.getInstance(transformation)
        val secretKey = SecretKeySpec(key, keyAlgorithm)
        val cipherMode = if (request.get("encrypt")?.asBoolean != false) Cipher.ENCRYPT_MODE else Cipher.DECRYPT_MODE
        if (mode == "ECB") {
            cipher.init(cipherMode, secretKey)
        } else {
            val iv = request.decodeBytes("iv")
            val expectedIvSize = if (algorithm == "AES") 16 else 8
            require(iv.size == expectedIvSize) { "$algorithm/$mode 的 IV 必须为 $expectedIvSize 字节" }
            cipher.init(cipherMode, secretKey, IvParameterSpec(iv))
        }
        val data = request.decodeBytes("data")
        require(data.size <= 4 * 1024 * 1024) { "加密输入超过 4 MiB 限制" }
        return cipher.doFinal(data)
    }

    private fun com.google.gson.JsonObject.decodeBytes(name: String): ByteArray {
        val encoded = get(name)?.takeUnless { it.isJsonNull }?.asString.orEmpty()
        if (encoded.isEmpty()) return ByteArray(0)
        return Base64.decode(encoded, Base64.DEFAULT)
    }

    private fun executeHttp(requestJson: String): Map<String, Any?> {
        val request = JsonParser.parseString(requestJson).asJsonObject
        var currentUri = URI(request.get("url")?.asString.orEmpty())
        val method = request.get("method")?.asString.orEmpty().ifBlank { "GET" }.uppercase()
        require(method in setOf("GET", "POST", "PUT", "PATCH", "DELETE", "HEAD")) { "不支持的请求方法：$method" }
        val timeout = request.get("timeout")?.asInt?.coerceIn(1_000, 60_000) ?: 15_000
        val headers = linkedMapOf<String, String>()
        request.getAsJsonObject("headers")?.entrySet()?.forEach { (name, value) ->
            if (name.equals("host", true) || name.equals("content-length", true) || name.equals("connection", true)) return@forEach
            headers[name.take(128)] = value.asString.take(8_192)
        }
        val body = request.get("body")?.takeUnless { it.isJsonNull }?.let { element ->
            if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else element.toString()
        }
        val requestStartedAt = SystemClock.elapsedRealtime()
        Log.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_HTTP_START method=$method " +
                "url=${OnlinePlaybackDiagnostics.safeUrl(currentUri.toASCIIString())} timeoutMs=$timeout " +
                "headers=${headers.keys.map { it.lowercase() }.sorted()} bodyBytes=${body?.toByteArray(Charsets.UTF_8)?.size ?: 0}"
        )

        repeat(5) { redirectIndex ->
            validateRemoteUri(currentUri)
            val connection = currentUri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeout
            connection.readTimeout = timeout
            connection.requestMethod = method
            connection.setRequestProperty("User-Agent", headers.remove("User-Agent") ?: headers.remove("user-agent") ?: "RawSMusic/0.9.61 beta MusicSourceRuntime")
            headers.forEach(connection::setRequestProperty)
            if (body != null && method !in setOf("GET", "HEAD")) {
                connection.doOutput = true
                val bytes = body.toByteArray(Charsets.UTF_8)
                require(bytes.size <= 512 * 1024) { "请求正文超过 512 KiB 限制" }
                connection.outputStream.use { it.write(bytes) }
            }
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirectIndex < 4) { "网络重定向次数过多" }
                    val location = connection.getHeaderField("Location")
                        ?: throw IllegalStateException("网络响应包含无效重定向")
                    val nextUri = currentUri.resolve(location)
                    Log.i(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_HTTP_REDIRECT status=$status " +
                            "from=${OnlinePlaybackDiagnostics.safeUrl(currentUri.toASCIIString())} " +
                            "to=${OnlinePlaybackDiagnostics.safeUrl(nextUri.toASCIIString())}"
                    )
                    currentUri = nextUri
                    return@repeat
                }
                val responseHeaders = linkedMapOf<String, String>()
                connection.headerFields.forEach { (name, values) ->
                    if (name != null && !values.isNullOrEmpty()) responseHeaders[name] = values.joinToString(", ").take(16_384)
                }
                val input = if (status >= 400) connection.errorStream else connection.inputStream
                val bytes = input?.use { readLimited(it, 4 * 1024 * 1024) } ?: ByteArray(0)
                val binary = request.get("binary")?.asBoolean == true
                val text = if (binary) Base64.encodeToString(bytes, Base64.NO_WRAP) else bytes.toString(Charsets.UTF_8)
                val data: Any? = if (binary) text else runCatching { JsonParser.parseString(text) }.getOrElse { text }
                Log.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} RUNTIME_HTTP_END method=$method status=$status bytes=${bytes.size} " +
                        "url=${OnlinePlaybackDiagnostics.safeUrl(currentUri.toASCIIString())} " +
                        "elapsedMs=${SystemClock.elapsedRealtime() - requestStartedAt}"
                )
                return linkedMapOf(
                    "status" to status,
                    "statusText" to connection.responseMessage.orEmpty(),
                    "headers" to responseHeaders,
                    "data" to data,
                    "body" to text,
                    "binaryBase64" to binary,
                    "url" to currentUri.toASCIIString(),
                )
            } finally {
                connection.disconnect()
            }
        }
        error("网络请求失败")
    }

    private fun validateRemoteUri(uri: URI) {
        require(uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) { "仅允许 HTTP/HTTPS 请求" }
        val host = uri.host?.takeIf(String::isNotBlank) ?: throw IllegalArgumentException("请求地址缺少主机名")
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "无法解析请求主机" }
        require(addresses.none(::isPrivateAddress)) { "拒绝访问本机或局域网地址" }
    }

    private fun isPrivateAddress(address: InetAddress): Boolean =
        address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
            address.isSiteLocalAddress || address.isMulticastAddress

    private fun readLimited(input: java.io.InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream(min(limit, 64 * 1024))
        val buffer = ByteArray(16 * 1024)
        var total = 0
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            total += count
            require(total <= limit) { "网络响应超过 ${limit / 1024} KiB 限制" }
            output.write(buffer, 0, count)
        }
        return output.toByteArray()
    }

    companion object {
        private const val TAG = "MusicSourceRuntimeSvc"

        private fun actionName(action: Int): String = when (action) {
            MusicSourceRuntimeWire.ACTION_SEARCH -> "search"
            MusicSourceRuntimeWire.ACTION_RESOLVE_AUDIO -> "resolve_audio"
            MusicSourceRuntimeWire.ACTION_GET_LYRIC -> "get_lyric"
            MusicSourceRuntimeWire.ACTION_LX_RESOLVE_AUDIO -> "lx_resolve_audio"
            MusicSourceRuntimeWire.ACTION_LX_GET_LYRIC -> "lx_get_lyric"
            else -> "unknown_$action"
        }

        private val RUNTIME_HTML: String by lazy(LazyThreadSafetyMode.NONE) {
            RUNTIME_HTML_CORE + RUNTIME_HTML_LX_COMPAT
        }

        private const val RUNTIME_HTML_CORE = """<!doctype html><html><head><meta charset="utf-8"></head><body><script>
(() => {
  'use strict';
  const sources = Object.create(null);
  const hashes = Object.create(null);

  function hostHttp(config) {
    const normalized = typeof config === 'string' ? { url: config } : Object.assign({}, config || {});
    normalized.method = String(normalized.method || 'GET').toUpperCase();
    if (normalized.data !== undefined && normalized.body === undefined) normalized.body = normalized.data;
    const raw = RawSourceHost.http(JSON.stringify(normalized));
    const envelope = JSON.parse(raw);
    if (!envelope.ok) return Promise.reject(new Error(envelope.error || '网络请求失败'));
    const response = envelope.response;
    response.config = normalized;
    return Promise.resolve(response);
  }

  function createInterceptorManager() {
    const handlers = [];
    return {
      handlers,
      use(onFulfilled, onRejected) {
        handlers.push({ fulfilled: onFulfilled, rejected: onRejected });
        return handlers.length - 1;
      },
      eject(id) { if (handlers[id]) handlers[id] = null; },
      clear() { handlers.length = 0; },
      forEach(callback) { handlers.forEach(handler => { if (handler) callback(handler); }); },
    };
  }

  function mergeAxiosHeaders(method, ...values) {
    const output = {};
    const methodNames = new Set(['common', 'get', 'delete', 'head', 'post', 'put', 'patch', 'options']);
    values.forEach(value => {
      if (!value || typeof value !== 'object') return;
      const common = value.common;
      if (common && typeof common === 'object') Object.assign(output, common);
      const methodHeaders = value[String(method || 'get').toLowerCase()];
      if (methodHeaders && typeof methodHeaders === 'object') Object.assign(output, methodHeaders);
      Object.keys(value).forEach(key => {
        if (!methodNames.has(key.toLowerCase()) && value[key] != null) output[key] = value[key];
      });
    });
    return output;
  }

  function serializeAxiosParams(params, serializer) {
    if (!params) return '';
    if (serializer && typeof serializer === 'function') return String(serializer(params) || '');
    if (serializer && typeof serializer.serialize === 'function') return String(serializer.serialize(params) || '');
    if (params instanceof URLSearchParams) return params.toString();
    return qs.stringify(params);
  }

  function appendAxiosParams(url, params, serializer) {
    const query = serializeAxiosParams(params, serializer);
    if (!query) return String(url || '');
    const base = String(url || '');
    const hashIndex = base.indexOf('#');
    const hash = hashIndex >= 0 ? base.slice(hashIndex) : '';
    const head = hashIndex >= 0 ? base.slice(0, hashIndex) : base;
    return head + (head.includes('?') ? '&' : '?') + query + hash;
  }

  function normalizeAxiosBody(config) {
    if (config.data === undefined && config.body === undefined) return;
    const value = config.data !== undefined ? config.data : config.body;
    if (value == null || typeof value === 'string') {
      config.body = value;
      return;
    }
    if (value instanceof URLSearchParams) {
      config.body = value.toString();
      if (!Object.keys(config.headers).some(key => key.toLowerCase() === 'content-type')) {
        config.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
      }
      return;
    }
    if (value instanceof ArrayBuffer || ArrayBuffer.isView(value) || value instanceof BufferCompat) {
      config.body = BufferCompat.from(value).toString('base64');
      config.__rawBodyEncoding = 'base64';
      return;
    }
    config.body = JSON.stringify(value);
    if (!Object.keys(config.headers).some(key => key.toLowerCase() === 'content-type')) {
      config.headers['Content-Type'] = 'application/json;charset=UTF-8';
    }
  }

  function createAxiosError(message, config, response, code) {
    const error = new Error(String(message || 'Axios request failed'));
    error.name = 'AxiosError';
    error.code = code || null;
    error.config = config || {};
    error.request = null;
    error.response = response || null;
    error.status = response && response.status;
    error.isAxiosError = true;
    error.toJSON = () => ({
      message: error.message,
      name: error.name,
      code: error.code,
      status: error.status,
      config: error.config,
    });
    return error;
  }

  function makeAxiosInstance(initialDefaults) {
    const defaults = Object.assign({ timeout: 15000, headers: {} }, initialDefaults || {});
    const requestInterceptors = createInterceptorManager();
    const responseInterceptors = createInterceptorManager();

    async function dispatch(input, maybeConfig) {
      const supplied = typeof input === 'string'
        ? Object.assign({}, maybeConfig || {}, { url: input })
        : Object.assign({}, input || {});
      let config = Object.assign({}, defaults, supplied);
      config.method = String(config.method || 'GET').toUpperCase();
      config.headers = mergeAxiosHeaders(config.method, defaults.headers, supplied.headers);
      if (config.baseURL && !/^[a-z][a-z0-9+.-]*:\/\//i.test(String(config.url || ''))) {
        config.url = String(config.baseURL).replace(/\/$/, '') + '/' + String(config.url || '').replace(/^\//, '');
      }
      config.url = appendAxiosParams(config.url, config.params, config.paramsSerializer);
      normalizeAxiosBody(config);

      const requestHandlers = requestInterceptors.handlers.filter(Boolean).slice().reverse();
      for (const handler of requestHandlers) {
        try {
          if (typeof handler.fulfilled === 'function') config = await handler.fulfilled(config) || config;
        } catch (error) {
          if (typeof handler.rejected === 'function') config = await handler.rejected(error) || config;
          else throw error;
        }
      }

      let response;
      try {
        response = await hostHttp(config);
        response.config = config;
        response.request = null;
        const validateStatus = typeof config.validateStatus === 'function'
          ? config.validateStatus
          : status => status >= 200 && status < 300;
        if (!validateStatus(Number(response.status || 0))) {
          throw createAxiosError(
            'Request failed with status code ' + response.status,
            config,
            response,
            'ERR_BAD_RESPONSE',
          );
        }
      } catch (error) {
        let current = error && error.isAxiosError
          ? error
          : createAxiosError(error && error.message || error, config, error && error.response, 'ERR_NETWORK');
        for (const handler of responseInterceptors.handlers.filter(Boolean)) {
          if (typeof handler.rejected !== 'function') continue;
          try {
            return await handler.rejected(current);
          } catch (next) {
            current = next;
          }
        }
        throw current;
      }

      let current = response;
      for (const handler of responseInterceptors.handlers.filter(Boolean)) {
        if (typeof handler.fulfilled !== 'function') continue;
        try {
          current = await handler.fulfilled(current);
        } catch (error) {
          if (typeof handler.rejected === 'function') current = await handler.rejected(error);
          else throw error;
        }
      }
      return current;
    }

    const instance = function(input, maybeConfig) { return dispatch(input, maybeConfig); };
    instance.request = config => dispatch(config);
    ['get', 'delete', 'head', 'options'].forEach(method => {
      instance[method] = (url, config) => dispatch(Object.assign({}, config || {}, { url, method }));
    });
    ['post', 'put', 'patch'].forEach(method => {
      instance[method] = (url, data, config) => dispatch(Object.assign({}, config || {}, { url, method, data }));
    });
    instance.defaults = defaults;
    instance.interceptors = { request: requestInterceptors, response: responseInterceptors };
    instance.create = childDefaults => makeAxiosInstance(Object.assign({}, defaults, childDefaults || {}, {
      headers: mergeAxiosHeaders('get', defaults.headers, childDefaults && childDefaults.headers),
    }));
    instance.getUri = config => {
      const merged = Object.assign({}, defaults, config || {});
      return appendAxiosParams(merged.url || '', merged.params, merged.paramsSerializer);
    };
    instance.isAxiosError = value => !!(value && value.isAxiosError);
    instance.AxiosError = function AxiosError(message, code, config, request, response) {
      return createAxiosError(message, config, response, code);
    };
    instance.all = values => Promise.all(values);
    instance.spread = callback => values => callback.apply(null, values);
    instance.default = instance;
    instance.__esModule = true;
    return instance;
  }

  const axios = makeAxiosInstance();

  const qs = {
    stringify(value) {
      const p = new URLSearchParams();
      Object.keys(value || {}).forEach(k => {
        const v = value[k];
        if (Array.isArray(v)) v.forEach(item => p.append(k, String(item)));
        else if (v !== undefined && v !== null) p.append(k, String(v));
      });
      return p.toString();
    },
    parse(value) {
      const out = {};
      new URLSearchParams(String(value || '')).forEach((v, k) => { out[k] = v; });
      return out;
    },
  };

  function bytesToBase64(bytes) {
    let binary = '';
    const chunk = 0x8000;
    for (let i = 0; i < bytes.length; i += chunk) {
      binary += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + chunk)));
    }
    return btoa(binary);
  }

  function base64ToBytes(value) {
    const normalized = String(value || '').replace(/-/g, '+').replace(/_/g, '/');
    const padded = normalized + '='.repeat((4 - normalized.length % 4) % 4);
    const binary = atob(padded);
    const out = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i++) out[i] = binary.charCodeAt(i) & 255;
    return out;
  }

  function bytesToHex(bytes) {
    let out = '';
    for (let i = 0; i < bytes.length; i++) out += bytes[i].toString(16).padStart(2, '0');
    return out;
  }

  function hexToBytes(value) {
    const text = String(value || '').replace(/\s+/g, '');
    if (text.length % 2 !== 0) throw new Error('十六进制文本长度无效');
    const out = new Uint8Array(text.length / 2);
    for (let i = 0; i < out.length; i++) out[i] = parseInt(text.slice(i * 2, i * 2 + 2), 16);
    return out;
  }

  function latin1ToBytes(value) {
    const text = String(value || '');
    const out = new Uint8Array(text.length);
    for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 255;
    return out;
  }

  function bytesToLatin1(bytes) {
    let out = '';
    for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i]);
    return out;
  }

  function wordsToBytes(words, sigBytes) {
    const length = sigBytes == null ? words.length * 4 : Math.max(0, Number(sigBytes) || 0);
    const out = new Uint8Array(length);
    for (let i = 0; i < length; i++) out[i] = (words[i >>> 2] >>> (24 - (i % 4) * 8)) & 255;
    return out;
  }

  function bytesToWords(bytes) {
    const words = [];
    for (let i = 0; i < bytes.length; i++) words[i >>> 2] = (words[i >>> 2] || 0) | (bytes[i] << (24 - (i % 4) * 8));
    return words;
  }

  function cloneBytes(value) {
    if (value instanceof Uint8Array) return new Uint8Array(value);
    if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer.slice(value.byteOffset, value.byteOffset + value.byteLength));
    if (value instanceof ArrayBuffer) return new Uint8Array(value.slice(0));
    return null;
  }

  function normalizeBufferEncoding(encoding) {
    const value = String(encoding || 'utf8').toLowerCase().replace(/[-_]/g, '');
    if (value === 'utf8' || value === 'utf') return 'utf8';
    if (value === 'base64' || value === 'base64url') return value;
    if (value === 'hex') return 'hex';
    if (value === 'latin1' || value === 'binary' || value === 'ascii') return value === 'ascii' ? 'ascii' : 'latin1';
    if (value === 'ucs2' || value === 'utf16le') return 'utf16le';
    throw new Error('不支持的 Buffer 编码：' + encoding);
  }

  function stringToBufferBytes(value, encoding) {
    const text = String(value || '');
    switch (normalizeBufferEncoding(encoding)) {
      case 'hex': return hexToBytes(text);
      case 'base64':
      case 'base64url': return base64ToBytes(text);
      case 'latin1': return latin1ToBytes(text);
      case 'ascii': {
        const out = new Uint8Array(text.length);
        for (let i = 0; i < text.length; i++) out[i] = text.charCodeAt(i) & 0x7f;
        return out;
      }
      case 'utf16le': {
        const out = new Uint8Array(text.length * 2);
        for (let i = 0; i < text.length; i++) {
          const code = text.charCodeAt(i);
          out[i * 2] = code & 255;
          out[i * 2 + 1] = code >>> 8;
        }
        return out;
      }
      default: return new TextEncoder().encode(text);
    }
  }

  function bufferBytesToString(bytes, encoding) {
    switch (normalizeBufferEncoding(encoding)) {
      case 'hex': return bytesToHex(bytes);
      case 'base64': return bytesToBase64(bytes);
      case 'base64url': return bytesToBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '');
      case 'latin1': return bytesToLatin1(bytes);
      case 'ascii': {
        let out = '';
        for (let i = 0; i < bytes.length; i++) out += String.fromCharCode(bytes[i] & 0x7f);
        return out;
      }
      case 'utf16le': {
        let out = '';
        for (let i = 0; i + 1 < bytes.length; i += 2) out += String.fromCharCode(bytes[i] | (bytes[i + 1] << 8));
        return out;
      }
      default: return new TextDecoder('utf-8').decode(bytes);
    }
  }

  class BufferCompat extends Uint8Array {
    static from(value, encodingOrOffset, length) {
      let bytes;
      if (typeof value === 'string') {
        bytes = stringToBufferBytes(value, encodingOrOffset);
      } else if (value instanceof ArrayBuffer) {
        const offset = Math.max(0, Number(encodingOrOffset) || 0);
        const count = length == null ? value.byteLength - offset : Math.max(0, Number(length) || 0);
        bytes = new Uint8Array(value, offset, Math.min(count, value.byteLength - offset));
      } else if (ArrayBuffer.isView(value)) {
        bytes = new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
      } else if (Array.isArray(value)) {
        bytes = Uint8Array.from(value.map(item => Number(item) & 255));
      } else if (value && value.type === 'Buffer' && Array.isArray(value.data)) {
        bytes = Uint8Array.from(value.data.map(item => Number(item) & 255));
      } else {
        throw new TypeError('Buffer.from 不支持当前输入');
      }
      const out = new BufferCompat(bytes.length);
      out.set(bytes);
      return out;
    }
    static alloc(size, fill, encoding) {
      const out = new BufferCompat(Math.max(0, Number(size) || 0));
      if (fill !== undefined) out.fillValue(fill, 0, out.length, encoding);
      return out;
    }
    static allocUnsafe(size) { return BufferCompat.alloc(size); }
    static allocUnsafeSlow(size) { return BufferCompat.alloc(size); }
    static isBuffer(value) { return value instanceof BufferCompat; }
    static isEncoding(value) { try { normalizeBufferEncoding(value); return true; } catch (_) { return false; } }
    static byteLength(value, encoding) { return BufferCompat.from(String(value ?? ''), encoding).length; }
    static concat(values, totalLength) {
      const buffers = Array.from(values || [], value => BufferCompat.from(value));
      const size = totalLength == null ? buffers.reduce((sum, value) => sum + value.length, 0) : Math.max(0, Number(totalLength) || 0);
      const out = BufferCompat.alloc(size);
      let offset = 0;
      for (const value of buffers) {
        if (offset >= size) break;
        const count = Math.min(value.length, size - offset);
        out.set(value.subarray(0, count), offset);
        offset += count;
      }
      return out;
    }
    toString(encoding, start, end) {
      const from = Math.max(0, Number(start) || 0);
      const to = end == null ? this.length : Math.max(from, Math.min(this.length, Number(end) || 0));
      return bufferBytesToString(Uint8Array.prototype.subarray.call(this, from, to), encoding);
    }
    slice(start, end) { return BufferCompat.from(Uint8Array.prototype.slice.call(this, start, end)); }
    subarray(start, end) { return BufferCompat.from(Uint8Array.prototype.subarray.call(this, start, end)); }
    equals(other) {
      const right = BufferCompat.from(other);
      if (right.length !== this.length) return false;
      for (let i = 0; i < this.length; i++) if (this[i] !== right[i]) return false;
      return true;
    }
    copy(target, targetStart, sourceStart, sourceEnd) {
      const output = target instanceof Uint8Array ? target : BufferCompat.from(target);
      const to = Math.max(0, Number(targetStart) || 0);
      const from = Math.max(0, Number(sourceStart) || 0);
      const end = sourceEnd == null ? this.length : Math.max(from, Math.min(this.length, Number(sourceEnd) || 0));
      const count = Math.min(end - from, Math.max(0, output.length - to));
      output.set(Uint8Array.prototype.subarray.call(this, from, from + count), to);
      return count;
    }
    write(value, offset, length, encoding) {
      const start = Math.max(0, Number(offset) || 0);
      const bytes = stringToBufferBytes(value, typeof length === 'string' ? length : encoding);
      const count = Math.min(
        bytes.length,
        typeof length === 'number' ? Math.max(0, length) : bytes.length,
        Math.max(0, this.length - start),
      );
      this.set(bytes.subarray(0, count), start);
      return count;
    }
    fillValue(value, start, end, encoding) {
      const from = Math.max(0, Number(start) || 0);
      const to = end == null ? this.length : Math.max(from, Math.min(this.length, Number(end) || 0));
      const bytes = typeof value === 'number' ? Uint8Array.of(value & 255) : stringToBufferBytes(value, encoding);
      if (!bytes.length) return this;
      for (let i = from; i < to; i++) this[i] = bytes[(i - from) % bytes.length];
      return this;
    }
    fill(value, start, end, encoding) { return this.fillValue(value, start, end, encoding); }
    readUInt8(offset) { return this[Number(offset) || 0]; }
    readUInt16BE(offset) { const i=Number(offset)||0; return (this[i] << 8) | this[i+1]; }
    readUInt16LE(offset) { const i=Number(offset)||0; return this[i] | (this[i+1] << 8); }
    readUInt32BE(offset) { const i=Number(offset)||0; return ((this[i]*0x1000000)+(this[i+1]<<16)+(this[i+2]<<8)+this[i+3]) >>> 0; }
    readUInt32LE(offset) { const i=Number(offset)||0; return (this[i]+(this[i+1]<<8)+(this[i+2]<<16)+(this[i+3]*0x1000000)) >>> 0; }
    writeUInt8(value, offset) { const i=Number(offset)||0; this[i]=Number(value)&255; return i+1; }
    writeUInt16BE(value, offset) { const i=Number(offset)||0, v=Number(value)>>>0; this[i]=v>>>8; this[i+1]=v; return i+2; }
    writeUInt16LE(value, offset) { const i=Number(offset)||0, v=Number(value)>>>0; this[i]=v; this[i+1]=v>>>8; return i+2; }
    writeUInt32BE(value, offset) { const i=Number(offset)||0, v=Number(value)>>>0; this[i]=v>>>24; this[i+1]=v>>>16; this[i+2]=v>>>8; this[i+3]=v; return i+4; }
    writeUInt32LE(value, offset) { const i=Number(offset)||0, v=Number(value)>>>0; this[i]=v; this[i+1]=v>>>8; this[i+2]=v>>>16; this[i+3]=v>>>24; return i+4; }
    toJSON() { return { type: 'Buffer', data: Array.from(this) }; }
  }

  const bufferModule = {
    Buffer: BufferCompat,
    SlowBuffer: size => BufferCompat.alloc(size),
    INSPECT_MAX_BYTES: 50,
    kMaxLength: 0x7fffffff,
  };
  globalThis.Buffer = BufferCompat;

  class WordArray {
    constructor(words, sigBytes) {
      const typed = cloneBytes(words);
      if (typed) this._bytes = typed;
      else if (Array.isArray(words)) this._bytes = wordsToBytes(words, sigBytes);
      else if (words instanceof WordArray) this._bytes = new Uint8Array(words._bytes);
      else this._bytes = new Uint8Array(0);
      if (sigBytes != null && this._bytes.length !== Number(sigBytes)) this._bytes = this._bytes.slice(0, Math.max(0, Number(sigBytes) || 0));
    }
    get words() { return bytesToWords(this._bytes); }
    set words(value) { this._bytes = wordsToBytes(Array.isArray(value) ? value : [], this._bytes.length); }
    get sigBytes() { return this._bytes.length; }
    set sigBytes(value) { this._bytes = this._bytes.slice(0, Math.max(0, Number(value) || 0)); }
    toString(encoder) { return (encoder || CryptoJS.enc.Hex).stringify(this); }
    concat(other) {
      const right = toWordArray(other)._bytes;
      const joined = new Uint8Array(this._bytes.length + right.length);
      joined.set(this._bytes, 0);
      joined.set(right, this._bytes.length);
      this._bytes = joined;
      return this;
    }
    clamp() { return this; }
    clone() { return new WordArray(this._bytes); }
    static create(words, sigBytes) { return new WordArray(words, sigBytes); }
    static random(size) { return hostCrypto({ operation: 'random', size: Math.max(0, Number(size) || 0) }); }
  }

  function toWordArray(value) {
    if (value instanceof WordArray) return value;
    const typed = cloneBytes(value);
    if (typed) return new WordArray(typed);
    if (value && value.ciphertext instanceof WordArray) return value.ciphertext;
    if (typeof value === 'string') return CryptoJS.enc.Utf8.parse(value);
    if (Array.isArray(value)) return new WordArray(value);
    if (value == null) return new WordArray();
    return CryptoJS.enc.Utf8.parse(String(value));
  }

  const enc = {
    Hex: {
      stringify(value) { return bytesToHex(toWordArray(value)._bytes); },
      parse(value) { return new WordArray(hexToBytes(value)); },
    },
    Base64: {
      stringify(value) { return bytesToBase64(toWordArray(value)._bytes); },
      parse(value) { return new WordArray(base64ToBytes(value)); },
    },
    Base64url: {
      stringify(value, urlSafe) {
        const text = bytesToBase64(toWordArray(value)._bytes);
        if (urlSafe === false) return text;
        let out = text.replace(/\+/g, '-').replace(/\//g, '_');
        while (out.endsWith('=')) out = out.slice(0, -1);
        return out;
      },
      parse(value) { return new WordArray(base64ToBytes(value)); },
    },
    Utf8: {
      stringify(value) { return new TextDecoder('utf-8').decode(toWordArray(value)._bytes); },
      parse(value) { return new WordArray(new TextEncoder().encode(String(value || ''))); },
    },
    Latin1: {
      stringify(value) { return bytesToLatin1(toWordArray(value)._bytes); },
      parse(value) { return new WordArray(latin1ToBytes(value)); },
    },
  };

  function hostCrypto(request) {
    if (request.data instanceof WordArray) request.data = bytesToBase64(request.data._bytes);
    if (request.key instanceof WordArray) request.key = bytesToBase64(request.key._bytes);
    if (request.iv instanceof WordArray) request.iv = bytesToBase64(request.iv._bytes);
    const envelope = JSON.parse(RawSourceHost.crypto(JSON.stringify(request)));
    if (!envelope.ok) throw new Error(envelope.error || '加密操作失败');
    return new WordArray(base64ToBytes(envelope.output || ''));
  }

  function digest(algorithm, value) {
    return hostCrypto({ operation: 'digest', algorithm, data: toWordArray(value) });
  }

  function hmac(algorithm, value, key) {
    return hostCrypto({ operation: 'hmac', algorithm, data: toWordArray(value), key: toWordArray(key) });
  }

  function makeHasher(algorithm) {
    const buffer = new WordArray();
    return {
      update(value) { buffer.concat(toWordArray(value)); return this; },
      finalize(value) { if (value !== undefined) buffer.concat(toWordArray(value)); return digest(algorithm, buffer); },
      reset() { buffer._bytes = new Uint8Array(0); return this; },
      clone() { const copy = makeHasher(algorithm); copy.update(buffer); return copy; },
    };
  }

  const mode = {
    CBC: { name: 'CBC' }, ECB: { name: 'ECB' }, CFB: { name: 'CFB' }, CTR: { name: 'CTR' }, OFB: { name: 'OFB' },
  };
  const pad = {
    Pkcs7: { name: 'PKCS7' }, NoPadding: { name: 'NOPADDING' },
  };

  const CipherParams = {
    create(value) {
      const out = Object.assign({}, value || {});
      out.toString = function(formatter) {
        if (formatter && typeof formatter.stringify === 'function') return formatter.stringify(out);
        return enc.Base64.stringify(out.ciphertext || new WordArray());
      };
      return out;
    },
  };

  function createCipherModule(algorithm) {
    function transform(encrypt, input, key, config) {
      const cfg = config || {};
      const keyBytes = toWordArray(key);
      const modeName = String(cfg.mode && cfg.mode.name || 'CBC');
      const blockSize = algorithm === 'AES' ? 16 : 8;
      const iv = modeName === 'ECB' ? new WordArray() : (cfg.iv ? toWordArray(cfg.iv) : new WordArray(new Uint8Array(blockSize)));
      const paddingName = String(cfg.padding && cfg.padding.name || 'PKCS7');
      let data;
      if (encrypt) data = toWordArray(input);
      else if (typeof input === 'string') data = enc.Base64.parse(input);
      else data = toWordArray(input && input.ciphertext ? input.ciphertext : input);
      const output = hostCrypto({
        operation: 'cipher', algorithm, mode: modeName, padding: paddingName,
        encrypt, key: keyBytes, iv, data,
      });
      if (!encrypt) return output;
      return CipherParams.create({ ciphertext: output, key: keyBytes, iv, algorithm });
    }
    return {
      encrypt(message, key, config) { return transform(true, message, key, config); },
      decrypt(ciphertext, key, config) { return transform(false, ciphertext, key, config); },
    };
  }

  const CryptoJS = {
    lib: { WordArray, CipherParams },
    enc,
    mode,
    pad,
    format: { OpenSSL: { stringify(params) { return enc.Base64.stringify(params.ciphertext); }, parse(value) { return CipherParams.create({ ciphertext: enc.Base64.parse(value) }); } } },
    MD5(value) { return digest('MD5', value); },
    SHA1(value) { return digest('SHA-1', value); },
    SHA256(value) { return digest('SHA-256', value); },
    SHA512(value) { return digest('SHA-512', value); },
    HmacMD5(value, key) { return hmac('HmacMD5', value, key); },
    HmacSHA1(value, key) { return hmac('HmacSHA1', value, key); },
    HmacSHA256(value, key) { return hmac('HmacSHA256', value, key); },
    HmacSHA512(value, key) { return hmac('HmacSHA512', value, key); },
    AES: createCipherModule('AES'),
    DES: createCipherModule('DES'),
    TripleDES: createCipherModule('DESEDE'),
    algo: {
      MD5: { create() { return makeHasher('MD5'); } },
      SHA1: { create() { return makeHasher('SHA-1'); } },
      SHA256: { create() { return makeHasher('SHA-256'); } },
      SHA512: { create() { return makeHasher('SHA-512'); } },
    },
  };


  function uniqueCheerioValues(values) {
    const output = [];
    const seen = new Set();
    values.forEach(value => {
      if (value == null || seen.has(value)) return;
      seen.add(value);
      output.push(value);
    });
    return output;
  }

  function parseCheerioSelector(selector) {
    let css = String(selector || '').trim();
    const filters = [];
    css = css.replace(/:contains\((['"]?)(.*?)\1\)/g, (_, quote, text) => {
      filters.push({ type: 'contains', value: text });
      return '';
    });
    css = css.replace(/:eq\((-?\d+)\)/g, (_, index) => {
      filters.push({ type: 'eq', value: Number(index) });
      return '';
    });
    css = css.replace(/:(first|last)(?![-\w])/g, (_, type) => {
      filters.push({ type });
      return '';
    });
    return { css: css || '*', filters };
  }

  function applyCheerioFilters(nodes, filters) {
    let output = Array.from(nodes || []);
    filters.forEach(filter => {
      if (filter.type === 'contains') {
        output = output.filter(node => String(node && node.textContent || '').includes(filter.value));
      } else if (filter.type === 'eq') {
        const index = filter.value < 0 ? output.length + filter.value : filter.value;
        output = index >= 0 && index < output.length ? [output[index]] : [];
      } else if (filter.type === 'first') {
        output = output.length ? [output[0]] : [];
      } else if (filter.type === 'last') {
        output = output.length ? [output[output.length - 1]] : [];
      }
    });
    return output;
  }

  function queryCheerioNodes(contexts, selector) {
    const parsed = parseCheerioSelector(selector);
    const found = [];
    contexts.forEach(context => {
      if (!context || typeof context.querySelectorAll !== 'function') return;
      try { found.push(...context.querySelectorAll(parsed.css)); } catch (_) {}
    });
    return applyCheerioFilters(uniqueCheerioValues(found), parsed.filters);
  }

  class CheerioCollection {
    constructor(values, rootDocument, select) {
      this._values = Array.from(values || []);
      this._rootDocument = rootDocument;
      this._select = select;
      this.cheerio = '[rawsmusic-cheerio]';
      this.length = this._values.length;
      for (let i = 0; i < this.length; i++) this[i] = this._values[i];
    }
    _wrap(values) { return new CheerioCollection(values, this._rootDocument, this._select); }
    toArray() { return this._values.slice(); }
    get(index) {
      if (index == null) return this.toArray();
      const resolved = Number(index) < 0 ? this.length + Number(index) : Number(index);
      return this._values[resolved];
    }
    eq(index) { const value = this.get(index); return this._wrap(value == null ? [] : [value]); }
    first() { return this.eq(0); }
    last() { return this.eq(-1); }
    each(callback) {
      this._values.forEach((value, index) => callback.call(value, index, value));
      return this;
    }
    map(callback) {
      const output = [];
      this._values.forEach((value, index) => {
        const mapped = callback.call(value, index, value);
        if (mapped == null) return;
        if (Array.isArray(mapped)) output.push(...mapped);
        else if (mapped instanceof CheerioCollection) output.push(...mapped.toArray());
        else output.push(mapped);
      });
      return this._wrap(output);
    }
    slice(start, end) { return this._wrap(this._values.slice(start, end)); }
    find(selector) { return this._wrap(queryCheerioNodes(this._values, selector)); }
    filter(selectorOrCallback) {
      if (typeof selectorOrCallback === 'function') {
        return this._wrap(this._values.filter((value, index) => selectorOrCallback.call(value, index, value)));
      }
      const parsed = parseCheerioSelector(selectorOrCallback);
      return this._wrap(applyCheerioFilters(this._values.filter(value => {
        if (!value || typeof value.matches !== 'function') return false;
        try { return value.matches(parsed.css); } catch (_) { return false; }
      }), parsed.filters));
    }
    not(selector) {
      const excluded = new Set(this.filter(selector).toArray());
      return this._wrap(this._values.filter(value => !excluded.has(value)));
    }
    is(selector) { return this.filter(selector).length > 0; }
    has(selector) { return this._wrap(this._values.filter(value => queryCheerioNodes([value], selector).length > 0)); }
    children(selector) {
      let values = [];
      this._values.forEach(value => { if (value && value.children) values.push(...value.children); });
      const wrapped = this._wrap(uniqueCheerioValues(values));
      return selector ? wrapped.filter(selector) : wrapped;
    }
    contents() {
      let values = [];
      this._values.forEach(value => { if (value && value.childNodes) values.push(...value.childNodes); });
      return this._wrap(values);
    }
    parent(selector) {
      const wrapped = this._wrap(uniqueCheerioValues(this._values.map(value => value && value.parentElement).filter(Boolean)));
      return selector ? wrapped.filter(selector) : wrapped;
    }
    parents(selector) {
      const output = [];
      this._values.forEach(value => {
        let current = value && value.parentElement;
        while (current) { output.push(current); current = current.parentElement; }
      });
      const wrapped = this._wrap(uniqueCheerioValues(output));
      return selector ? wrapped.filter(selector) : wrapped;
    }
    closest(selector) {
      return this._wrap(uniqueCheerioValues(this._values.map(value => {
        if (!value || typeof value.closest !== 'function') return null;
        try { return value.closest(selector); } catch (_) { return null; }
      }).filter(Boolean)));
    }
    next(selector) {
      const wrapped = this._wrap(uniqueCheerioValues(this._values.map(value => value && value.nextElementSibling).filter(Boolean)));
      return selector ? wrapped.filter(selector) : wrapped;
    }
    prev(selector) {
      const wrapped = this._wrap(uniqueCheerioValues(this._values.map(value => value && value.previousElementSibling).filter(Boolean)));
      return selector ? wrapped.filter(selector) : wrapped;
    }
    siblings(selector) {
      let output = [];
      this._values.forEach(value => {
        if (!value || !value.parentElement) return;
        output.push(...Array.from(value.parentElement.children).filter(child => child !== value));
      });
      const wrapped = this._wrap(uniqueCheerioValues(output));
      return selector ? wrapped.filter(selector) : wrapped;
    }
    attr(name, value) {
      const first = this.get(0);
      if (typeof name === 'object' && name) {
        return this.each((_, node) => {
          if (!node || typeof node.setAttribute !== 'function') return;
          Object.keys(name).forEach(key => node.setAttribute(key, String(name[key])));
        });
      }
      if (value === undefined) return first && typeof first.getAttribute === 'function' ? first.getAttribute(String(name)) : undefined;
      return this.each((_, node) => { if (node && typeof node.setAttribute === 'function') node.setAttribute(String(name), String(value)); });
    }
    removeAttr(name) { return this.each((_, node) => { if (node && node.removeAttribute) node.removeAttribute(String(name)); }); }
    prop(name, value) {
      const first = this.get(0);
      if (value === undefined) return first ? first[name] : undefined;
      return this.each((_, node) => { if (node) node[name] = value; });
    }
    data(name, value) {
      const key = String(name || '').replace(/-([a-z])/g, (_, letter) => letter.toUpperCase());
      const first = this.get(0);
      if (value === undefined) return first && first.dataset ? first.dataset[key] : undefined;
      return this.each((_, node) => { if (node && node.dataset) node.dataset[key] = String(value); });
    }
    text(value) {
      if (value === undefined) return this._values.map(node => String(node && node.textContent || '')).join('');
      return this.each((_, node) => { if (node) node.textContent = String(value); });
    }
    html(value) {
      const first = this.get(0);
      if (value === undefined) {
        if (!first) return null;
        if (first.nodeType === 9) return first.documentElement ? first.documentElement.outerHTML : '';
        return first.innerHTML == null ? null : first.innerHTML;
      }
      return this.each((_, node) => { if (node && 'innerHTML' in node) node.innerHTML = String(value); });
    }
    val(value) {
      const first = this.get(0);
      if (value === undefined) return first && 'value' in first ? first.value : undefined;
      return this.each((_, node) => { if (node && 'value' in node) node.value = value; });
    }
    hasClass(name) {
      const first = this.get(0);
      return !!(first && first.classList && first.classList.contains(String(name)));
    }
    addClass(name) { const names=String(name||'').split(/\s+/).filter(Boolean); return this.each((_,n)=>{ if(n&&n.classList) n.classList.add(...names); }); }
    removeClass(name) { const names=String(name||'').split(/\s+/).filter(Boolean); return this.each((_,n)=>{ if(n&&n.classList) n.classList.remove(...names); }); }
    toggleClass(name, force) { return this.each((_,n)=>{ if(n&&n.classList) n.classList.toggle(String(name), force); }); }
    css(name, value) {
      const first = this.get(0);
      if (typeof name === 'object' && name) return this.each((_,node)=>{ if(node&&node.style) Object.assign(node.style,name); });
      if (value === undefined) return first && first.style ? first.style[name] : undefined;
      return this.each((_,node)=>{ if(node&&node.style) node.style[name]=String(value); });
    }
    remove() { return this.each((_, node) => { if (node && node.remove) node.remove(); }); }
    clone() { return this._wrap(this._values.map(node => node && node.cloneNode ? node.cloneNode(true) : node)); }
  }

  function createCheerioModule() {
    function load(markup, options, isDocument) {
      const config = options || {};
      const xmlMode = !!(config.xmlMode || config._useHtmlParser2);
      const mime = xmlMode ? 'application/xml' : 'text/html';
      const parsed = new DOMParser().parseFromString(String(markup == null ? '' : markup), mime);
      const root = isDocument === false
        ? (parsed.body || parsed.documentElement || parsed)
        : parsed;
      const select = function(selector, context) {
        if (selector instanceof CheerioCollection) return selector;
        if (selector == null) return new CheerioCollection([], parsed, select);
        if (typeof selector !== 'string') {
          if (selector.nodeType) return new CheerioCollection([selector], parsed, select);
          if (Array.isArray(selector) || typeof selector.length === 'number') return new CheerioCollection(Array.from(selector), parsed, select);
          return new CheerioCollection([], parsed, select);
        }
        const text = selector.trim();
        if (text.startsWith('<') && text.endsWith('>')) {
          const fragment = new DOMParser().parseFromString(text, mime);
          const values = fragment.body ? Array.from(fragment.body.childNodes) : Array.from(fragment.childNodes || []);
          return new CheerioCollection(values, parsed, select);
        }
        const contexts = context == null
          ? [root]
          : (context instanceof CheerioCollection ? context.toArray() : (context.nodeType ? [context] : Array.from(context || [])));
        return new CheerioCollection(queryCheerioNodes(contexts, text), parsed, select);
      };
      select.root = () => new CheerioCollection([root], parsed, select);
      select.html = value => {
        if (value != null) return select(value).html();
        return parsed.documentElement ? parsed.documentElement.outerHTML : '';
      };
      select.text = value => select(value == null ? root : value).text();
      select.xml = select.html;
      select.contains = (container, contained) => !!(container && contained && container !== contained && container.contains && container.contains(contained));
      select.parseHTML = value => select(String(value || '')).toArray();
      return select;
    }
    const module = { load };
    module.loadBuffer = value => load(BufferCompat.from(value).toString('utf8'));
    module.decodeStream = () => { throw new Error('当前 Cheerio 兼容层不支持流式解析'); };
    module.stringStream = module.decodeStream;
    module.default = module;
    module.__esModule = true;
    return module;
  }

  function createPakoModule() {
    function normalizeBytes(value) {
      if (value instanceof Uint8Array) return value;
      if (value instanceof ArrayBuffer) return new Uint8Array(value);
      if (ArrayBuffer.isView(value)) return new Uint8Array(value.buffer, value.byteOffset, value.byteLength);
      if (typeof value === 'string') return BufferCompat.from(value, 'binary');
      return BufferCompat.from(value || []);
    }
    function invoke(operation, value, options) {
      const bytes = normalizeBytes(value);
      const response = JSON.parse(RawSourceHost.zlib(JSON.stringify({
        operation,
        data: bytesToBase64(bytes),
        level: options && Number.isFinite(Number(options.level)) ? Number(options.level) : -1,
      })));
      if (!response.ok) throw new Error(response.error || 'pako 操作失败');
      const output = base64ToBytes(response.output || '');
      if (options && options.to === 'string') return new TextDecoder('utf-8').decode(output);
      return output;
    }
    function concatChunks(chunks) {
      const normalized = chunks.map(normalizeBytes);
      const size = normalized.reduce((sum, chunk) => sum + chunk.length, 0);
      const output = new Uint8Array(size);
      let offset = 0;
      normalized.forEach(chunk => { output.set(chunk, offset); offset += chunk.length; });
      return output;
    }
    class InflateCompat {
      constructor(options) { this.options = options || {}; this.chunks = []; this.err = 0; this.msg = ''; this.result = null; }
      push(data, final) {
        try {
          this.chunks.push(normalizeBytes(data));
          if (final === true || final === 4) this.result = module.inflate(concatChunks(this.chunks), this.options);
          return true;
        } catch (error) {
          this.err = -1; this.msg = String(error && error.message || error); return false;
        }
      }
    }
    class DeflateCompat {
      constructor(options) { this.options = options || {}; this.chunks = []; this.err = 0; this.msg = ''; this.result = null; }
      push(data, final) {
        try {
          this.chunks.push(normalizeBytes(data));
          if (final === true || final === 4) {
            const operation = this.options.gzip ? 'gzip' : (this.options.raw ? 'deflateRaw' : 'deflate');
            this.result = invoke(operation, concatChunks(this.chunks), this.options);
          }
          return true;
        } catch (error) {
          this.err = -1; this.msg = String(error && error.message || error); return false;
        }
      }
    }
    const module = {
      inflate(value, options) {
        if (options && options.raw) return invoke('inflateRaw', value, options);
        try { return invoke('inflate', value, options); }
        catch (first) {
          try { return invoke('inflateRaw', value, options); }
          catch (_) { return invoke('ungzip', value, options); }
        }
      },
      inflateRaw: (value, options) => invoke('inflateRaw', value, options),
      ungzip: (value, options) => invoke('ungzip', value, options),
      deflate: (value, options) => invoke(options && options.raw ? 'deflateRaw' : 'deflate', value, options),
      deflateRaw: (value, options) => invoke('deflateRaw', value, options),
      gzip: (value, options) => invoke('gzip', value, options),
      Inflate: InflateCompat,
      Deflate: DeflateCompat,
      constants: { Z_NO_FLUSH: 0, Z_SYNC_FLUSH: 2, Z_FINISH: 4, Z_OK: 0, Z_STREAM_END: 1 },
    };
    module.unzip = module.inflate;
    module.default = module;
    module.__esModule = true;
    return module;
  }

  const pako = createPakoModule();

  const cheerio = createCheerioModule();

  function exposeCommonJsDefault(module) {
    if (module && (typeof module === 'object' || typeof module === 'function')) {
      if (module.default == null) module.default = module;
    }
    return module;
  }

  function requireModule(name) {
    if (name === 'axios') return exposeCommonJsDefault(axios);
    if (name === 'qs') return exposeCommonJsDefault(qs);
    if (name === 'cheerio' || name === 'cheerio/slim') return exposeCommonJsDefault(cheerio);
    if (name === 'pako') return exposeCommonJsDefault(pako);
    if (name === 'buffer' || name === 'safe-buffer') return exposeCommonJsDefault(bufferModule);
    if (name === 'crypto-js') return exposeCommonJsDefault(CryptoJS);
    if (name === 'crypto-js/md5') return exposeCommonJsDefault(CryptoJS.MD5);
    if (name === 'crypto-js/sha1') return exposeCommonJsDefault(CryptoJS.SHA1);
    if (name === 'crypto-js/sha256') return exposeCommonJsDefault(CryptoJS.SHA256);
    if (name === 'crypto-js/sha512') return exposeCommonJsDefault(CryptoJS.SHA512);
    if (name === 'crypto-js/aes') return exposeCommonJsDefault(CryptoJS.AES);
    if (name === 'crypto-js/des') return exposeCommonJsDefault(CryptoJS.DES);
    if (name === 'crypto-js/tripledes') return exposeCommonJsDefault(CryptoJS.TripleDES);
    if (name === 'crypto-js/enc-base64') return exposeCommonJsDefault(CryptoJS.enc.Base64);
    if (name === 'crypto-js/enc-hex') return exposeCommonJsDefault(CryptoJS.enc.Hex);
    if (name === 'crypto-js/enc-utf8') return exposeCommonJsDefault(CryptoJS.enc.Utf8);
    if (name === 'he') return exposeCommonJsDefault({ decode: value => {
      const t = document.createElement('textarea'); t.innerHTML = String(value || ''); return t.value;
    }});
    throw new Error('当前运行器尚未开放宿主模块：' + name);
  }

  function safeJson(value) {
    const seen = new WeakSet();
    return JSON.stringify(value, (key, current) => {
      if (typeof current === 'function' || typeof current === 'symbol') return undefined;
      if (typeof current === 'bigint') return current.toString();
      if (current && typeof current === 'object') {
        if (seen.has(current)) return undefined;
        seen.add(current);
      }
      return current;
    });
  }

  function normalizeItem(item, fallbackPlatform, fallbackType) {
    const raw = item && typeof item === 'object' ? item : {};
    const qualities = raw.qualities && typeof raw.qualities === 'object' ? Object.keys(raw.qualities) : [];
    return {
      id: String(raw.id ?? raw.songId ?? raw.mid ?? raw.url ?? ''),
      platform: String(raw.platform || fallbackPlatform || ''),
      type: String(raw.type || fallbackType || 'music'),
      title: String(raw.title ?? raw.name ?? ''),
      artist: String(raw.artist ?? raw.singer ?? raw.author ?? ''),
      album: String(raw.album ?? raw.albumName ?? ''),
      durationSeconds: Number(raw.duration ?? raw.interval ?? 0) || 0,
      artwork: String(raw.artwork ?? raw.pic ?? raw.cover ?? raw.img ?? ''),
      qualityKeys: qualities.length ? qualities : ['standard'],
      rawPayload: safeJson(raw) || '{}',
    };
  }

  function restoreMediaItem(itemPayload, fallbackPlatform) {
    let envelope;
    try { envelope = JSON.parse(String(itemPayload || '{}')); } catch (_) { envelope = {}; }
    let raw;
    try { raw = JSON.parse(String(envelope.rawPayload || '{}')); } catch (_) { raw = {}; }
    if (!raw || typeof raw !== 'object' || Array.isArray(raw)) raw = {};
    if (raw.id == null) raw.id = envelope.remoteId || '';
    if (!raw.platform) raw.platform = fallbackPlatform || '';
    if (!raw.title) raw.title = envelope.title || '';
    if (!raw.artist) raw.artist = Array.isArray(envelope.artists) ? envelope.artists.join(' / ') : '';
    if (!raw.album) raw.album = envelope.album || '';
    if (!raw.artwork) raw.artwork = envelope.artworkUrl || '';
    if (!raw.duration && envelope.durationMs) raw.duration = Number(envelope.durationMs) / 1000;
    return raw;
  }

  function normalizeHeaders(headers, userAgent) {
    const output = {};
    if (headers && typeof headers === 'object' && !Array.isArray(headers)) {
      Object.keys(headers).slice(0, 64).forEach(key => {
        const value = headers[key];
        if (value != null && String(key).trim()) output[String(key).slice(0, 128)] = String(value).slice(0, 8192);
      });
    }
    if (userAgent && !Object.keys(output).some(key => key.toLowerCase() === 'user-agent')) {
      output['User-Agent'] = String(userAgent).slice(0, 8192);
    }
    return output;
  }

  async function withTimeout(promise, timeoutMs) {
    let timer;
    try {
      return await Promise.race([
        Promise.resolve(promise),
        new Promise((_, reject) => { timer = setTimeout(() => reject(new Error('音源调用超时')), timeoutMs); }),
      ]);
    } finally {
      clearTimeout(timer);
    }
  }


"""

        private const val RUNTIME_HTML_LX_COMPAT = """  const lxSources = Object.create(null);
  const lxHashes = Object.create(null);

  function lxQualityFallback(qualitys, requested) {
    const available = Array.isArray(qualitys) ? qualitys.map(String) : [];
    const target = String(requested || '128k');
    if (!available.length || available.includes(target)) return target;
    for (const quality of ['flac24bit', 'flac', '320k', '128k']) {
      if (available.includes(quality)) return quality;
    }
    return available[available.length - 1] || target;
  }

  function lxDuration(value) {
    const total = Math.max(0, Math.floor(Number(value || 0) / 1000));
    const hours = Math.floor(total / 3600);
    const minutes = Math.floor((total % 3600) / 60);
    const seconds = total % 60;
    const pad2 = number => String(number).padStart(2, '0');
    return hours > 0 ? pad2(hours) + ':' + pad2(minutes) + ':' + pad2(seconds) : pad2(minutes) + ':' + pad2(seconds);
  }

  function lxBuildMusicInfo(item, platform, songmid, quality) {
    const qualityInfo = { type: quality, size: null };
    const qualityMap = {}; qualityMap[quality] = { size: null };
    const title = String(item.title || item.name || '');
    const artist = String(item.artist || item.singer || '');
    const album = String(item.album || item.albumName || '');
    const artwork = String(item.artwork || item.pic || item.cover || '');
    return {
      id: platform + '_' + songmid,
      name: title,
      singer: artist,
      source: platform,
      songmid: songmid,
      albumName: album,
      interval: item.interval || lxDuration(Number(item.durationMs || 0)),
      types: [qualityInfo],
      _types: qualityMap,
      typeUrl: {},
      meta: {
        songId: songmid,
        albumName: album,
        picUrl: artwork || null,
        qualitys: [qualityInfo],
        _qualitys: qualityMap,
      },
    };
  }

  function lxCreateRuntime(id, sourceName) {
    let initInfo = null;
    let requestHandler = null;
    const EVENT_NAMES = { request: 'request', inited: 'inited', updateAlert: 'updateAlert' };

    function lxRequest(url, options, callback) {
      const opts = Object.assign({}, options || {});
      const config = {
        url: String(url || ''),
        method: String(opts.method || 'get').toUpperCase(),
        headers: Object.assign({}, opts.headers || {}),
        timeout: Math.min(60000, Math.max(1000, Number(opts.timeout || 15000))),
        binary: opts.binary === true,
      };
      if (opts.body != null) config.body = opts.body;
      else if (opts.form != null) {
        config.body = qs.stringify(opts.form);
        if (!Object.keys(config.headers).some(key => key.toLowerCase() === 'content-type')) {
          config.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
        }
      } else if (opts.formData != null) {
        config.body = qs.stringify(opts.formData);
        if (!Object.keys(config.headers).some(key => key.toLowerCase() === 'content-type')) {
          config.headers['Content-Type'] = 'application/x-www-form-urlencoded;charset=UTF-8';
        }
      }
      let aborted = false;
      hostHttp(config).then(response => {
        if (aborted) return;
        let body = response.data;
        if (response.binaryBase64) body = BufferCompat.from(String(response.data || ''), 'base64');
        const compatResponse = {
          statusCode: Number(response.status || 0),
          statusMessage: String(response.statusText || ''),
          headers: response.headers || {},
          body,
        };
        if (typeof callback === 'function') callback(null, compatResponse, body);
      }).catch(error => {
        if (!aborted && typeof callback === 'function') callback(error instanceof Error ? error : new Error(String(error)), null, null);
      });
      return () => { aborted = true; };
    }

    const utils = {
      crypto: {
        md5(value) { return CryptoJS.MD5(encodeURIComponent(String(value || ''))).toString(CryptoJS.enc.Hex); },
        randomBytes(size) { return BufferCompat.from(CryptoJS.lib.WordArray.random(Math.max(0, Number(size) || 0))._bytes); },
        aesEncrypt(buffer, modeName, key, iv) {
          const modeText = String(modeName || '').toLowerCase();
          const config = {
            mode: modeText.includes('ecb') ? CryptoJS.mode.ECB : CryptoJS.mode.CBC,
            padding: modeText.includes('ecb') ? CryptoJS.pad.NoPadding : CryptoJS.pad.Pkcs7,
            iv: new WordArray(BufferCompat.from(iv || [])),
          };
          const result = CryptoJS.AES.encrypt(
            new WordArray(BufferCompat.from(buffer || [])),
            new WordArray(BufferCompat.from(key || [])),
            config,
          );
          return BufferCompat.from(result.ciphertext._bytes);
        },
        rsaEncrypt(buffer, key) {
          const normalizedKey = String(key || '')
            .replace(/-----BEGIN PUBLIC KEY-----/g, '')
            .replace(/-----END PUBLIC KEY-----/g, '')
            .replace(/\s+/g, '');
          const output = hostCrypto({
            operation: 'rsa',
            padding: 'NoPadding',
            data: new WordArray(BufferCompat.from(buffer || [])),
            key: new WordArray(BufferCompat.from(normalizedKey, 'base64')),
          });
          return BufferCompat.from(output._bytes);
        },
      },
      buffer: {
        from(input, encoding) { return BufferCompat.from(input, encoding); },
        bufToString(input, encoding) { return BufferCompat.from(input || []).toString(encoding || 'utf8'); },
      },
    };

    const lx = {
      EVENT_NAMES,
      request: lxRequest,
      send(eventName, data) {
        if (eventName === EVENT_NAMES.inited) {
          if (initInfo != null) return Promise.reject(new Error('Script is inited'));
          initInfo = data && typeof data === 'object' ? data : { status: false, errorMessage: 'Invalid init data' };
          return Promise.resolve();
        }
        if (eventName === EVENT_NAMES.updateAlert) return Promise.resolve();
        return Promise.reject(new Error('The event is not supported: ' + eventName));
      },
      on(eventName, handler) {
        if (eventName !== EVENT_NAMES.request || typeof handler !== 'function') {
          return Promise.reject(new Error('The event is not supported: ' + eventName));
        }
        requestHandler = handler;
        return Promise.resolve();
      },
      utils,
      currentScriptInfo: { name: sourceName, description: '', version: '', author: '', homepage: '', rawScript: '' },
      version: '2.0.0',
      env: 'mobile',
    };
    const sandbox = { lx, console, setTimeout, clearTimeout, Buffer: BufferCompat, URL, TextEncoder, TextDecoder };
    sandbox.globalThis = sandbox;
    sandbox.window = sandbox;
    sandbox.self = sandbox;
    return {
      lx,
      sandbox,
      get initInfo() { return initInfo; },
      get requestHandler() { return requestHandler; },
    };
  }

  async function lxWaitForInit(runtime) {
    const started = Date.now();
    while (runtime.initInfo == null && Date.now() - started < 8000) {
      await new Promise(resolve => setTimeout(resolve, 20));
    }
    if (runtime.initInfo == null) throw new Error('源未调用 lx.send(EVENT_NAMES.inited)');
    if (runtime.initInfo.status === false) throw new Error(String(runtime.initInfo.errorMessage || 'LX 源初始化失败'));
    if (typeof runtime.requestHandler !== 'function') throw new Error('源未注册 lx.on(EVENT_NAMES.request)');
  }

  function lxRestoreItem(itemPayload) {
    const item = restoreMediaItem(itemPayload, '');
    let envelope = {};
    try { envelope = JSON.parse(String(itemPayload || '{}')); } catch (_) {}
    if (!item.durationMs && envelope.durationMs) item.durationMs = Number(envelope.durationMs) || 0;
    if (!item.artwork && envelope.artworkUrl) item.artwork = envelope.artworkUrl;
    return item;
  }

  window.__rawLxSource = {
    async mount(id, sourceName, hash, script) {
      try {
        if (lxSources[id] && lxHashes[id] === hash) return JSON.stringify({ ok: true, payload: { mounted: true } });
        const runtime = lxCreateRuntime(id, sourceName);
        const sandbox = runtime.sandbox;
        const factory = new Function(
          'lx', 'console', 'setTimeout', 'clearTimeout', 'Buffer', 'URL', 'TextEncoder', 'TextDecoder',
          'globalThis', 'window', 'self',
          String(script || '') + '\n//# sourceURL=rawsmusic-lx-source-' + id + '.js'
        );
        factory.call(
          sandbox, runtime.lx, console, setTimeout, clearTimeout, BufferCompat, URL, TextEncoder, TextDecoder,
          sandbox, sandbox, sandbox,
        );
        await lxWaitForInit(runtime);
        runtime.lx.currentScriptInfo.rawScript = String(script || '');
        lxSources[id] = runtime;
        lxHashes[id] = hash;
        return JSON.stringify({ ok: true, payload: { mounted: true, info: runtime.initInfo } });
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error && error.message || error) });
      }
    },

    async resolveAudio(id, sourceName, itemPayload, quality) {
      try {
        const runtime = lxSources[id];
        if (!runtime) throw new Error('LX 源尚未挂载');
        const item = lxRestoreItem(itemPayload);
        const platform = String(item.source || item.platform || '').trim();
        const songmid = String(item.songmid || item.songId || item.id || '').replace(/^\w+_/, '').trim();
        if (!platform || !songmid) throw new Error('LX 搜索结果缺少平台或歌曲 ID');
        const sourcesInfo = runtime.initInfo && runtime.initInfo.sources;
        const platformInfo = sourcesInfo && sourcesInfo[platform];
        if (!platformInfo) throw new Error('当前 LX 源不支持 ' + platform);
        const actions = Array.isArray(platformInfo.actions) ? platformInfo.actions : [];
        if (actions.length && !actions.includes('musicUrl')) throw new Error('当前 LX 源不支持播放地址解析');
        const requestedQuality = lxQualityFallback(platformInfo.qualitys, quality);
        const info = {
          type: requestedQuality,
          musicInfo: lxBuildMusicInfo(item, platform, songmid, requestedQuality),
        };
        const result = await withTimeout(
          runtime.requestHandler.call(runtime.lx, { source: platform, action: 'musicUrl', info }),
          25000,
        );
        const url = typeof result === 'string' ? result : String(result && (result.url || result.data && result.data.url) || '');
        if (!/^https?:\/\//i.test(url)) throw new Error('LX 源没有返回有效播放地址');
        return JSON.stringify({ ok: true, payload: { url, headers: {}, quality: requestedQuality } });
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error && error.message || error) });
      }
    },

    async getLyric(id, sourceName, itemPayload) {
      try {
        const runtime = lxSources[id];
        if (!runtime) throw new Error('LX 源尚未挂载');
        const item = lxRestoreItem(itemPayload);
        const platform = String(item.source || item.platform || '').trim();
        const songmid = String(item.songmid || item.songId || item.id || '').replace(/^\w+_/, '').trim();
        const sourcesInfo = runtime.initInfo && runtime.initInfo.sources;
        const platformInfo = sourcesInfo && sourcesInfo[platform];
        const actions = Array.isArray(platformInfo && platformInfo.actions) ? platformInfo.actions : [];
        if (!platformInfo || !actions.includes('lyric')) throw new Error('当前 LX 源不支持歌词解析');
        const info = { musicInfo: lxBuildMusicInfo(item, platform, songmid, '128k') };
        let result = await withTimeout(
          runtime.requestHandler.call(runtime.lx, { source: platform, action: 'lyric', info }),
          20000,
        );
        if (typeof result === 'string') result = { lyric: result };
        if (!result || typeof result !== 'object') throw new Error('LX 源没有返回歌词');
        return JSON.stringify({
          ok: true,
          payload: {
            original: String(result.lyric || result.rawLrc || ''),
            translation: String(result.tlyric || result.translation || ''),
            romanization: String(result.rlyric || result.romanization || ''),
            wordByWord: String(result.lxlyric || result.wordByWord || ''),
          },
        });
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error && error.message || error) });
      }
    },
  };

  window.__rawMusicSource = {
    async mount(id, hash, script) {
      try {
        if (sources[id] && hashes[id] === hash) return JSON.stringify({ ok: true, payload: { mounted: true } });
        const module = { exports: {} };
        const exports = module.exports;
        const env = { appVersion: '0.9.61 beta', os: 'android', lang: 'zh-CN', userVariables: {} };
        const process = { platform: 'android', version: '0.9.61 beta', env };
        const factory = new Function('require', '__musicfree_require', 'module', 'exports', 'console', 'env', 'URL', 'process', script + '\n//# sourceURL=rawsmusic-source-' + id + '.js');
        factory(requireModule, requireModule, module, exports, console, env, URL, process);
        const plugin = module.exports && module.exports.default ? module.exports.default : module.exports;
        if (!plugin || typeof plugin !== 'object') throw new Error('插件没有导出对象');
        if (!plugin.platform) throw new Error('插件缺少 platform 字段');
        sources[id] = plugin;
        hashes[id] = hash;
        return JSON.stringify({ ok: true, payload: { mounted: true, platform: String(plugin.platform) } });
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error && error.message || error) });
      }
    },

    async search(id, sourceName, query, page, type) {
      try {
        const plugin = sources[id];
        if (!plugin) throw new Error('音源尚未挂载');
        if (typeof plugin.search !== 'function') throw new Error('音源不支持搜索');
        const result = await withTimeout(plugin.search(String(query || ''), Number(page || 1), String(type || 'music')), 12000);
        const data = Array.isArray(result && result.data) ? result.data : [];
        const payload = {
          sourceId: id,
          sourceName: sourceName,
          isEnd: result && result.isEnd !== false,
          items: data.slice(0, 100).map(item => normalizeItem(item, plugin.platform || sourceName, type)),
        };
        return JSON.stringify({ ok: true, payload });
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error && error.message || error) });
      }
    },

    async resolveAudio(id, sourceName, itemPayload, quality) {
      try {
        const plugin = sources[id];
        if (!plugin) throw new Error('音源尚未挂载');
        const requestedQuality = String(quality || 'standard');
        const item = restoreMediaItem(itemPayload, plugin.platform || sourceName);
        let result;
        if (typeof plugin.getMediaSource === 'function') {
          result = await withTimeout(plugin.getMediaSource(item, requestedQuality), 15000);
        } else {
          const qualityInfo = item.qualities && typeof item.qualities === 'object'
            ? item.qualities[requestedQuality]
            : null;
          result = {
            url: qualityInfo && qualityInfo.url ? qualityInfo.url : item.url,
            headers: item.headers,
            userAgent: item.userAgent,
            quality: requestedQuality,
          };
        }
        if (typeof result === 'string') result = { url: result };
        if (!result || typeof result !== 'object') throw new Error('音源没有返回播放地址');
        const fallbackQualityInfo = item.qualities && typeof item.qualities === 'object'
          ? item.qualities[requestedQuality]
          : null;
        const url = String(result.url || (fallbackQualityInfo && fallbackQualityInfo.url) || item.url || '');
        if (!url) throw new Error('音源没有返回播放地址');
        const payload = {
          url,
          headers: normalizeHeaders(result.headers, result.userAgent),
          quality: String(result.quality || requestedQuality),
        };
        return JSON.stringify({ ok: true, payload });
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error && error.message || error) });
      }
    },

    async getLyric(id, sourceName, itemPayload) {
      try {
        const plugin = sources[id];
        if (!plugin) throw new Error('音源尚未挂载');
        const item = restoreMediaItem(itemPayload, plugin.platform || sourceName);
        let result;
        if (typeof plugin.getLyric === 'function') {
          result = await withTimeout(plugin.getLyric(item), 15000);
        } else {
          result = item.lyric || item.lrc || item.rawLrc || item.rawLrcTxt || '';
        }
        if (typeof result === 'string') result = { rawLrc: result };
        if (!result || typeof result !== 'object') throw new Error('音源没有返回歌词');
        const firstText = (...values) => {
          for (const value of values) {
            if (typeof value === 'string' && value.trim()) return value;
          }
          return '';
        };
        const original = firstText(
          result.rawLrc, result.lyric, result.lrc, result.rawLyric,
          result.rawLrcTxt, result.original, result.content, item.rawLrc,
          item.lyric, item.lrc
        );
        const translation = firstText(
          result.translation, result.trans, result.tlyric, result.translatedLyric,
          result.rawTranslation, item.translation, item.tlyric
        );
        const romanization = firstText(
          result.romanization, result.romalrc, result.rlyric, result.romaLyric,
          item.romanization, item.rlyric
        );
        const wordByWord = firstText(
          result.wordByWord, result.lxlyric, result.klyric, result.yrc,
          item.wordByWord, item.lxlyric, item.klyric, item.yrc
        );
        if (!original && !wordByWord) throw new Error('音源没有返回可用歌词');
        return JSON.stringify({
          ok: true,
          payload: { original, translation, romanization, wordByWord },
        });
      } catch (error) {
        return JSON.stringify({ ok: false, error: String(error && error.message || error) });
      }
    },
  };
})();
</script></body></html>"""
    }
}
