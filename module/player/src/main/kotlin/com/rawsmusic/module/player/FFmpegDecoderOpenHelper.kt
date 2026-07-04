package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Centralizes FFmpeg decoder open/probe policy.
 *
 * This keeps fallback ordering, safe-mode clamping, strict USB bit-perfect rules,
 * and SAF path resolution out of the main playback class.
 */
internal class FFmpegDecoderOpenHelper(
    private val tag: String,
    private val pathResolver: DecoderPathResolver,
    private val isSafeMode: () -> Boolean,
    private val isStrictUsbBitPerfectPath: () -> Boolean
) {
    fun probeDuration(path: String): Long {
        return try {
            val dur = FFmpegBridge.probeDuration(path)
            if (dur <= 0) {
                AppLogger.w(tag, "probeDuration returned non-positive: ${dur}ms for $path, treating as unknown")
                0L
            } else {
                dur
            }
        } catch (e: Exception) {
            AppLogger.e(tag, "FFprobe failed", e)
            0L
        }
    }

    fun openWithFallback(path: String, targetSr: Int, targetBits: Int, targetCh: Int): Long {
        val safeTargetBits = if (isSafeMode()) 16 else targetBits.coerceAtMost(32)
        val safeTargetSr = if (isSafeMode()) 44100 else targetSr
        val resolvedPath = pathResolver.resolve(path)

        if (isStrictUsbBitPerfectPath()) {
            // Bit-perfect boundary: do not silently resample, down-convert,
            // or change channel count when the exact decoder format cannot be opened.
            val handle = FFmpegBridge.openDecoder(resolvedPath, targetSr, targetBits, targetCh)
            if (handle == 0L) {
                AppLogger.e(tag, "USB bit-perfect decoder open failed without fallback: ${targetSr}Hz/${targetBits}bit/${targetCh}ch")
            }
            return handle
        }

        val fallbackBits = listOf(safeTargetBits, 32, 24, 16)
            .filter { it in 16..32 }
            .distinct()
        val fallbackRates = listOf(safeTargetSr, 48000, 44100).distinct()

        for (bits in fallbackBits) {
            for (rate in fallbackRates) {
                val handle = FFmpegBridge.openDecoder(resolvedPath, rate, bits, targetCh)
                if (handle != 0L) {
                    if (rate != targetSr || bits != targetBits) {
                        AppLogger.w(tag, "Decoder fallback opened: requested=${targetSr}Hz/${targetBits}bit/${targetCh}ch actual=${rate}Hz/${bits}bit/${targetCh}ch")
                    }
                    return handle
                }
            }
        }
        return 0L
    }
}
