package com.rawsmusic.core.common.net

import java.util.concurrent.ConcurrentHashMap

/**
 * Small process-local bridge for HTTP sources that need request headers when FFmpeg opens them.
 *
 * This is intentionally source-agnostic: WebDAV, plugin sources, and future remote libraries can
 * register a resolved URL without teaching the player about their protocol. Entries are bounded
 * and short-lived; callers should still keep the URL independently usable when possible so queue
 * persistence does not depend on this in-memory registry.
 */
object RemoteHttpStreamRegistry {
    class Entry internal constructor(
        private val staticHeaders: Map<String, String>,
        val userAgent: String? = null,
        val owner: String = "remote",
        val registeredAtMs: Long = System.currentTimeMillis(),
        private val headerProvider: ((String) -> Map<String, String>)? = null,
        private val urlProvider: ((String) -> String)? = null,
    ) {
        fun resolveHeaders(url: String): Map<String, String> {
            val dynamic = runCatching { headerProvider?.invoke(url).orEmpty() }.getOrDefault(emptyMap())
            return sanitizeHeaders(staticHeaders + dynamic)
        }

        fun resolveUrl(url: String): String =
            runCatching { urlProvider?.invoke(url).orEmpty() }
                .getOrDefault("")
                .takeIf { it.isNotBlank() }
                ?: url
    }

    private const val MAX_ENTRIES = 64
    private val entries = ConcurrentHashMap<String, Entry>()

    fun register(
        url: String,
        headers: Map<String, String>,
        userAgent: String? = null,
        owner: String = "remote",
        headerProvider: ((String) -> Map<String, String>)? = null,
        urlProvider: ((String) -> String)? = null,
    ) {
        if (url.isBlank()) return
        val sanitizedHeaders = sanitizeHeaders(headers)
        entries[url] = Entry(
            staticHeaders = sanitizedHeaders,
            userAgent = userAgent?.takeIf { it.isNotBlank() },
            owner = owner,
            headerProvider = headerProvider,
            urlProvider = urlProvider,
        )
        trim()
    }

    fun lookup(url: String): Entry? = entries[url]

    fun remove(url: String) {
        entries.remove(url)
    }

    private fun trim() {
        if (entries.size <= MAX_ENTRIES) return
        entries.entries
            .sortedBy { it.value.registeredAtMs }
            .take((entries.size - MAX_ENTRIES).coerceAtLeast(0))
            .forEach { entries.remove(it.key, it.value) }
    }

    private fun sanitizeHeaders(headers: Map<String, String>): Map<String, String> = buildMap {
        headers.forEach { (rawName, rawValue) ->
            val name = rawName.trim().replace("\r", "").replace("\n", "")
            val value = rawValue.replace("\r", "").replace("\n", "")
            if (name.isNotBlank()) put(name, value)
        }
    }
}
