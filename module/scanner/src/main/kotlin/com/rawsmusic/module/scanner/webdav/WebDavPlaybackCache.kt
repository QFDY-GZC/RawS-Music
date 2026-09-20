package com.rawsmusic.module.scanner.webdav

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves WebDAV media to a transport that is safe for FFmpeg.
 *
 * Real byte-range servers stay on normal HTTP. Servers that ignore Range are materialized into
 * an ordinary app-cache file. Do not use an in-process ProxyFileDescriptor/FUSE fd here: native
 * pread() callers can remain in uninterruptible D state if the process is killed with outstanding
 * proxy callbacks.
 */
object WebDavPlaybackCache {
    private const val TAG = "WebDavPlaybackCache"
    private const val CACHE_DIR = "webdav_playback_v2"
    private const val MAX_CACHE_FILES = 8
    private const val COPY_BUFFER_BYTES = 256 * 1024
    private const val PROGRESS_STEP_BYTES = 16L * 1024L * 1024L

    private val keyLocks = ConcurrentHashMap<String, Any>()

    /**
     * Returns an already-published ordinary cache file without touching the network.
     * WebDAV browser artwork uses this path so scrolling never starts a full media download.
     */
    fun findCachedPath(
        context: Context,
        config: WebDavConfig,
        remoteUrl: String,
        knownSize: Long = 0L,
    ): String? = findCachedPathInDirectory(
        directory = File(context.applicationContext.cacheDir, CACHE_DIR),
        config = config,
        remoteUrl = remoteUrl,
        knownSize = knownSize,
    )

    internal fun findCachedPathInDirectory(
        directory: File,
        config: WebDavConfig,
        remoteUrl: String,
        knownSize: Long = 0L,
    ): String? {
        val target = cacheFile(directory, config, remoteUrl)
        val marker = markerFile(directory, config, remoteUrl)
        return target.absolutePath.takeIf {
            isCompleteCache(target, marker, knownSize.coerceAtLeast(0L))
        }
    }

    internal fun cacheFileForTest(directory: File, config: WebDavConfig, remoteUrl: String): File =
        cacheFile(directory, config, remoteUrl)

    internal fun markerFileForTest(directory: File, config: WebDavConfig, remoteUrl: String): File =
        markerFile(directory, config, remoteUrl)

    fun resolve(
        context: Context,
        client: WebDavClient,
        config: WebDavConfig,
        remoteUrl: String,
        knownSize: Long = 0L,
    ): String {
        val key = cacheKey(config, remoteUrl)
        val lock = keyLocks.getOrPut(key) { Any() }
        return synchronized(lock) {
            try {
                resolveLocked(context.applicationContext, client, config, remoteUrl, knownSize, key)
            } finally {
                keyLocks.remove(key, lock)
            }
        }
    }

    private fun resolveLocked(
        context: Context,
        client: WebDavClient,
        config: WebDavConfig,
        remoteUrl: String,
        knownSize: Long,
        key: String,
    ): String {
        val directory = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        val extension = safeExtension(remoteUrl)
        val target = File(directory, "$key.$extension")
        val marker = File(directory, "$key.complete")
        val expectedKnownSize = knownSize.coerceAtLeast(0L)

        if (isCompleteCache(target, marker, expectedKnownSize)) {
            target.setLastModified(System.currentTimeMillis())
            marker.setLastModified(System.currentTimeMillis())
            Log.i(TAG, "CACHE_HIT bytes=${target.length()} url=${remoteUrl.safeLogUrl()}")
            return target.absolutePath
        }

        val response = client.openStreamingResponse(
            config = config,
            remotePath = remoteUrl,
            rangeHeader = "bytes=0-0",
            headOnly = false,
        )
        response.use { current ->
            if (current.code == 206) {
                val contentRange = current.header("Content-Range").orEmpty()
                val rangeStart = contentRange
                    .substringAfter("bytes ", "")
                    .substringBefore('-')
                    .toLongOrNull()
                if (rangeStart == 0L) {
                    Log.i(TAG, "RANGE_SUPPORTED url=${remoteUrl.safeLogUrl()}")
                    return remoteUrl
                }
                throw IOException("Invalid WebDAV Content-Range: $contentRange")
            }
            if (current.code != 200) {
                throw IOException("WebDAV playback probe failed: HTTP ${current.code}")
            }

            val body = current.body ?: throw IOException("WebDAV response has no body")
            val responseSize = current.header("Content-Length")?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L
            val expectedSize = responseSize.takeIf { it > 0L } ?: expectedKnownSize
            val temp = File(directory, "$key.$extension.part")
            marker.delete()
            temp.delete()

            Log.i(
                TAG,
                "RANGE_UNSUPPORTED_CACHE_START expected=$expectedSize url=${remoteUrl.safeLogUrl()}",
            )
            var copied = 0L
            var nextProgress = PROGRESS_STEP_BYTES
            try {
                body.byteStream().use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(COPY_BUFFER_BYTES)
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            copied += read.toLong()
                            if (copied >= nextProgress) {
                                Log.d(TAG, "CACHE_PROGRESS bytes=$copied url=${remoteUrl.safeLogUrl()}")
                                nextProgress += PROGRESS_STEP_BYTES
                            }
                        }
                        output.fd.sync()
                    }
                }
                if (expectedSize > 0L && copied != expectedSize) {
                    throw IOException("Incomplete WebDAV cache: expected=$expectedSize actual=$copied")
                }
                if (copied <= 0L) throw IOException("Empty WebDAV cache")

                if (target.exists() && !target.delete()) {
                    throw IOException("Unable to replace stale WebDAV cache")
                }
                if (!temp.renameTo(target)) {
                    throw IOException("Unable to publish WebDAV cache")
                }
                marker.writeText(target.length().toString())
                target.setLastModified(System.currentTimeMillis())
                marker.setLastModified(System.currentTimeMillis())
                prune(directory, keepKey = key)
                Log.i(TAG, "CACHE_READY bytes=${target.length()} url=${remoteUrl.safeLogUrl()}")
                return target.absolutePath
            } catch (error: Throwable) {
                temp.delete()
                marker.delete()
                throw error
            }
        }
    }

    private fun isCompleteCache(target: File, marker: File, knownSize: Long): Boolean {
        if (!target.isFile || target.length() <= 0L || !marker.isFile) return false
        val markerSize = runCatching { marker.readText().trim().toLong() }.getOrDefault(-1L)
        if (markerSize != target.length()) return false
        return knownSize <= 0L || target.length() == knownSize
    }

    private fun cacheFile(
        directory: File,
        config: WebDavConfig,
        remoteUrl: String,
        key: String = cacheKey(config, remoteUrl),
    ): File = File(directory, "$key.${safeExtension(remoteUrl)}")

    private fun markerFile(
        directory: File,
        config: WebDavConfig,
        remoteUrl: String,
        key: String = cacheKey(config, remoteUrl),
    ): File = File(directory, "$key.complete")

    private fun prune(directory: File, keepKey: String) {
        val mediaFiles = directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(".complete") && !it.name.endsWith(".part") }
            .sortedByDescending { it.lastModified() }
        mediaFiles.drop(MAX_CACHE_FILES).forEach { stale ->
            if (!stale.name.startsWith(keepKey)) {
                val markerName = stale.name.substringBeforeLast('.') + ".complete"
                runCatching { stale.delete() }
                runCatching { File(directory, markerName).delete() }
            }
        }
    }

    private fun cacheKey(config: WebDavConfig, remoteUrl: String): String {
        val material = buildString {
            append(WebDavUrlTools.stripUserInfo(remoteUrl)).append('\u0000')
            append(WebDavUrlTools.stripUserInfo(config.url)).append('\u0000')
            append(config.username).append('\u0000')
            append(config.password).append('\u0000')
            append(config.authMode.name).append('\u0000')
            config.extraHeaders.toSortedMap(String.CASE_INSENSITIVE_ORDER).forEach { (name, value) ->
                append(name.lowercase()).append('=').append(value).append('\u0000')
            }
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }.take(32)
    }

    private fun safeExtension(remoteUrl: String): String {
        val raw = remoteUrl.substringBefore('?').substringAfterLast('.', "").lowercase()
        val safe = raw.filter { it.isLetterOrDigit() }.take(8)
        return safe.ifBlank { "media" }
    }

    private fun String.safeLogUrl(): String =
        substringBefore('?').replace(Regex("//[^/@]+@"), "//***@")
}
