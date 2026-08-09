package com.rawsmusic.module.player.usb

import android.content.Context
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import java.io.File
import kotlin.math.abs

/** Owns PCM fallback conversion and its bounded cache. */
internal class UsbPcmFallbackProcessor(private val context: Context) {
    companion object {
        private const val TAG = "UsbPcmFallbackProcessor"
        private const val CACHE_LIMIT_BYTES = 500L * 1024L * 1024L
    }

    private var lastCacheTrimMs = 0L

    fun process(
        srcPath: String,
        srcRate: Int,
        srcBits: Int,
        srcChannels: Int,
        forceFallback: Boolean = false,
        supportsNative: (sampleRate: Int, bits: Int, subslot: Int, channels: Int) -> Boolean
    ): Pair<String, UsbAudioFormat> {
        if (!forceFallback) {
            val sourceSubslot = if (srcBits > 16) 4 else 2
            if (supportsNative(srcRate, srcBits, sourceSubslot, srcChannels)) {
                AppLogger.i(TAG, "Soft-resample bypass: device supports native $srcRate/${srcBits}b")
                return srcPath to UsbAudioFormat(srcRate, srcChannels, srcBits)
            }
        }

        if (srcBits > 16) {
            AppLogger.i(TAG, "Soft-resample: try downgrade bits $srcRate/${srcBits}b -> $srcRate/16b")
            val output = cachedPcmFile(srcPath, "r${srcRate}_b16_c${srcChannels}")
            ensureRawPcm(output, srcPath, srcRate, 16, srcChannels)
            return output.absolutePath to UsbAudioFormat(srcRate, srcChannels, 16)
        }

        val targetRate = nearestStandardRate(srcRate)
        val targetBits = if (srcBits <= 16) 16 else 24
        AppLogger.i(TAG, "Soft-resampling $srcPath ${srcRate}Hz/${srcBits}b -> $targetRate/$targetBits")
        val output = cachedPcmFile(srcPath, "r${targetRate}_b${targetBits}_c${srcChannels}")
        ensureRawPcm(output, srcPath, targetRate, targetBits, srcChannels)
        return output.absolutePath to UsbAudioFormat(targetRate, srcChannels, targetBits)
    }

    private fun nearestStandardRate(sourceRate: Int): Int = when {
        sourceRate <= 48_000 -> if (abs(sourceRate - 44_100) <= abs(sourceRate - 48_000)) 44_100 else 48_000
        sourceRate <= 96_000 -> if (abs(sourceRate - 88_200) <= abs(sourceRate - 96_000)) 88_200 else 96_000
        else -> if (abs(sourceRate - 176_400) <= abs(sourceRate - 192_000)) 176_400 else 192_000
    }

    private fun cachedPcmFile(sourcePath: String, suffix: String): File {
        val cacheDir = File(context.cacheDir, "resampled_pcm").apply { mkdirs() }
        trimCacheThrottled(cacheDir)
        val hash = (sourcePath + "_" + suffix).hashCode().toString(16)
        return File(cacheDir, "$hash.pcm")
    }

    private fun ensureRawPcm(
        output: File,
        sourcePath: String,
        targetRate: Int,
        targetBits: Int,
        channels: Int
    ) {
        if (output.exists() && output.length() > 0L) return
        FFmpegBridge.convertToRawPcm(
            inputPath = sourcePath,
            outputPath = output.absolutePath,
            targetSampleRate = targetRate,
            bitsPerSample = targetBits,
            channels = channels
        )
    }

    private fun trimCacheThrottled(dir: File) {
        val now = System.currentTimeMillis()
        if (now - lastCacheTrimMs < 60_000L) return
        lastCacheTrimMs = now
        val files = dir.listFiles()?.filter { it.isFile }?.sortedBy { it.lastModified() } ?: return
        var totalSize = files.sumOf { it.length() }
        for (file in files) {
            if (totalSize <= CACHE_LIMIT_BYTES) break
            AppLogger.i(TAG, "Trimming cache: deleting ${file.name} (${file.length()} bytes)")
            totalSize -= file.length()
            runCatching { file.delete() }
        }
    }
}
