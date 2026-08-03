package com.rawsmusic.core.common.utils

import java.net.URI

/** Shared, privacy-safe diagnostics helpers for online-source playback. */
object OnlinePlaybackDiagnostics {
    const val PREFIX = "ONLINE_PIPE"

    fun safeUrl(value: String?): String {
        val raw = value.orEmpty().trim()
        if (raw.isBlank()) return "-"
        return runCatching {
            val uri = URI(raw)
            val scheme = uri.scheme.orEmpty().lowercase().ifBlank { "?" }
            val host = uri.host.orEmpty().ifBlank { "?" }
            val port = if (uri.port >= 0) ":${uri.port}" else ""
            val path = uri.rawPath.orEmpty().ifBlank { "/" }.take(240)
            "$scheme://$host$port$path"
        }.getOrElse {
            raw.substringBefore('?').substringBefore('#').take(280)
        }
    }


    fun urlShape(value: String?): String {
        val raw = value.orEmpty().trim()
        if (raw.isBlank()) return "len=0 queryKeys=[]"
        return runCatching {
            val uri = URI(raw)
            val keys = uri.rawQuery.orEmpty()
                .split('&')
                .asSequence()
                .map { it.substringBefore('=').trim().lowercase() }
                .filter(String::isNotBlank)
                .distinct()
                .take(24)
                .toList()
            "len=${raw.length} queryKeys=${keys.joinToString(prefix = "[", postfix = "]")}"
        }.getOrElse {
            "len=${raw.length} query=${raw.contains('?')}"
        }
    }

    fun headerNames(headers: Map<String, String>): String = headers.keys
        .map(String::trim)
        .filter(String::isNotBlank)
        .map(String::lowercase)
        .distinct()
        .sorted()
        .joinToString(prefix = "[", postfix = "]", limit = 24, truncated = "…")

    fun errorSummary(error: Throwable?): String {
        if (error == null) return "-"
        val type = error::class.java.simpleName.ifBlank { "Throwable" }
        val message = error.message.orEmpty()
            .replace('\n', ' ')
            .replace('\r', ' ')
            .take(512)
            .ifBlank { "-" }
        return "$type:$message"
    }
}
