package com.rawsmusic.module.data.source.playback

import android.content.Context
import android.graphics.BitmapFactory
import com.rawsmusic.core.common.source.RawSourceMediaItem
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Downloads remote artwork once and exposes a local path to the existing RawSMusic artwork stack. */
object MusicSourceArtworkRepository {
    private const val ARTWORK_DIR = "music_source_artwork_v1"
    private const val MAX_IMAGE_BYTES = 12 * 1024 * 1024
    private const val MAX_REDIRECTS = 4
    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 15_000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val mutablePaths = MutableStateFlow<Map<String, String>>(emptyMap())
    val paths = mutablePaths.asStateFlow()

    fun localPath(item: RawSourceMediaItem?): String? = item?.stableIdentity?.let(mutablePaths.value::get)

    /** Returns a validated local cover file, downloading it when necessary. */
    suspend fun ensureLocalFile(context: Context, item: RawSourceMediaItem?): File? {
        if (item == null || item.artworkUrl.isBlank()) return null
        val identity = item.stableIdentity
        mutablePaths.value[identity]
            ?.let(::File)
            ?.takeIf { it.isFile && isDecodable(it) }
            ?.let { return it }
        return withContext(Dispatchers.IO) {
            val file = resolve(context.applicationContext, item.artworkUrl)
            if (file != null) {
                mutablePaths.value = mutablePaths.value + (identity to file.absolutePath)
            }
            file
        }
    }

    fun prefetch(context: Context, item: RawSourceMediaItem?) {
        if (item == null || item.artworkUrl.isBlank()) return
        val identity = item.stableIdentity
        val existing = mutablePaths.value[identity]
        if (!existing.isNullOrBlank() && File(existing).isFile) return
        if (jobs.containsKey(identity)) return
        jobs[identity] = scope.launch {
            try {
                val file = resolve(context.applicationContext, item.artworkUrl)
                if (file != null) {
                    mutablePaths.value = mutablePaths.value + (identity to file.absolutePath)
                }
            } finally {
                jobs.remove(identity)
            }
        }
    }

    private fun resolve(context: Context, rawUrl: String): File? {
        val normalized = normalizeRemoteUrl(rawUrl)
        val directory = File(context.cacheDir, ARTWORK_DIR).apply { mkdirs() }
        if (!directory.isDirectory) return null
        val cacheStem = sha256(normalized)
        // BitmapProvider recognizes image files by extension before decoding. Step64 used `.img`, so
        // the UI displayed Coil's remote image first and then switched to a local path that the
        // existing artwork stack rejected. Use a recognized extension; BitmapFactory still validates
        // the actual bytes independently of their container format.
        val target = File(directory, "$cacheStem.jpg")
        if (target.isFile && target.length() in 1..MAX_IMAGE_BYTES.toLong() && isDecodable(target)) {
            return target
        }

        // Migrate already downloaded Step64-Step66c cache entries without another network request.
        val legacyTarget = File(directory, "$cacheStem.img")
        if (legacyTarget.isFile && legacyTarget.length() in 1..MAX_IMAGE_BYTES.toLong() && isDecodable(legacyTarget)) {
            if (target.exists()) target.delete()
            if (!legacyTarget.renameTo(target)) {
                legacyTarget.copyTo(target, overwrite = true)
                legacyTarget.delete()
            }
            if (target.isFile) return target
        }

        val temporary = File(directory, target.name + ".tmp")
        runCatching { download(normalized, temporary) }.getOrElse {
            temporary.delete()
            return null
        }
        if (!isDecodable(temporary)) {
            temporary.delete()
            return null
        }
        if (target.exists()) target.delete()
        if (!temporary.renameTo(target)) {
            temporary.copyTo(target, overwrite = true)
            temporary.delete()
        }
        return target.takeIf(File::isFile)
    }

    private fun download(initialUrl: String, output: File) {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectCount ->
            val uri = URI(currentUrl)
            validatePublicHost(uri)
            val connection = uri.toURL().openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "image/avif,image/webp,image/apng,image/*,*/*;q=0.8")
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) RawSMusic/0.9.61 beta")
            try {
                val status = connection.responseCode
                if (status in 300..399) {
                    require(redirectCount < MAX_REDIRECTS) { "专辑图重定向过多" }
                    val location = connection.getHeaderField("Location") ?: error("专辑图重定向无效")
                    currentUrl = uri.resolve(location).toASCIIString()
                    normalizeRemoteUrl(currentUrl)
                    return@repeat
                }
                require(status in 200..299) { "专辑图下载失败：HTTP $status" }
                val length = connection.contentLengthLong
                require(length <= 0 || length <= MAX_IMAGE_BYTES) { "专辑图文件过大" }
                connection.inputStream.use { input ->
                    output.outputStream().use { sink ->
                        val buffer = ByteArray(16 * 1024)
                        var total = 0
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            total += count
                            require(total <= MAX_IMAGE_BYTES) { "专辑图文件过大" }
                            sink.write(buffer, 0, count)
                        }
                    }
                }
                return
            } finally {
                connection.disconnect()
            }
        }
        error("专辑图下载失败")
    }

    private fun isDecodable(file: File): Boolean {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0 && options.outWidth <= 12_000 && options.outHeight <= 12_000
    }

    private fun normalizeRemoteUrl(rawUrl: String): String {
        val normalized = rawUrl.trim().let { value -> if (value.startsWith("//")) "https:$value" else value }
        val uri = URI(normalized)
        require(uri.scheme.equals("https", true) || uri.scheme.equals("http", true)) { "仅支持 HTTP/HTTPS 专辑图" }
        require(!uri.host.isNullOrBlank()) { "专辑图地址缺少主机" }
        return uri.toASCIIString()
    }

    private fun validatePublicHost(uri: URI) {
        val host = uri.host ?: error("专辑图地址缺少主机")
        require(!host.equals("localhost", true)) { "拒绝本机专辑图地址" }
        val addresses = InetAddress.getAllByName(host)
        require(addresses.isNotEmpty()) { "无法解析专辑图主机" }
        require(addresses.none { address ->
            address.isAnyLocalAddress || address.isLoopbackAddress || address.isLinkLocalAddress ||
                address.isSiteLocalAddress || address.isMulticastAddress
        }) { "拒绝本地网络专辑图地址" }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
