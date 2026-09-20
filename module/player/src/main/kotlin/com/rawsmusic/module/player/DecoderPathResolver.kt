package com.rawsmusic.module.player

import android.content.Context
import android.os.ParcelFileDescriptor
import com.rawsmusic.core.common.net.RemoteHttpStreamRegistry
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.scanner.webdav.WebDavPlaybackCredentialResolver
import java.io.File

/**
 * Owns SAF ParcelFileDescriptors used to feed content:// URIs into FFmpeg.
 *
 * A single playback session may have more than one decoder open at the same time
 * (main decoder + gapless/crossfade next decoder). Keep every PFD alive until
 * the session is retired instead of closing the previous one when resolving the
 * next content:// URI.
 */
internal class DecoderPathResolver(
    private val context: Context,
    private val tag: String
) {
    private val activePfds = ArrayList<ParcelFileDescriptor>()
    private val flacCompatibilityCache = FlacPlaybackCompatibilityCache(
        cacheDirectory = File(context.cacheDir, "flac_playback_compat"),
        tag = tag,
    )

    @Synchronized
    fun resolve(path: String): String {
        val sanitizedPath = WebDavPlaybackCredentialResolver.sanitizeUrlIfConfigured(path) ?: path
        val registeredTransport = RemoteHttpStreamRegistry.lookup(sanitizedPath)?.resolveUrl(sanitizedPath)
        val transportPath = registeredTransport ?: sanitizedPath
        val resolvedPath = if (transportPath.startsWith("content://")) {
            resolveContentUri(transportPath)
        } else {
            transportPath
        }
        return flacCompatibilityCache.resolve(
            sourcePath = resolvedPath,
            sourceIdentity = sanitizedPath,
        )
    }

    /**
     * Slow safety fallback for WebDAV origins that FFmpeg cannot consume directly.
     * Normal WebDAV playback must stay on direct HTTP; this is called only after a real
     * decoder-open failure so large non-Range objects are not downloaded before every play.
     */
    @Synchronized
    fun resolveWebDavCacheFallback(path: String): String? {
        val sanitizedPath = WebDavPlaybackCredentialResolver.sanitizeUrlIfConfigured(path) ?: return null
        return WebDavPlaybackCredentialResolver.cachePath(context, sanitizedPath)
    }

    private fun resolveContentUri(path: String): String {
        return try {
            val uri = android.net.Uri.parse(path)
            val pfd = context.contentResolver.openFileDescriptor(uri, "r") ?: return path
            activePfds.add(pfd)
            val fdPath = "/proc/self/fd/${pfd.fd}"
            AppLogger.d(tag, "SAF: resolved content:// to fd path: $fdPath activePfds=${activePfds.size}")
            fdPath
        } catch (e: Exception) {
            AppLogger.w(tag, "SAF: failed to resolve content:// URI: $path", e)
            path
        }
    }

    @Synchronized
    fun close(reason: String) {
        if (activePfds.isEmpty()) return
        val pfds = activePfds.toList()
        activePfds.clear()
        for (pfd in pfds) {
            runCatching { pfd.close() }
                .onFailure { AppLogger.w(tag, "SAF: close PFD failed reason=$reason", it) }
        }
        AppLogger.d(tag, "SAF: closed ${pfds.size} PFD(s), reason=$reason")
    }
}
