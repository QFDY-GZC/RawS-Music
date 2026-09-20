package com.rawsmusic.module.scanner.webdav

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okio.Buffer
import org.xml.sax.Attributes
import org.xml.sax.InputSource
import org.xml.sax.helpers.DefaultHandler
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.xml.parsers.SAXParserFactory

internal object WebDavUrlTools {
    fun normalizeCollectionUrl(value: String): String = normalize(value, directory = true)

    fun normalizeResourceUrl(value: String): String = normalize(value, directory = false)

    fun normalize(value: String, directory: Boolean): String {
        var raw = value.trim()
        require(raw.isNotBlank()) { "WebDAV 地址为空" }
        if (!raw.startsWith("http://", true) && !raw.startsWith("https://", true)) {
            raw = "http://$raw"
        }
        val parsed = raw.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效的 WebDAV 地址")
        val path = parsed.encodedPath.ifBlank { "/" }
        val normalizedPath = when {
            directory && !path.endsWith('/') -> "$path/"
            !directory && path.length > 1 && path.endsWith('/') -> path.dropLast(1)
            else -> path
        }
        return parsed.newBuilder().encodedPath(normalizedPath).build().toString()
    }

    fun resolveHref(baseUrl: String, href: String, directoryHint: Boolean? = null): String {
        val base = normalizeCollectionUrl(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的 WebDAV 基础地址")
        val candidate = href.trim().replace(" ", "%20")
        val resolved = candidate.toHttpUrlOrNull() ?: base.resolve(candidate)
            ?: throw IllegalArgumentException("服务器返回了无效 href: $href")
        val directory = directoryHint ?: resolved.encodedPath.endsWith('/')
        return normalize(resolved.toString(), directory)
    }

    fun resolveChild(baseUrl: String, child: String, directory: Boolean): String {
        val base = normalizeCollectionUrl(baseUrl).toHttpUrlOrNull()
            ?: throw IllegalArgumentException("无效的 WebDAV 基础地址")
        val clean = child.trim().trim('/')
        val builder = base.newBuilder()
        if (clean.isNotEmpty()) builder.addPathSegments(clean)
        var result = builder.build().toString()
        result = normalize(result, directory)
        return result
    }

    fun parentWithinRoot(rootUrl: String, currentUrl: String): String? {
        val root = normalizeCollectionUrl(rootUrl).toHttpUrlOrNull() ?: return null
        val current = normalizeCollectionUrl(currentUrl).toHttpUrlOrNull() ?: return null
        if (!sameOrigin(root, current)) return null
        val rootPath = root.encodedPath.ensureDirectoryPath()
        val currentPath = current.encodedPath.ensureDirectoryPath()
        if (!currentPath.startsWith(rootPath) || currentPath == rootPath) return null
        val withoutTrailing = currentPath.trimEnd('/')
        val parentPath = withoutTrailing.substringBeforeLast('/', missingDelimiterValue = "")
            .ifBlank { "/" }
            .ensureDirectoryPath()
        if (!parentPath.startsWith(rootPath)) return null
        return current.newBuilder().encodedPath(parentPath).build().toString()
    }

    fun identity(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url.trimEnd('/')
        return buildString {
            append(parsed.scheme.lowercase(Locale.ROOT))
            append("://")
            append(parsed.host.lowercase(Locale.ROOT))
            append(':').append(parsed.port)
            append(parsed.encodedPath.ensureDirectoryPath())
        }
    }

    fun origin(url: HttpUrl): String =
        "${url.scheme.lowercase(Locale.ROOT)}://${url.host.lowercase(Locale.ROOT)}:${url.port}"

    /**
     * WebDAV credentials belong to the configured host. A same-host HTTP -> HTTPS upgrade is
     * allowed, but HTTPS -> HTTP downgrade and cross-host absolute hrefs never inherit secrets.
     */
    fun isCredentialSafeTarget(configUrl: String, targetUrl: String): Boolean {
        val configured = normalizeCollectionUrl(configUrl).toHttpUrlOrNull() ?: return false
        val target = targetUrl.toHttpUrlOrNull() ?: return false
        if (!configured.host.equals(target.host, true)) return false
        if (configured.scheme.equals("https", true) && !target.scheme.equals("https", true)) return false
        if (configured.scheme.equals(target.scheme, true)) return configured.port == target.port
        if (configured.scheme.equals("http", true) && target.scheme.equals("https", true)) {
            return (configured.port == 80 && target.port == 443) || configured.port == target.port
        }
        return false
    }

    fun isWithinConfiguredRoot(configUrl: String, targetUrl: String): Boolean {
        if (!isCredentialSafeTarget(configUrl, targetUrl)) return false
        val configured = normalizeCollectionUrl(configUrl).toHttpUrlOrNull() ?: return false
        val target = targetUrl.toHttpUrlOrNull() ?: return false
        val rootPath = configured.encodedPath.ensureDirectoryPath()
        val targetPath = target.encodedPath
        return targetPath == rootPath.trimEnd('/') || targetPath.startsWith(rootPath)
    }

    fun stripUserInfo(url: String): String {
        val parsed = url.toHttpUrlOrNull() ?: return url
        if (parsed.username.isEmpty() && parsed.password.isEmpty()) return parsed.toString()
        return parsed.newBuilder().username("").password("").build().toString()
    }

    private fun sameOrigin(a: HttpUrl, b: HttpUrl): Boolean =
        a.scheme.equals(b.scheme, true) && a.host.equals(b.host, true) && a.port == b.port

    private fun String.ensureDirectoryPath(): String = if (endsWith('/')) this else "$this/"
}

internal data class WebDavAuthChallenge(
    val scheme: String,
    val params: Map<String, String>,
)

internal class WebDavDigestAuth {
    private val nonceCounters = ConcurrentHashMap<String, AtomicInteger>()
    private val random = SecureRandom()

    fun parseChallenges(headers: List<String>): List<WebDavAuthChallenge> {
        if (headers.isEmpty()) return emptyList()
        val combined = headers.joinToString(", ")
        val schemeRegex = Regex("(?i)(?:^|,\\s*)(Basic|Digest)\\s+")
        val matches = schemeRegex.findAll(combined).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.mapIndexed { index, match ->
            val scheme = match.groupValues[1]
            val bodyStart = match.range.last + 1
            val bodyEnd = matches.getOrNull(index + 1)?.range?.first ?: combined.length
            val body = combined.substring(bodyStart, bodyEnd).trim().trimStart(',').trim()
            WebDavAuthChallenge(scheme = scheme, params = parseParams(body))
        }
    }

    fun authorization(request: Request, username: String, password: String, challenge: WebDavAuthChallenge): String? {
        if (!challenge.scheme.equals("Digest", true)) return null
        val realm = challenge.params["realm"] ?: return null
        val nonce = challenge.params["nonce"] ?: return null
        val opaque = challenge.params["opaque"]
        val algorithmRaw = challenge.params["algorithm"].orEmpty().ifBlank { "MD5" }
        val algorithm = algorithmRaw.uppercase(Locale.ROOT)
        val digestName = when (algorithm.removeSuffix("-SESS")) {
            "MD5" -> "MD5"
            "SHA-256" -> "SHA-256"
            "SHA-512-256", "SHA-512/256" -> "SHA-512/256"
            else -> return null
        }
        val qops = challenge.params["qop"]
            ?.split(',')
            ?.map { it.trim().lowercase(Locale.ROOT) }
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val qop = when {
            "auth" in qops -> "auth"
            "auth-int" in qops -> "auth-int"
            qops.isEmpty() -> null
            else -> return null
        }
        val charset = if (challenge.params["charset"].equals("UTF-8", true)) Charsets.UTF_8 else Charsets.ISO_8859_1
        val cnonce = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        val ncValue = nonceCounters.computeIfAbsent(nonce) { AtomicInteger() }.incrementAndGet()
        val nc = "%08x".format(ncValue)
        val uri = request.url.encodedPath + request.url.encodedQuery?.let { "?$it" }.orEmpty()

        fun hash(value: ByteArray): String = MessageDigest.getInstance(digestName)
            .digest(value)
            .joinToString("") { "%02x".format(it) }
        fun hash(value: String): String = hash(value.toByteArray(charset))

        val initialHa1 = hash("$username:$realm:$password")
        val ha1 = if (algorithm.endsWith("-SESS")) hash("$initialHa1:$nonce:$cnonce") else initialHa1
        val ha2 = if (qop == "auth-int") {
            val entityHash = requestBodyBytes(request)?.let(::hash) ?: return null
            hash("${request.method}:$uri:$entityHash")
        } else {
            hash("${request.method}:$uri")
        }
        val response = if (qop != null) {
            hash("$ha1:$nonce:$nc:$cnonce:$qop:$ha2")
        } else {
            hash("$ha1:$nonce:$ha2")
        }
        val userHash = challenge.params["userhash"].equals("true", true)
        val sentUsername = if (userHash) hash("$username:$realm") else username

        return buildString {
            append("Digest username=\"").append(escapeQuoted(sentUsername)).append("\"")
            append(", realm=\"").append(escapeQuoted(realm)).append("\"")
            append(", nonce=\"").append(escapeQuoted(nonce)).append("\"")
            append(", uri=\"").append(escapeQuoted(uri)).append("\"")
            append(", response=\"").append(response).append("\"")
            append(", algorithm=").append(algorithmRaw)
            if (opaque != null) append(", opaque=\"").append(escapeQuoted(opaque)).append("\"")
            if (qop != null) {
                append(", qop=").append(qop)
                append(", nc=").append(nc)
                append(", cnonce=\"").append(cnonce).append("\"")
            }
            if (userHash) append(", userhash=true")
        }
    }

    private fun parseParams(value: String): Map<String, String> {
        val result = linkedMapOf<String, String>()
        val regex = Regex("([A-Za-z0-9_-]+)\\s*=\\s*(?:\"((?:\\\\.|[^\"])*)\"|([^,\\s]+))")
        regex.findAll(value).forEach { match ->
            val key = match.groupValues[1].lowercase(Locale.ROOT)
            val quoted = match.groupValues[2]
            val plain = match.groupValues[3]
            result[key] = (if (quoted.isNotEmpty()) quoted.replace("\\\"", "\"").replace("\\\\", "\\") else plain)
        }
        return result
    }

    private fun requestBodyBytes(request: Request): ByteArray? = runCatching {
        val body = request.body ?: return@runCatching ByteArray(0)
        val buffer = Buffer()
        body.writeTo(buffer)
        buffer.readByteArray()
    }.getOrNull()

    private fun escapeQuoted(value: String): String = value.replace("\\", "\\\\").replace("\"", "\\\"")
}

internal data class ParsedWebDavItem(
    val href: String,
    val displayName: String,
    val isDirectory: Boolean,
    val size: Long,
    val lastModified: String,
    val contentType: String,
)

internal object WebDavMultiStatusParser {
    private data class Props(
        var displayName: String = "",
        var size: Long = 0L,
        var lastModified: String = "",
        var contentType: String = "",
        var isDirectory: Boolean = false,
    )

    fun parse(xml: String): List<ParsedWebDavItem> {
        val result = mutableListOf<ParsedWebDavItem>()

        val factory = SAXParserFactory.newInstance().apply {
            isNamespaceAware = true
            isValidating = false
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
            runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
        }
        factory.newSAXParser().parse(
            InputSource(StringReader(xml)),
            object : DefaultHandler() {
                var inResponse = false
                var inPropstat = false
                var href = ""
                var merged = Props()
                var pending = Props()
                var propstatStatus = 200
                var captureTag: String? = null
                var captureText = StringBuilder()

                override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes?) {
                    val tag = (localName?.takeIf { it.isNotBlank() } ?: qName.orEmpty().substringAfter(':'))
                        .lowercase(Locale.ROOT)
                    when (tag) {
                        "response" -> {
                            inResponse = true
                            inPropstat = false
                            href = ""
                            merged = Props()
                        }
                        "propstat" -> if (inResponse) {
                            inPropstat = true
                            pending = Props()
                            propstatStatus = 200
                        }
                        "collection" -> if (inResponse) {
                            if (inPropstat) pending.isDirectory = true else merged.isDirectory = true
                        }
                    }
                    if (inResponse && tag in CAPTURE_TAGS) {
                        captureTag = tag
                        captureText = StringBuilder()
                    }
                }

                override fun characters(ch: CharArray, start: Int, length: Int) {
                    if (captureTag != null && length > 0) captureText.append(ch, start, length)
                }

                override fun endElement(uri: String?, localName: String?, qName: String?) {
                    val tag = (localName?.takeIf { it.isNotBlank() } ?: qName.orEmpty().substringAfter(':'))
                        .lowercase(Locale.ROOT)
                    if (captureTag == tag) {
                        assign(tag, captureText.toString().trim())
                        captureTag = null
                        captureText = StringBuilder()
                    }
                    when (tag) {
                        "propstat" -> if (inPropstat) {
                            if (propstatStatus in 200..299) merge(merged, pending)
                            inPropstat = false
                        }
                        "response" -> if (inResponse) {
                            if (href.isNotBlank()) {
                                result += ParsedWebDavItem(
                                    href = href,
                                    displayName = merged.displayName,
                                    isDirectory = merged.isDirectory,
                                    size = merged.size,
                                    lastModified = merged.lastModified,
                                    contentType = merged.contentType,
                                )
                            }
                            inResponse = false
                            inPropstat = false
                        }
                    }
                }

                private fun assign(tag: String, value: String) {
                    val target = if (inPropstat) pending else merged
                    when (tag) {
                        "href" -> href = value
                        "displayname" -> target.displayName = value
                        "getcontentlength" -> target.size = value.toLongOrNull() ?: 0L
                        "getlastmodified" -> target.lastModified = value
                        "getcontenttype" -> target.contentType = value
                        "status" -> if (inPropstat) {
                            propstatStatus = Regex("\\s(\\d{3})(?:\\s|$)")
                                .find(value)?.groupValues?.get(1)?.toIntOrNull() ?: 200
                        }
                    }
                }
            },
        )
        return result
    }

    private fun merge(target: Props, source: Props) {
        if (source.displayName.isNotBlank()) target.displayName = source.displayName
        if (source.size > 0L) target.size = source.size
        if (source.lastModified.isNotBlank()) target.lastModified = source.lastModified
        if (source.contentType.isNotBlank()) target.contentType = source.contentType
        target.isDirectory = target.isDirectory || source.isDirectory
    }

    private val CAPTURE_TAGS = setOf(
        "href",
        "status",
        "displayname",
        "getcontentlength",
        "getlastmodified",
        "getcontenttype",
    )
}
