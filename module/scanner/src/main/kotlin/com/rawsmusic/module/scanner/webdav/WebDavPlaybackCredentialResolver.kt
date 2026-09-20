package com.rawsmusic.module.scanner.webdav

import android.content.Context
import android.util.Log
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * Rehydrates WebDAV HTTP options for persisted queue items after the process-local stream registry
 * has been lost. It intentionally matches only URLs inside the configured WebDAV collection root,
 * so unrelated HTTP media never inherit WebDAV credentials.
 */
object WebDavPlaybackCredentialResolver {
    private const val TAG = "WebDavCredentialResolver"
    private val client by lazy { WebDavClient() }

    private fun currentConfig(): WebDavConfig? {
        val configuredUrl = AppPreferences.WebDav.url.trim()
        if (configuredUrl.isBlank()) return null
        return WebDavConfig(
            url = configuredUrl,
            username = AppPreferences.WebDav.username,
            password = AppPreferences.WebDav.password,
            authMode = when (AppPreferences.WebDav.authMode) {
                1 -> AuthMode.BASIC
                2 -> AuthMode.DIGEST
                else -> AuthMode.AUTO
            },
            extraHeaders = WebDavHeaderCodec.parseOrEmpty(AppPreferences.WebDav.extraHeadersText),
        )
    }

    fun sanitizeUrlIfConfigured(url: String): String? {
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return null
        val configuredUrl = AppPreferences.WebDav.url.trim()
        if (configuredUrl.isBlank()) return null
        val sanitizedUrl = WebDavUrlTools.stripUserInfo(url)
        return sanitizedUrl.takeIf {
            runCatching { WebDavUrlTools.isWithinConfiguredRoot(configuredUrl, it) }.getOrDefault(false)
        }
    }

    fun resolve(url: String): WebDavPlaybackRequest? {
        val sanitizedUrl = sanitizeUrlIfConfigured(url) ?: return null
        val config = currentConfig() ?: return null
        return runCatching {
            client.buildPlaybackRequestForUrl(
                config = config,
                targetUrl = sanitizedUrl,
                refreshAuthentication = true,
            )
        }.onFailure { error ->
            Log.w(TAG, "RESOLVE_FAILED url=${sanitizedUrl.safeLogUrl()}: ${error.message}", error)
        }.getOrNull()
    }

    fun cachePath(context: Context, url: String, knownSize: Long = 0L): String? {
        val sanitizedUrl = sanitizeUrlIfConfigured(url) ?: return null
        val config = currentConfig() ?: return null
        return runCatching {
            val request = client.buildPlaybackRequestForUrl(
                config = config,
                targetUrl = sanitizedUrl,
                refreshAuthentication = true,
            )
            val path = WebDavPlaybackCache.resolve(
                context = context,
                client = client,
                config = config,
                remoteUrl = sanitizedUrl,
                knownSize = knownSize,
            )
            val headerProvider: (String) -> Map<String, String> = { target ->
                client.buildPlaybackHeaders(
                    config = config,
                    targetUrl = target,
                    refreshAuthentication = true,
                )
            }
            RemoteHttpStreamRegistry.register(
                url = sanitizedUrl,
                headers = request.headers,
                userAgent = request.userAgent,
                owner = "webdav-persisted",
                headerProvider = headerProvider,
                urlProvider = { path },
            )
            if (request.url != sanitizedUrl) {
                RemoteHttpStreamRegistry.register(
                    url = request.url,
                    headers = request.headers,
                    userAgent = request.userAgent,
                    owner = "webdav-persisted",
                    headerProvider = headerProvider,
                    urlProvider = { path },
                )
            }
            path
        }.onFailure { error ->
            Log.w(TAG, "CACHE_FALLBACK_FAILED url=${sanitizedUrl.safeLogUrl()}: ${error.message}", error)
        }.getOrNull()
    }

    private fun String.safeLogUrl(): String =
        substringBefore('?').replace(Regex("//[^/@]+@"), "//***@")
}
