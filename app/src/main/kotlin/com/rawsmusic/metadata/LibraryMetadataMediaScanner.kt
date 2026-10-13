package com.rawsmusic.metadata

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeoutOrNull

/** One connection and one outstanding scan; completion or cancellation always disconnects it. */
internal suspend fun scanMatchedAudioFiles(context: Context, paths: Collection<String>) {
    if (paths.isEmpty()) return
    val connected = Channel<Unit>(1)
    val completed = Channel<String>(1)
    val connection = MediaScannerConnection(context.applicationContext,
        object : MediaScannerConnection.MediaScannerConnectionClient {
            override fun onMediaScannerConnected() { connected.trySend(Unit) }
            override fun onScanCompleted(path: String?, uri: Uri?) {
                if (path != null) completed.trySend(path)
            }
        })
    try {
        connection.connect()
        if (withTimeoutOrNull(10_000L) { connected.receive() } == null) {
            AppLogger.w("MetadataMatchService", "Media scanner connection timed out")
            return
        }
        for (path in paths) {
            currentCoroutineContext().ensureActive()
            if (path.startsWith("content://", true)) continue
            connection.scanFile(path, null)
            val result = withTimeoutOrNull(10_000L) {
                var received = completed.receive()
                while (received != path) received = completed.receive()
                received
            }
            if (result == null) {
                AppLogger.w("MetadataMatchService", "Media scan timed out; remaining scans skipped")
                break
            }
        }
    } finally {
        runCatching { connection.disconnect() }
        connected.close()
        completed.close()
    }
}
