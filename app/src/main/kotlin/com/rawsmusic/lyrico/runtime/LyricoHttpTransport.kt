package com.rawsmusic.lyrico.runtime

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import okhttp3.ConnectionPool
import okhttp3.OkHttpClient

/** Process-owned transport; plugin VM destruction must not create another TLS/socket pool. */
internal object LyricoHttpTransport {
    const val TEXT_LIMIT_BYTES = 2 * 1024 * 1024
    const val BINARY_LIMIT_BYTES = 4 * 1024 * 1024
    private val permits = Semaphore(4, true)
    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectionPool(ConnectionPool(4, 30, TimeUnit.SECONDS))
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    fun <T> withPermit(block: () -> T): T {
        if (!permits.tryAcquire(30, TimeUnit.SECONDS)) throw IOException("Plugin HTTP capacity timeout")
        try { return block() } finally { permits.release() }
    }
}

/** Enforce decoded-body limits even for gzip/chunked replies without a Content-Length. */
internal fun readBoundedPluginResponse(input: InputStream, limit: Int, declaredLength: Long = -1): ByteArray {
    require(limit > 0)
    if (declaredLength > limit) throw IOException("Plugin HTTP response exceeds $limit bytes")
    val output = ByteArrayOutputStream(minOf(limit, 8192))
    val buffer = ByteArray(minOf(limit, 8192))
    var total = 0
    while (true) {
        val count = input.read(buffer, 0, minOf(buffer.size, limit - total + 1))
        if (count < 0) break
        if (count == 0) continue
        total += count
        if (total > limit) throw IOException("Plugin HTTP response exceeds $limit bytes")
        output.write(buffer, 0, count)
    }
    return output.toByteArray()
}
