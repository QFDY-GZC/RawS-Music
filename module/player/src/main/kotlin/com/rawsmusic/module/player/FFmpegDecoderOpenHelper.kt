package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import android.os.SystemClock
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry

/**
 * Centralizes FFmpeg decoder open/probe policy.
 *
 * This keeps fallback ordering, safe-mode clamping, strict USB bit-perfect rules,
 * SAF path resolution and online HTTP options out of the main playback class.
 */
internal class FFmpegDecoderOpenHelper(
    private val tag: String,
    private val pathResolver: DecoderPathResolver,
    private val isSafeMode: () -> Boolean,
    private val isStrictUsbBitPerfectPath: () -> Boolean
) {
    fun probeDuration(path: String): Long {
        MusicSourceResolvedStreamRegistry.lookup(path)?.let { entry ->
            val duration = entry.item.durationMs.coerceAtLeast(0L)
            AppLogger.i(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} PROBE_BYPASS kind=duration " +
                    "generation=${entry.generation} result=$duration source=item_metadata"
            )
            return duration
        }
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

    fun openExact(path: String, targetSr: Int, targetBits: Int, targetCh: Int): Long {
        val resolvedPath = pathResolver.resolve(path)
        return openResolved(path, resolvedPath, targetSr, targetBits, targetCh)
    }

    fun openWithFallback(path: String, targetSr: Int, targetBits: Int, targetCh: Int): Long {
        val safeTargetBits = if (isSafeMode()) 16 else targetBits.coerceAtMost(32)
        val safeTargetSr = if (isSafeMode()) 44100 else targetSr
        val resolvedPath = pathResolver.resolve(path)

        if (isStrictUsbBitPerfectPath()) {
            // Bit-perfect boundary: do not silently resample, down-convert,
            // or change channel count when the exact decoder format cannot be opened.
            val handle = openResolved(path, resolvedPath, targetSr, targetBits, targetCh)
            if (handle == 0L) {
                AppLogger.e(tag, "USB bit-perfect decoder open failed without fallback: ${targetSr}Hz/${targetBits}bit/${targetCh}ch")
            }
            return handle
        }

        val onlineEntry = MusicSourceResolvedStreamRegistry.lookup(path)
            ?: MusicSourceResolvedStreamRegistry.lookup(resolvedPath)
        if (onlineEntry != null) {
            // A remote URL should not be reopened across the full local-file fallback matrix.
            // Try the selected Android output target once, then one conservative PCM fallback.
            val primary = openResolved(path, resolvedPath, safeTargetSr, safeTargetBits, targetCh)
            if (primary != 0L) return primary
            val fallbackRate = 44_100
            val fallbackBits = 16
            if (safeTargetSr == fallbackRate && safeTargetBits == fallbackBits) return 0L
            AppLogger.w(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} DECODER_SAFE_FALLBACK generation=${onlineEntry.generation} " +
                    "target=${fallbackRate}Hz/${fallbackBits}bit/${targetCh}ch"
            )
            return openResolved(path, resolvedPath, fallbackRate, fallbackBits, targetCh)
        }

        val fallbackBits = listOf(safeTargetBits, 32, 24, 16)
            .filter { it in 16..32 }
            .distinct()
        val fallbackRates = listOf(safeTargetSr, 48000, 44100).distinct()

        for (bits in fallbackBits) {
            for (rate in fallbackRates) {
                val handle = openResolved(path, resolvedPath, rate, bits, targetCh)
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

    private fun openResolved(
        originalPath: String,
        resolvedPath: String,
        targetSr: Int,
        targetBits: Int,
        targetCh: Int,
    ): Long {
        val entry = MusicSourceResolvedStreamRegistry.lookup(originalPath)
            ?: MusicSourceResolvedStreamRegistry.lookup(resolvedPath)
        val startedAt = SystemClock.elapsedRealtime()
        if (entry != null) {
            AppLogger.i(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} DECODER_OPEN_START generation=${entry.generation} " +
                    "target=${targetSr}Hz/${targetBits}bit/${targetCh}ch " +
                    "headers=${OnlinePlaybackDiagnostics.headerNames(entry.source.headers)} " +
                    "ua=${!entry.source.userAgent.isNullOrBlank()} " +
                    "url=${OnlinePlaybackDiagnostics.safeUrl(originalPath)}"
            )
        } else if (originalPath.startsWith("http://", true) || originalPath.startsWith("https://", true)) {
            AppLogger.e(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} DECODER_REGISTRY_MISS " +
                    "target=${targetSr}Hz/${targetBits}bit/${targetCh}ch " +
                    "url=${OnlinePlaybackDiagnostics.safeUrl(originalPath)}"
            )
        }
        val handle = if (entry != null) {
            FFmpegBridge.openDecoder(
                path = resolvedPath,
                targetSampleRate = targetSr,
                bitsPerSample = targetBits,
                channels = targetCh,
                headers = entry.source.headers,
                userAgent = entry.source.userAgent,
            )
        } else {
            FFmpegBridge.openDecoder(resolvedPath, targetSr, targetBits, targetCh)
        }
        if (entry != null) {
            AppLogger.i(
                tag,
                "${OnlinePlaybackDiagnostics.PREFIX} DECODER_OPEN_END generation=${entry.generation} " +
                    "handle=0x${handle.toString(16)} success=${handle != 0L} " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}"
            )
        }
        return handle
    }
}
