package com.rawsmusic.module.data.source.playback

import android.content.Context
import com.rawsmusic.core.common.source.RawResolvedAudioSource
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Materializes an online audio response as a seekable local file before handing it to FFmpeg.
 *
 * The bundled FFmpeg decoder in the current app can decode local files but cannot open HTTPS
 * sources. A complete local file also preserves the existing decoder/DSP/AudioTrack path and
 * makes normal seek semantics reliable, unlike a non-seekable pipe.
 */
internal object OnlineAudioFileCache {
    data class Result(
        val file: File,
        val fromCache: Boolean,
        val bytes: Long,
        val contentType: String,
    )

    private const val TAG = "OnlineAudioCache"
    private const val CACHE_DIR = "online_audio_v1"
    private const val CACHE_TTL_MS = 24L * 60L * 60L * 1_000L
    private const val MAX_CACHE_BYTES = 1_024L * 1_024L * 1_024L
    private const val MAX_SINGLE_FILE_BYTES = 768L * 1_024L * 1_024L
    private const val RESERVED_FREE_BYTES = 96L * 1_024L * 1_024L
    private const val MIN_VALID_BYTES = 4L * 1_024L
    private const val CONNECT_TIMEOUT_MS = 12_000
    private const val READ_TIMEOUT_MS = 30_000
    private const val MAX_REDIRECTS = 6

    private val downloadMutexes = ConcurrentHashMap<String, Mutex>()

    suspend fun materialize(
        context: Context,
        item: RawSourceMediaItem,
        source: RawResolvedAudioSource,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): Result = withContext(Dispatchers.IO) {
        val cacheKey = sha256("${item.stableIdentity}|${source.quality.name}")
        val downloadMutex = downloadMutexes.computeIfAbsent(cacheKey) { Mutex() }
        downloadMutex.withLock {
            val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
            require(cacheDir.isDirectory) { "无法创建在线播放缓存目录" }

            val finalFile = File(cacheDir, "$cacheKey.media")
            val now = System.currentTimeMillis()
            if (finalFile.isFile &&
                finalFile.length() >= MIN_VALID_BYTES &&
                now - finalFile.lastModified() in 0..CACHE_TTL_MS
            ) {
                finalFile.setLastModified(now)
                onProgress(100, finalFile.length(), finalFile.length())
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_HIT bytes=${finalFile.length()} " +
                        "file=${finalFile.name} url=${OnlinePlaybackDiagnostics.safeUrl(source.url)}"
                )
                return@withLock Result(
                    file = finalFile,
                    fromCache = true,
                    bytes = finalFile.length(),
                    contentType = "",
                )
            }

            val partFile = File(cacheDir, "$cacheKey.${System.nanoTime()}.part")
            partFile.delete()
            try {
                val result = downloadToFile(
                    source = source,
                    destination = partFile,
                    onProgress = onProgress,
                )
                require(partFile.length() >= MIN_VALID_BYTES) {
                    "在线播放响应过小：${partFile.length()} bytes"
                }
                if (finalFile.exists() && !finalFile.delete()) {
                    throw IllegalStateException("无法替换旧的在线播放缓存")
                }
                if (!partFile.renameTo(finalFile)) {
                    partFile.copyTo(finalFile, overwrite = true)
                    if (!partFile.delete()) partFile.deleteOnExit()
                }
                finalFile.setLastModified(System.currentTimeMillis())
                trimCache(cacheDir, keep = finalFile)
                Result(
                    file = finalFile,
                    fromCache = false,
                    bytes = result.bytes,
                    contentType = result.contentType,
                )
            } catch (error: Throwable) {
                partFile.delete()
                throw error
            }
        }
    }

    private data class DownloadResult(
        val bytes: Long,
        val contentType: String,
    )

    private suspend fun downloadToFile(
        source: RawResolvedAudioSource,
        destination: File,
        onProgress: (percent: Int, downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): DownloadResult {
        var currentUrl = source.url
        var redirectCount = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val connection = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                useCaches = false
                doInput = true
                setRequestProperty("Accept-Encoding", "identity")
                source.headers.forEach { (name, value) ->
                    if (!name.equals("Range", ignoreCase = true) &&
                        !name.equals("Host", ignoreCase = true) &&
                        !name.equals("Connection", ignoreCase = true)
                    ) {
                        runCatching { setRequestProperty(name, value) }
                    }
                }
                if (source.headers.keys.none { it.equals("User-Agent", ignoreCase = true) }) {
                    source.userAgent?.takeIf { it.isNotBlank() }?.let {
                        setRequestProperty("User-Agent", it)
                    }
                }
            }

            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location")
                        ?.takeIf { it.isNotBlank() }
                        ?: throw IllegalStateException("在线播放重定向缺少 Location：HTTP $status")
                    redirectCount += 1
                    require(redirectCount <= MAX_REDIRECTS) { "在线播放重定向次数过多" }
                    val nextUrl = URL(URL(currentUrl), location).toString()
                    AppLogger.i(
                        TAG,
                        "${OnlinePlaybackDiagnostics.PREFIX} CACHE_REDIRECT status=$status count=$redirectCount " +
                            "from=${OnlinePlaybackDiagnostics.safeUrl(currentUrl)} " +
                            "to=${OnlinePlaybackDiagnostics.safeUrl(nextUrl)}"
                    )
                    currentUrl = nextUrl
                    continue
                }
                require(status == HttpURLConnection.HTTP_OK || status == HttpURLConnection.HTTP_PARTIAL) {
                    "在线播放下载失败：HTTP $status"
                }

                val totalBytes = connection.contentLengthLong.coerceAtLeast(-1L)
                require(totalBytes <= 0L || totalBytes <= MAX_SINGLE_FILE_BYTES) {
                    "在线播放文件过大：$totalBytes bytes"
                }
                val usable = destination.parentFile?.usableSpace ?: Long.MAX_VALUE
                if (totalBytes > 0L) {
                    require(usable >= totalBytes + RESERVED_FREE_BYTES) { "存储空间不足，无法缓存在线播放音频" }
                }

                val contentType = connection.contentType.orEmpty()
                    .substringBefore(';')
                    .trim()
                    .lowercase(Locale.US)
                require(!contentType.startsWith("text/") &&
                    contentType != "application/json" &&
                    contentType != "application/xml"
                ) {
                    "在线播放地址返回了非音频内容：${contentType.ifBlank { "unknown" }}"
                }

                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_DOWNLOAD_START status=$status totalBytes=$totalBytes " +
                        "contentType=${contentType.ifBlank { "unknown" }} " +
                        "headers=${OnlinePlaybackDiagnostics.headerNames(source.headers)} " +
                        "url=${OnlinePlaybackDiagnostics.safeUrl(currentUrl)}"
                )

                var downloaded = 0L
                var lastPercent = -1
                var lastLoggedBytes = 0L
                BufferedInputStream(connection.inputStream, 64 * 1_024).use { input ->
                    BufferedOutputStream(FileOutputStream(destination), 64 * 1_024).use { output ->
                        val buffer = ByteArray(64 * 1_024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            if (count == 0) continue
                            output.write(buffer, 0, count)
                            downloaded += count
                            require(downloaded <= MAX_SINGLE_FILE_BYTES) {
                                "在线播放文件超过缓存上限"
                            }
                            val percent = if (totalBytes > 0L) {
                                ((downloaded * 100L) / totalBytes).toInt().coerceIn(0, 99)
                            } else {
                                0
                            }
                            if (percent != lastPercent || downloaded - lastLoggedBytes >= 4L * 1_024L * 1_024L) {
                                lastPercent = percent
                                lastLoggedBytes = downloaded
                                onProgress(percent, downloaded, totalBytes)
                            }
                        }
                        output.flush()
                    }
                }
                onProgress(100, downloaded, if (totalBytes > 0L) totalBytes else downloaded)
                AppLogger.i(
                    TAG,
                    "${OnlinePlaybackDiagnostics.PREFIX} CACHE_DOWNLOAD_END bytes=$downloaded " +
                        "contentType=${contentType.ifBlank { "unknown" }} redirects=$redirectCount " +
                        "url=${OnlinePlaybackDiagnostics.safeUrl(currentUrl)}"
                )
                return DownloadResult(downloaded, contentType)
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun trimCache(cacheDir: File, keep: File) {
        val files = cacheDir.listFiles()
            ?.filter { it.isFile && it.extension == "media" }
            ?.sortedByDescending { it.lastModified() }
            .orEmpty()
        var total = files.sumOf { it.length() }
        for (file in files.asReversed()) {
            if (total <= MAX_CACHE_BYTES) break
            if (file == keep) continue
            val bytes = file.length()
            if (file.delete()) total -= bytes
        }
        cacheDir.listFiles()
            ?.filter { it.isFile && it.extension == "part" }
            ?.forEach { stale ->
                if (System.currentTimeMillis() - stale.lastModified() > 60L * 60L * 1_000L) stale.delete()
            }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
