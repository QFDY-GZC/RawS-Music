package com.rawsmusic.module.scanner.webdav

import android.util.Log
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.Route
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

data class WebDavConfig(
    val url: String,
    val username: String = "",
    val password: String = "",
    val authMode: AuthMode = AuthMode.AUTO,
    /** Reserved for reverse proxies / share endpoints without changing the core protocol API. */
    val extraHeaders: Map<String, String> = emptyMap(),
)

enum class AuthMode {
    AUTO,
    BASIC,
    DIGEST,
}

data class WebDavItem(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: String = "",
    val contentType: String = "",
    /** Canonical absolute URL resolved against the directory that produced this item. */
    val resolvedUrl: String = "",
) {
    val fileName: String
        get() = displayName.ifBlank {
            resolvedUrl.toHttpUrlOrNull()?.pathSegments?.lastOrNull { it.isNotBlank() }
                ?: href.trimEnd('/').substringAfterLast('/')
        }
}

data class WebDavTestResult(
    val success: Boolean,
    val message: String,
    val canonicalUrl: String = "",
)

data class WebDavPlaybackRequest(
    val url: String,
    val headers: Map<String, String>,
    val userAgent: String,
)

data class WebDavDirectoryResult(
    val canonicalUrl: String,
    val items: List<WebDavItem>,
)

class WebDavException(
    message: String,
    val statusCode: Int? = null,
    val requestUrl: String? = null,
) : Exception(message)

class WebDavClient {
    companion object {
        private const val TAG = "WebDavClient"
        private const val USER_AGENT = "RawSMusic/1.0"
        private const val AUTH_PROBE_FRESH_MS = 120_000L
    }

    private val digestAuth = WebDavDigestAuth()
    private val lastAuthSchemeByOrigin = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val digestChallengeByOrigin = java.util.concurrent.ConcurrentHashMap<String, CachedDigestChallenge>()
    private val authProbeAtByOrigin = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val cookieStore = CopyOnWriteArrayList<Cookie>()

    private data class CachedDigestChallenge(
        val challenge: WebDavAuthChallenge,
        val capturedAtMs: Long,
    )

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .callTimeout(90, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .addNetworkInterceptor { chain ->
                val original = chain.request()
                val config = original.tag(WebDavConfig::class.java)
                if (config == null || WebDavUrlTools.isCredentialSafeTarget(config.url, original.url.toString())) {
                    chain.proceed(original)
                } else {
                    val sanitized = original.newBuilder()
                        .removeHeader("Authorization")
                        .removeHeader("Proxy-Authorization")
                        .removeHeader("Cookie")
                    config.extraHeaders.sanitizedHeaders().keys.forEach(sanitized::removeHeader)
                    Log.w(TAG, "Stripped WebDAV credentials from redirect to ${original.url.host}")
                    chain.proceed(sanitized.build())
                }
            }
            .cookieJar(object : CookieJar {
                override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                    cookies.forEach { incoming ->
                        cookieStore.removeAll { old ->
                            old.name == incoming.name && old.domain == incoming.domain && old.path == incoming.path
                        }
                        if (incoming.expiresAt > System.currentTimeMillis()) cookieStore += incoming
                    }
                }

                override fun loadForRequest(url: HttpUrl): List<Cookie> {
                    val now = System.currentTimeMillis()
                    cookieStore.removeAll { it.expiresAt <= now }
                    return cookieStore.filter { it.matches(url) }
                }
            })
            .authenticator { _: Route?, response: Response -> authenticate(response) }
            .build()
    }

    /**
     * Used only when direct FFmpeg HTTP open has failed and playback falls back to a full local
     * cache file. Keep directory/auth traffic on the normal bounded client, but do not impose the
     * 90-second total-call limit on large media bodies.
     */
    private val streamingHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder()
            .readTimeout(5, TimeUnit.MINUTES)
            .callTimeout(0, TimeUnit.MILLISECONDS)
            .build()
    }

    fun testConnection(config: WebDavConfig): WebDavTestResult {
        return try {
            val url = canonicalizeCollectionUrl(config.url)
            httpClient.newCall(buildPropfindRequest(url, config, depth = "0")).execute().use { response ->
                if (!response.isSuccessful) throw responseException(response)
                val canonical = WebDavUrlTools.normalizeCollectionUrl(response.request.url.toString())
                WebDavTestResult(
                    success = true,
                    message = "连接成功 (${authDescription(config, response.request.url)})",
                    canonicalUrl = canonical,
                )
            }
        } catch (error: Throwable) {
            Log.e(TAG, "testConnection failed", error)
            WebDavTestResult(false, error.message ?: "连接失败")
        }
    }

    fun listDirectory(config: WebDavConfig, path: String = ""): List<WebDavItem> =
        listDirectoryResult(config, path).items

    fun listDirectoryResult(config: WebDavConfig, path: String = ""): WebDavDirectoryResult {
        val requestedUrl = canonicalizeCollectionUrl(path.ifBlank { config.url })
        return try {
            httpClient.newCall(buildPropfindRequest(requestedUrl, config, depth = "1")).execute().use { response ->
                if (!response.isSuccessful) throw responseException(response)
                val xml = response.body?.string() ?: throw WebDavException("服务器返回了空的 PROPFIND 响应")
                val finalUrl = WebDavUrlTools.normalizeCollectionUrl(response.request.url.toString())
                val selfIdentity = WebDavUrlTools.identity(finalUrl)
                val items = WebDavMultiStatusParser.parse(xml)
                    .mapNotNull { parsed ->
                        val resolved = runCatching {
                            WebDavUrlTools.resolveHref(finalUrl, parsed.href, parsed.isDirectory)
                        }.onFailure {
                            Log.w(TAG, "Ignoring invalid DAV href=${parsed.href}", it)
                        }.getOrNull() ?: return@mapNotNull null
                        WebDavItem(
                            href = parsed.href,
                            displayName = parsed.displayName,
                            isDirectory = parsed.isDirectory,
                            size = parsed.size,
                            lastModified = parsed.lastModified,
                            contentType = parsed.contentType,
                            resolvedUrl = resolved,
                        )
                    }
                    .filter { !it.isDirectory || WebDavUrlTools.identity(it.resolvedUrl) != selfIdentity }
                    .distinctBy { it.resolvedUrl }
                WebDavDirectoryResult(canonicalUrl = finalUrl, items = items)
            }
        } catch (error: WebDavException) {
            Log.e(TAG, "listDirectory failed url=$requestedUrl status=${error.statusCode}", error)
            throw error
        } catch (error: Throwable) {
            Log.e(TAG, "listDirectory failed url=$requestedUrl", error)
            throw WebDavException(error.message ?: "加载 WebDAV 目录失败", requestUrl = requestedUrl)
        }
    }

    fun canonicalizeCollectionUrl(url: String): String = WebDavUrlTools.normalizeCollectionUrl(url)

    fun canonicalizeResourceUrl(url: String): String = WebDavUrlTools.normalizeResourceUrl(url)

    fun resolveChildUrl(baseUrl: String, child: String, directory: Boolean): String =
        WebDavUrlTools.resolveChild(baseUrl, child, directory)

    fun buildFileUrl(config: WebDavConfig, href: String): String =
        WebDavUrlTools.resolveHref(config.url, href)

    fun buildFileUrl(currentDirectoryUrl: String, href: String, isDirectory: Boolean): String =
        WebDavUrlTools.resolveHref(currentDirectoryUrl, href, isDirectory)

    /**
     * Legacy compatibility API. Credentials are intentionally no longer embedded in media URLs;
     * persisted queue playback is rehydrated through WebDavPlaybackCredentialResolver instead.
     */
    fun buildAuthenticatedUrl(config: WebDavConfig, href: String): String {
        val rawUrl = if (href.startsWith("http://", true) || href.startsWith("https://", true)) {
            canonicalizeResourceUrl(href)
        } else {
            buildFileUrl(config, href)
        }
        return WebDavUrlTools.stripUserInfo(rawUrl)
    }

    fun buildPlaybackRequest(config: WebDavConfig, item: WebDavItem): WebDavPlaybackRequest {
        val rawUrl = WebDavUrlTools.stripUserInfo(item.resolvedUrl.ifBlank { buildFileUrl(config, item.href) })
        return buildPlaybackRequestForUrl(config, rawUrl, refreshAuthentication = false)
    }

    fun buildPlaybackRequestForUrl(
        config: WebDavConfig,
        targetUrl: String,
        refreshAuthentication: Boolean,
    ): WebDavPlaybackRequest {
        val rawUrl = WebDavUrlTools.stripUserInfo(canonicalizeResourceUrl(targetUrl))
        return WebDavPlaybackRequest(
            url = rawUrl,
            headers = buildPlaybackHeaders(config, rawUrl, refreshAuthentication),
            userAgent = effectiveUserAgent(config),
        )
    }

    fun buildPlaybackHeaders(
        config: WebDavConfig,
        targetUrl: String,
        refreshAuthentication: Boolean,
    ): Map<String, String> {
        val rawUrl = WebDavUrlTools.stripUserInfo(canonicalizeResourceUrl(targetUrl))
        if (!WebDavUrlTools.isCredentialSafeTarget(config.url, rawUrl)) return emptyMap()
        val parsed = rawUrl.toHttpUrlOrNull() ?: return emptyMap()
        val origin = WebDavUrlTools.origin(parsed)

        if (refreshAuthentication && shouldRefreshAuthentication(config, origin)) {
            refreshAuthentication(config, rawUrl)
        }

        val knownScheme = lastAuthSchemeByOrigin[origin]
        return buildMap {
            putAll(config.extraHeaders.sanitizedHeaders().filterKeys { !it.equals("User-Agent", true) })
            matchingCookies(parsed).takeIf { it.isNotBlank() }?.let { sessionCookies ->
                val explicitCookies = entries.firstOrNull { it.key.equals("Cookie", true) }?.value
                val combined = listOfNotNull(explicitCookies?.takeIf { it.isNotBlank() }, sessionCookies)
                    .joinToString("; ")
                keys.firstOrNull { it.equals("Cookie", true) }?.let(::remove)
                put("Cookie", combined)
            }
            if (config.username.isBlank()) return@buildMap

            when {
                config.authMode == AuthMode.BASIC || knownScheme.equals("Basic", true) -> {
                    put("Authorization", Credentials.basic(config.username, config.password))
                }
                config.authMode == AuthMode.DIGEST || knownScheme.equals("Digest", true) -> {
                    val challenge = digestChallengeByOrigin[origin]?.challenge ?: return@buildMap
                    val getRequest = Request.Builder().url(parsed).get().build()
                    digestAuth.authorization(getRequest, config.username, config.password, challenge)?.let {
                        put("Authorization", it)
                    }
                }
            }
        }
    }

    fun getParentUrl(currentUrl: String, config: WebDavConfig): String? =
        WebDavUrlTools.parentWithinRoot(config.url, currentUrl)

    fun createDirectory(config: WebDavConfig, path: String): Boolean {
        val url = canonicalizeCollectionUrl(path)
        val request = requestBuilder(url, config).method("MKCOL", null).build()
        httpClient.newCall(request).execute().use { response ->
            if (response.isSuccessful) return true
            if (response.code == 405) return exists(config, url)
            throw responseException(response)
        }
    }

    fun uploadFile(config: WebDavConfig, remotePath: String, data: ByteArray): Boolean {
        val url = canonicalizeResourceUrl(remotePath)
        val body = data.toRequestBody("application/octet-stream".toMediaType())
        httpClient.newCall(requestBuilder(url, config).put(body).build()).execute().use { response ->
            if (!response.isSuccessful) throw responseException(response)
            return true
        }
    }

    fun downloadFile(config: WebDavConfig, remotePath: String): ByteArray? {
        val url = canonicalizeResourceUrl(remotePath)
        httpClient.newCall(requestBuilder(url, config).get().build()).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw responseException(response)
            return response.body?.bytes()
        }
    }

    /**
     * Opens a streaming WebDAV resource request for the local range bridge. The returned response
     * owns the network body and must be closed by the caller. Range is forwarded verbatim so FFmpeg
     * can seek without downloading the whole object first.
     */
    internal fun openStreamingResponse(
        config: WebDavConfig,
        remotePath: String,
        rangeHeader: String?,
        headOnly: Boolean,
    ): Response {
        val url = canonicalizeResourceUrl(remotePath)
        val builder = requestBuilder(url, config)
            .header("Accept-Encoding", "identity")
        rangeHeader?.takeIf { it.isNotBlank() }?.let { builder.header("Range", it) }
        val request = if (headOnly) builder.head().build() else builder.get().build()
        return streamingHttpClient.newCall(request).execute()
    }

    fun exists(config: WebDavConfig, path: String): Boolean {
        val directory = path.trim().substringBefore('?').endsWith('/')
        val url = WebDavUrlTools.normalize(path, directory)
        httpClient.newCall(buildPropfindRequest(url, config, depth = "0")).execute().use { response ->
            if (response.isSuccessful) return true
            if (response.code == 404) return false
            if (response.code !in setOf(405, 501)) throw responseException(response)
        }
        httpClient.newCall(requestBuilder(url, config).head().build()).execute().use { response ->
            if (response.code == 404) return false
            if (!response.isSuccessful) throw responseException(response)
            return true
        }
    }

    private fun buildPropfindRequest(url: String, config: WebDavConfig, depth: String): Request {
        val propfindXml = """<?xml version="1.0" encoding="utf-8"?>
            |<d:propfind xmlns:d="DAV:">
            |  <d:prop>
            |    <d:displayname/>
            |    <d:getcontentlength/>
            |    <d:getlastmodified/>
            |    <d:getcontenttype/>
            |    <d:resourcetype/>
            |  </d:prop>
            |</d:propfind>""".trimMargin()
        val body = propfindXml.toRequestBody("application/xml; charset=utf-8".toMediaType())
        return requestBuilder(url, config)
            .method("PROPFIND", body)
            .header("Depth", depth)
            .header("Content-Type", "application/xml; charset=utf-8")
            .build()
    }

    private fun requestBuilder(url: String, config: WebDavConfig): Request.Builder {
        val credentialSafe = WebDavUrlTools.isCredentialSafeTarget(config.url, url)
        val builder = Request.Builder()
            .url(url)
            .tag(WebDavConfig::class.java, config)
            .header("User-Agent", effectiveUserAgent(config))
            .header("Accept", "*/*")
        if (credentialSafe) {
            config.extraHeaders.sanitizedHeaders()
                .filterKeys { !it.equals("User-Agent", true) }
                .forEach { (name, value) -> builder.header(name, value) }
        }
        val knownScheme = url.toHttpUrlOrNull()?.let(WebDavUrlTools::origin)?.let(lastAuthSchemeByOrigin::get)
        if (
            credentialSafe &&
            config.username.isNotBlank() &&
            (config.authMode == AuthMode.BASIC || knownScheme.equals("Basic", true))
        ) {
            builder.header("Authorization", Credentials.basic(config.username, config.password))
        }
        return builder
    }

    private fun authenticate(response: Response): Request? {
        val config = response.request.tag(WebDavConfig::class.java) ?: return null
        if (!WebDavUrlTools.isCredentialSafeTarget(config.url, response.request.url.toString())) return null
        if (config.username.isBlank() || responseCount(response) >= 4) return null
        val challenges = digestAuth.parseChallenges(response.headers("WWW-Authenticate"))
        if (challenges.isEmpty()) return null
        val digest = challenges.firstOrNull { it.scheme.equals("Digest", true) }
        val basic = challenges.firstOrNull { it.scheme.equals("Basic", true) }
        fun basicAuthorization(): String? {
            if (basic == null) return null
            val basicValue = Credentials.basic(config.username, config.password)
            if (response.request.header("Authorization") == basicValue) return null
            return basicValue
        }

        val chosenAuthorization: Pair<String, String> = when (config.authMode) {
            AuthMode.DIGEST -> {
                val challenge = digest ?: return null
                cacheDigestChallenge(response.request.url, challenge)
                "Digest" to (digestAuth.authorization(response.request, config.username, config.password, challenge) ?: return null)
            }
            AuthMode.BASIC -> "Basic" to (basicAuthorization() ?: return null)
            AuthMode.AUTO -> {
                val digestValue = digest?.let {
                    cacheDigestChallenge(response.request.url, it)
                    digestAuth.authorization(response.request, config.username, config.password, it)
                }
                if (digestValue != null) "Digest" to digestValue
                else "Basic" to (basicAuthorization() ?: return null)
            }
        }
        val (chosenScheme, authorization) = chosenAuthorization

        lastAuthSchemeByOrigin[WebDavUrlTools.origin(response.request.url)] = chosenScheme
        authProbeAtByOrigin[WebDavUrlTools.origin(response.request.url)] = System.currentTimeMillis()
        Log.d(TAG, "Authentication challenge resolved with $chosenScheme for ${response.request.url.host}")
        return response.request.newBuilder().header("Authorization", authorization).build()
    }

    private fun shouldRefreshAuthentication(config: WebDavConfig, origin: String): Boolean {
        if (config.username.isBlank() || config.authMode == AuthMode.BASIC) return false
        val now = System.currentTimeMillis()
        val knownScheme = lastAuthSchemeByOrigin[origin]
        val lastProbe = authProbeAtByOrigin[origin] ?: 0L
        if (knownScheme == null) return now - lastProbe > AUTH_PROBE_FRESH_MS
        if (!knownScheme.equals("Digest", true)) return false
        val challenge = digestChallengeByOrigin[origin] ?: return true
        return now - challenge.capturedAtMs > AUTH_PROBE_FRESH_MS
    }

    private fun refreshAuthentication(config: WebDavConfig, targetUrl: String) {
        val parsed = targetUrl.toHttpUrlOrNull() ?: return
        val origin = WebDavUrlTools.origin(parsed)
        runCatching {
            var needsPropfind = false
            httpClient.newCall(requestBuilder(targetUrl, config).head().build()).execute().use { response ->
                needsPropfind = response.code in setOf(405, 501)
                if (!response.isSuccessful && !needsPropfind) {
                    Log.w(TAG, "WebDAV auth HEAD preflight code=${response.code} host=${response.request.url.host}")
                }
            }
            if (needsPropfind) {
                httpClient.newCall(buildPropfindRequest(targetUrl, config, depth = "0")).execute().use { response ->
                    if (!response.isSuccessful) {
                        Log.w(TAG, "WebDAV auth PROPFIND preflight code=${response.code} host=${response.request.url.host}")
                    }
                }
            }
        }.onFailure { error ->
            Log.w(TAG, "WebDAV playback auth preflight failed host=${parsed.host}: ${error.message}")
        }
        authProbeAtByOrigin[origin] = System.currentTimeMillis()
    }

    private fun cacheDigestChallenge(url: HttpUrl, challenge: WebDavAuthChallenge) {
        digestChallengeByOrigin[WebDavUrlTools.origin(url)] = CachedDigestChallenge(
            challenge = challenge,
            capturedAtMs = System.currentTimeMillis(),
        )
    }

    private fun matchingCookies(url: HttpUrl): String = cookieStore
        .filter { it.expiresAt > System.currentTimeMillis() && it.matches(url) }
        .joinToString("; ") { "${it.name}=${it.value}" }

    private fun responseCount(response: Response): Int {
        var count = 0
        var current: Response? = response
        while (current != null) {
            if (current.code == 401 || current.code == 407) count++
            current = current.priorResponse
        }
        return count
    }

    private fun authDescription(config: WebDavConfig, url: HttpUrl): String {
        if (config.username.isBlank()) return "无认证"
        return lastAuthSchemeByOrigin[WebDavUrlTools.origin(url)] ?: when (config.authMode) {
            AuthMode.BASIC -> "Basic"
            AuthMode.DIGEST -> "Digest"
            AuthMode.AUTO -> "自动认证"
        }
    }

    private fun responseException(response: Response): WebDavException {
        val url = response.request.url.toString()
        val detail = response.body?.string()?.replace('\n', ' ')?.replace('\r', ' ')?.take(240).orEmpty()
        val message = when (response.code) {
            400 -> "服务器拒绝了 WebDAV 请求 (400)"
            401 -> "WebDAV 认证失败 (401)：请检查用户名、密码或认证方式"
            403 -> "WebDAV 访问被拒绝 (403)：账号没有当前路径权限"
            404 -> "WebDAV 路径不存在 (404)：请检查服务器地址和根目录"
            405 -> "服务器不允许此 WebDAV 方法 (405)"
            409 -> "WebDAV 路径冲突 (409)：上级目录可能不存在"
            423 -> "WebDAV 资源已锁定 (423)"
            429 -> "WebDAV 请求过于频繁 (429)"
            in 500..599 -> "WebDAV 服务器错误 (${response.code})"
            else -> "WebDAV 请求失败：HTTP ${response.code}"
        } + detail.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty() +
            if (detail.contains("Client type mismatch", ignoreCase = true)) {
                " · 服务器要求匹配客户端 User-Agent；可在自定义 HTTP Header 中设置，例如 User-Agent: Zotero/8.0"
            } else {
                ""
            }
        return WebDavException(message, response.code, url)
    }

    private fun effectiveUserAgent(config: WebDavConfig): String =
        config.extraHeaders.entries
            .firstOrNull { it.key.equals("User-Agent", true) }
            ?.value
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: USER_AGENT

    private fun Map<String, String>.sanitizedHeaders(): Map<String, String> = buildMap {
        this@sanitizedHeaders.forEach { (rawName, rawValue) ->
            val name = rawName.trim().replace("\r", "").replace("\n", "")
            val value = rawValue.replace("\r", "").replace("\n", "")
            if (name.isNotBlank() && !name.equals("Host", true) && !name.equals("Content-Length", true)) {
                put(name, value)
            }
        }
    }
}
