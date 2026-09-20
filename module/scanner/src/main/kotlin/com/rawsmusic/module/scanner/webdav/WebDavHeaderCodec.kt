package com.rawsmusic.module.scanner.webdav

/** Parses the optional advanced HTTP headers shared by browse/backup/playback WebDAV requests. */
object WebDavHeaderCodec {
    private const val MAX_TEXT_LENGTH = 16 * 1024
    private const val MAX_HEADERS = 32
    private const val MAX_NAME_LENGTH = 128
    private const val MAX_VALUE_LENGTH = 4096

    private val headerName = Regex("^[!#\\$%&'*+.^_`|~0-9A-Za-z-]+$")
    private val forbidden = setOf(
        "host",
        "content-length",
        "connection",
        "transfer-encoding",
        "proxy-authorization",
        "proxy-authenticate",
        "te",
        "trailer",
        "upgrade",
    )

    fun parse(text: String): Map<String, String> {
        require(text.length <= MAX_TEXT_LENGTH) { "Header configuration is too large" }
        val result = linkedMapOf<String, String>()
        text.lineSequence().forEachIndexed { index, rawLine ->
            val line = rawLine.trim()
            if (line.isBlank() || line.startsWith('#')) return@forEachIndexed
            val separator = line.indexOf(':')
            require(separator > 0) { "Line ${index + 1}: expected 'Header-Name: value'" }
            val name = line.substring(0, separator).trim()
            val value = line.substring(separator + 1).trim()
            require(name.length <= MAX_NAME_LENGTH && headerName.matches(name)) {
                "Line ${index + 1}: invalid header name"
            }
            require(name.lowercase() !in forbidden) {
                "Line ${index + 1}: header '$name' is managed by the HTTP stack"
            }
            require(value.length <= MAX_VALUE_LENGTH) { "Line ${index + 1}: header value is too long" }
            require('\r' !in value && '\n' !in value) { "Line ${index + 1}: invalid header value" }
            result[name] = value
            require(result.size <= MAX_HEADERS) { "Too many custom headers (max $MAX_HEADERS)" }
        }
        return result
    }

    fun parseOrEmpty(text: String): Map<String, String> = runCatching { parse(text) }.getOrDefault(emptyMap())
}
