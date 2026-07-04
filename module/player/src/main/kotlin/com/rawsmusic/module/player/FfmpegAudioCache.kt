package com.rawsmusic.module.player

import android.content.Context
import com.rawsmusic.core.common.utils.AppLogger
import java.io.File

/**
 * Owns FFmpeg intermediate cache file naming and bounded cache trimming.
 *
 * The player decides which format is needed; this class only maps that decision to a
 * stable cache file and keeps the cache directory from growing indefinitely.
 */
class FfmpegAudioCache(private val context: Context) {
    private var lastTrimMs = 0L

    fun getCacheFile(
        path: String,
        usbExclusiveMode: Boolean,
        usbBitPerfectMode: Boolean,
        atTargetRate: Int = 0,
        atTargetBits: Int = 0,
        usbTargetSr: Int = 0,
        usbTargetBits: Int = 0,
        usbTargetCh: Int = 0,
    ): File {
        val cacheDir = File(context.cacheDir, "ffmpeg_audio")
        cacheDir.mkdirs()
        trimThrottled(cacheDir, 500L * 1024L * 1024L)

        return if (usbExclusiveMode) {
            val bpTag = if (usbBitPerfectMode) "_bp" else ""
            val hash = (path + "_usb_raw_pcm" + "_r$usbTargetSr" + "_b$usbTargetBits" + "_c$usbTargetCh" + bpTag)
                .hashCode()
                .toString(16)
            File(cacheDir, "$hash.pcm")
        } else {
            val rateTag = if (atTargetRate > 0) "_r$atTargetRate" else "_orig"
            val bitsTag = "_b$atTargetBits"
            val hash = (path + rateTag + bitsTag).hashCode().toString(16)
            File(cacheDir, "$hash.wav")
        }
    }

    private fun trimThrottled(dir: File, maxSize: Long) {
        val now = System.currentTimeMillis()
        if (now - lastTrimMs < 60_000L) return
        lastTrimMs = now
        trim(dir, maxSize)
    }

    private fun trim(dir: File, maxSize: Long) {
        try {
            val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
            var totalSize = files.sumOf { it.length() }
            for (file in files) {
                if (totalSize <= maxSize) break
                AppLogger.i(TAG, "Trimming cache: deleting ${file.name} (${file.length()} bytes)")
                totalSize -= file.length()
                file.delete()
            }
        } catch (_: Exception) {
            // Cache trimming must never interrupt playback preparation.
        }
    }

    private companion object {
        private const val TAG = "FfmpegAudioCache"
    }
}
