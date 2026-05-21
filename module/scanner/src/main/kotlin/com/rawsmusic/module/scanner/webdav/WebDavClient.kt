package com.rawsmusic.module.scanner.webdav

import android.util.Log
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Response
import okhttp3.Route
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class WebDavConfig(
    val url: String,
    val username: String = "",
    val password: String = "",
    val authMode: AuthMode = AuthMode.AUTO
)

enum class AuthMode {
    AUTO,
    BASIC,
    DIGEST
}

data class WebDavItem(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val lastModified: String = "",
    val contentType: String = ""
) {
    val fileName: String
        get() = displayName.ifBlank {
            val decoded = URLDecoder.decode(href.trimEnd('/'), "UTF-8")
            decoded.substringAfterLast('/')
        }
}

data class WebDavTestResult(
    val success: Boolean,
    val message: String
)

class WebDavClient {

    companion object {
        private const val TAG = "WebDavClient"
    }

    private var currentConfig: WebDavConfig? = null
    private var authAttemptCount = 0
    private var lastAuthMethod: String? = null

    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .authenticator { route: Route?, response: Response ->
                handleAuth(response)
            }
            .build()
    }

    private fun handleAuth(response: Response): Request? {
        val config = currentConfig ?: return null
        if (config.username.isBlank()) return null

        val existingAuth = response.request.header("Authorization")
        if (existingAuth != null) {
            if (authAttemptCount >= 2) {
                Log.w(TAG, "Auth attempts exhausted (tried $authAttemptCount times). Stopping.")
                return null
            }
            authAttemptCount++
            Log.w(TAG, "Auth rejected (${existingAuth.take(15)}...), attempt $authAttemptCount")
        }

        val authHeaders = response.headers("WWW-Authenticate")
        if (authHeaders.isEmpty()) {
            Log.w(TAG, "No WWW-Authenticate header found")
            return null
        }

        Log.d(TAG, "WWW-Authenticate headers: $authHeaders")

        val parsedAuths = parseAllAuthHeaders(authHeaders)
        Log.d(TAG, "Parsed auth methods: ${parsedAuths.keys}")

        return when (config.authMode) {
            AuthMode.DIGEST -> {
                parsedAuths["Digest"]?.let { tryDigestAuth(response, config, it) }
                    ?: run { Log.w(TAG, "Digest requested but not offered by server"); null }
            }
            AuthMode.BASIC -> {
                parsedAuths["Basic"]?.let { tryBasicAuth(response, config) }
                    ?: run { Log.w(TAG, "Basic requested but not offered by server"); null }
            }
            AuthMode.AUTO -> {
                if (existingAuth?.startsWith("Basic") == true && response.code == 401) {
                    Log.d(TAG, "Basic failed, trying Digest...")
                    parsedAuths["Digest"]?.let { tryDigestAuth(response, config, it) }
                } else {
                    parsedAuths["Digest"]?.let { tryDigestAuth(response, config, it) }
                        ?: parsedAuths["Basic"]?.let { tryBasicAuth(response, config) }
                }
            }
        }
    }

    private fun parseAllAuthHeaders(headers: List<String>): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (header in headers) {
            val trimmed = header.trim()
            when {
                trimmed.startsWith("Digest", ignoreCase = true) -> {
                    result["Digest"] = trimmed
                }
                trimmed.startsWith("Basic", ignoreCase = true) -> {
                    result["Basic"] = trimmed
                }
                trimmed.startsWith("Bearer", ignoreCase = true) -> {
                    result["Bearer"] = trimmed
                }
                trimmed.startsWith("NTLM", ignoreCase = true) -> {
                    result["NTLM"] = trimmed
                }
            }
        }
        
        if (result.isEmpty() && headers.isNotEmpty()) {
            val combined = headers.joinToString(", ")
            if (combined.contains("Digest", ignoreCase = true)) {
                result["Digest"] = combined
            }
            if (combined.contains("Basic", ignoreCase = true)) {
                result["Basic"] = combined
            }
        }
        
        return result
    }

    private fun tryBasicAuth(response: Response, config: WebDavConfig): Request? {
        return try {
            val credentials = Credentials.basic(config.username, config.password)
            Log.d(TAG, "Using Basic auth for user: ${config.username}")
            lastAuthMethod = "Basic"
            response.request.newBuilder()
                .header("Authorization", credentials)
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Basic auth failed", e)
            null
        }
    }

    private fun tryDigestAuth(response: Response, config: WebDavConfig, authHeader: String): Request? {
        try {
            val realm = extractParam(authHeader, "realm") ?: return null
            val nonce = extractParam(authHeader, "nonce") ?: return null
            val qop = extractParam(authHeader, "qop")
            val opaque = extractParam(authHeader, "opaque")
            val algorithm = extractParam(authHeader, "algorithm") ?: "MD5"
            
            val url = response.request.url
            val uri = url.encodedPath + if (url.encodedQuery != null) "?${url.encodedQuery}" else ""
            val method = response.request.method
            val nc = "00000001"
            val cnonce = generateCnonce()

            Log.d(TAG, "Digest params: realm=$realm, nonce=$nonce, qop=$qop, algorithm=$algorithm")

            val ha1 = if (algorithm.equals("MD5-sess", ignoreCase = true)) {
                md5("${md5("${config.username}:$realm:${config.password}")}:$nonce:$cnonce")
            } else {
                md5("${config.username}:$realm:${config.password}")
            }
            val ha2 = md5("$method:$uri")

            val digestResponse = if (qop != null) {
                val qopValue = qop.replace("\"", "").split(",").map { it.trim() }
                    .firstOrNull { it == "auth" } ?: "auth"
                md5("$ha1:$nonce:$nc:$cnonce:$qopValue:$ha2")
            } else {
                md5("$ha1:$nonce:$ha2")
            }

            Log.d(TAG, "Digest HA1=$ha1, HA2=$ha2, response=$digestResponse")

            val authValue = buildString {
                append("Digest ")
                append("""username="${config.username}", """)
                append("""realm="$realm", """)
                append("""nonce="$nonce", """)
                append("""uri="$uri", """)
                append("""response="$digestResponse"""")
                if (algorithm != null) append(""", algorithm=$algorithm""")
                if (opaque != null) append(""", opaque="$opaque"""")
                if (qop != null) append(""", qop=auth""")
                append(""", nc=$nc""")
                append(""", cnonce="$cnonce"""")
            }

            Log.d(TAG, "Using Digest auth: ${authValue.take(100)}...")
            lastAuthMethod = "Digest"
            return response.request.newBuilder()
                .header("Authorization", authValue.trim())
                .build()
        } catch (e: Exception) {
            Log.e(TAG, "Digest auth failed", e)
            return null
        }
    }

    private fun extractParam(header: String, param: String): String? {
        val patterns = listOf(
            Regex("""(?i)$param\s*=\s*"([^"]+)""""),
            Regex("""(?i)$param\s*=\s*([^,\s]+)""")
        )
        for (pattern in patterns) {
            val match = pattern.find(header)
            if (match != null) {
                return match.groupValues[1]
            }
        }
        return null
    }

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateCnonce(): String {
        val chars = "0123456789abcdef"
        return (1..16).map { chars.random() }.joinToString("")
    }

    fun testConnection(config: WebDavConfig): WebDavTestResult {
        currentConfig = config
        authAttemptCount = 0
        lastAuthMethod = null
        return try {
            val url = normalizeUrl(config.url)
            Log.d(TAG, "Testing connection to: $url, user: ${config.username}, authMode: ${config.authMode}")
            val request = buildRequest(url, config, "HEAD")
            val response = httpClient.newCall(request).execute()
            Log.d(TAG, "HEAD response: ${response.code}, auth used: $lastAuthMethod")
            val errorBody = if (!response.isSuccessful) response.body?.string()?.take(500) else null
            if (errorBody != null) Log.d(TAG, "Error body: $errorBody")
            response.close()
            if (response.isSuccessful || response.code == 405) {
                val propfindRequest = buildPropfindRequest(url, config, depth = "0")
                val propfindResponse = httpClient.newCall(propfindRequest).execute()
                val body = propfindResponse.body?.string()
                Log.d(TAG, "PROPFIND response: ${propfindResponse.code}")
                propfindResponse.close()
                if (propfindResponse.isSuccessful && body != null) {
                    WebDavTestResult(true, "连接成功 (${lastAuthMethod ?: "无认证"})")
                } else {
                    Log.e(TAG, "PROPFIND failed: ${propfindResponse.code}")
                    WebDavTestResult(false, "PROPFIND 失败: ${propfindResponse.code}")
                }
            } else {
                Log.e(TAG, "HEAD failed: ${response.code}, body: $errorBody")
                when (response.code) {
                    401 -> WebDavTestResult(false, "认证失败 (401): 用户名或密码错误")
                    403 -> WebDavTestResult(false, "访问被拒绝 (403): 无权限或认证方式不支持")
                    404 -> WebDavTestResult(false, "路径不存在 (404): 请检查 WebDAV 地址")
                    else -> WebDavTestResult(false, "连接失败: HTTP ${response.code}")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "testConnection failed", e)
            WebDavTestResult(false, "连接失败: ${e.message}")
        }
    }

    fun listDirectory(config: WebDavConfig, path: String = ""): List<WebDavItem> {
        currentConfig = config
        authAttemptCount = 0
        lastAuthMethod = null
        val url = if (path.isNotEmpty()) normalizeUrl(path) else normalizeUrl(config.url)
        return listDirectoryInternal(config, url)
    }

    private fun listDirectoryInternal(config: WebDavConfig, url: String): List<WebDavItem> {
        return try {
            val request = buildPropfindRequest(url, config, depth = "1")
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
            response.close()

            if (!response.isSuccessful || body == null) {
                Log.e(TAG, "PROPFIND failed: ${response.code}, url: $url, auth: $lastAuthMethod")
                if (response.code == 401 || response.code == 403) {
                    Log.e(TAG, "Authentication error - check username/password")
                }
                return emptyList()
            }

            val items = parsePropfindResponse(body)
            val currentHref = normalizeHref(url)
            items.filter { normalizeHref(it.href) != currentHref }
        } catch (e: Exception) {
            Log.e(TAG, "listDirectory failed", e)
            emptyList()
        }
    }

    fun buildFileUrl(config: WebDavConfig, href: String): String {
        val baseUrl = normalizeUrl(config.url)
        val rawUrl = if (href.startsWith("http://") || href.startsWith("https://")) {
            href
        } else {
            val base = URI(baseUrl)
            val resolved = base.resolve(href)
            resolved.toString()
        }
        return rawUrl
    }

    fun buildAuthenticatedUrl(config: WebDavConfig, href: String): String {
        val rawUrl = buildFileUrl(config, href)
        if (config.username.isBlank()) return rawUrl
        try {
            val uri = URI(rawUrl)
            val userInfo = "${config.username}:${config.password}"
            return URI(uri.scheme, userInfo, uri.host, uri.port, uri.path, uri.query, uri.fragment).toString()
        } catch (_: Exception) {
            return rawUrl
        }
    }

    fun getParentUrl(currentUrl: String, config: WebDavConfig): String? {
        val baseUrl = normalizeUrl(config.url)
        if (currentUrl.trimEnd('/') == baseUrl.trimEnd('/')) return null
        val current = URI(currentUrl.trimEnd('/'))
        val parent = current.resolve("..")
        val parentStr = parent.toString().trimEnd('/')
        val baseStr = baseUrl.trimEnd('/')
        if (parentStr.length < baseStr.length) return null
        return parentStr
    }

    private fun buildRequest(url: String, config: WebDavConfig, method: String): Request {
        val builder = Request.Builder().url(url).method(method, null)
            .header("User-Agent", "RawSMusic/1.0")
            .header("Accept", "*/*")
        return builder.build()
    }

    private fun buildPropfindRequest(url: String, config: WebDavConfig, depth: String = "1"): Request {
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

        val body = propfindXml.toRequestBody(
            "application/xml; charset=utf-8".toMediaType()
        )
        val builder = Request.Builder()
            .url(url)
            .method("PROPFIND", body)
            .header("Depth", depth)
            .header("Content-Type", "application/xml; charset=utf-8")
            .header("User-Agent", "RawSMusic/1.0")
            .header("Accept", "*/*")

        return builder.build()
    }

    private fun parsePropfindResponse(xml: String): List<WebDavItem> {
        val items = mutableListOf<WebDavItem>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            factory.isNamespaceAware = true
            val parser = factory.newPullParser()
            parser.setInput(StringReader(xml))

            var href = ""
            var displayName = ""
            var contentLength = 0L
            var lastModified = ""
            var contentType = ""
            var isDirectory = false
            var inResponse = false
            var currentTag = ""

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name
                        val namespace = parser.namespace
                        when {
                            name == "response" && namespace == "DAV:" -> {
                                inResponse = true
                                href = ""
                                displayName = ""
                                contentLength = 0L
                                lastModified = ""
                                contentType = ""
                                isDirectory = false
                            }
                            inResponse -> {
                                currentTag = name
                                if (name == "collection" && namespace == "DAV:") {
                                    isDirectory = true
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (inResponse) {
                            val text = parser.text?.trim() ?: ""
                            when (currentTag) {
                                "href" -> href = text
                                "displayname" -> displayName = text
                                "getcontentlength" -> contentLength = text.toLongOrNull() ?: 0L
                                "getlastmodified" -> lastModified = text
                                "getcontenttype" -> contentType = text
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        if (parser.name == "response" && parser.namespace == "DAV:" && inResponse) {
                            inResponse = false
                            if (href.isNotBlank()) {
                                items.add(
                                    WebDavItem(
                                        href = href,
                                        displayName = displayName,
                                        isDirectory = isDirectory,
                                        size = contentLength,
                                        lastModified = lastModified,
                                        contentType = contentType
                                    )
                                )
                            }
                        }
                        currentTag = ""
                    }
                }
                eventType = parser.next()
            }
        } catch (e: Exception) {
            Log.e(TAG, "parsePropfindResponse failed", e)
        }
        return items
    }

    private fun normalizeUrl(url: String): String {
        var normalized = url.trim()
        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        if (!normalized.endsWith("/")) {
            normalized = "$normalized/"
        }
        return normalized
    }

    fun createDirectory(config: WebDavConfig, path: String): Boolean {
        currentConfig = config
        authAttemptCount = 0
        lastAuthMethod = null
        val url = normalizeUrl(path)
        return try {
            val requestBuilder = Request.Builder().url(url).method("MKCOL", null)
            if (config.username.isNotBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(config.username, config.password))
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val success = response.isSuccessful || response.code == 405
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "MKCOL failed", e)
            false
        }
    }

    fun uploadFile(config: WebDavConfig, remotePath: String, data: ByteArray): Boolean {
        currentConfig = config
        authAttemptCount = 0
        lastAuthMethod = null
        val url = normalizeUrl(remotePath).trimEnd('/')
        return try {
            val body = data.toRequestBody("application/octet-stream".toMediaType())
            val requestBuilder = Request.Builder().url(url).put(body)
            if (config.username.isNotBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(config.username, config.password))
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val success = response.isSuccessful
            response.close()
            success
        } catch (e: Exception) {
            Log.e(TAG, "PUT upload failed", e)
            false
        }
    }

    fun downloadFile(config: WebDavConfig, remotePath: String): ByteArray? {
        currentConfig = config
        authAttemptCount = 0
        lastAuthMethod = null
        val url = normalizeUrl(remotePath).trimEnd('/')
        return try {
            val requestBuilder = Request.Builder().url(url).get()
            if (config.username.isNotBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(config.username, config.password))
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val bytes = response.body?.bytes()
            response.close()
            if (response.isSuccessful) bytes else null
        } catch (e: Exception) {
            Log.e(TAG, "GET download failed", e)
            null
        }
    }

    fun exists(config: WebDavConfig, path: String): Boolean {
        currentConfig = config
        authAttemptCount = 0
        lastAuthMethod = null
        val url = normalizeUrl(path).trimEnd('/')
        return try {
            val requestBuilder = Request.Builder().url(url).head()
            if (config.username.isNotBlank()) {
                requestBuilder.header("Authorization", Credentials.basic(config.username, config.password))
            }
            val response = httpClient.newCall(requestBuilder.build()).execute()
            val exists = response.isSuccessful
            response.close()
            exists
        } catch (_: Exception) { false }
    }

    private fun normalizeHref(href: String): String {
        var h = href.trim()
        if (!h.endsWith("/")) h = "$h/"
        return h
    }
}
