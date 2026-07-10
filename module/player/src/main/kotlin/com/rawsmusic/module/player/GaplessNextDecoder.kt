package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Manages the pre-opened "next song" FFmpeg decoder used by gapless / crossfade transitions.
 *
 * Owns the decoder handle and the format snapshot (sample rate / channels / bits) so the
 * player loop does not scatter these across multiple volatile fields. The player still
 * decides when to open, transfer, and close; this class guarantees resource safety.
 */
internal class GaplessNextDecoder(
    private val tag: String,
    private val resolvePath: (String) -> String,
    private val isGenerationCurrent: (Int) -> Boolean
) {
    data class Prepared(
        val path: String,
        val handle: Long,
        val sampleRate: Int,
        val channels: Int,
        val bitsPerSample: Int,
        val ownerGeneration: Int
    )

    @Volatile
    private var prepared: Prepared? = null

    val handle: Long get() = prepared?.handle ?: 0L
    val sampleRate: Int get() = prepared?.sampleRate ?: 0
    val channels: Int get() = prepared?.channels ?: 0
    val bitsPerSample: Int get() = prepared?.bitsPerSample ?: 0
    val path: String? get() = prepared?.path
    val isPrepared: Boolean get() = prepared != null

    /** Returns the current prepared state without consuming it, or null if none. */
    val snapshot: Prepared? get() = prepared

    fun snapshotFor(ownerGeneration: Int): Prepared? =
        prepared?.takeIf { it.ownerGeneration == ownerGeneration }

    fun pathFor(ownerGeneration: Int): String? =
        snapshotFor(ownerGeneration)?.path

    fun isPreparedFor(ownerGeneration: Int): Boolean =
        snapshotFor(ownerGeneration) != null

    /** Atomically consumes and returns the prepared state, or null if none. */
    fun takePrepared(): Prepared? {
        val p = prepared
        prepared = null
        return p
    }

    fun prepare(
        path: String,
        wavSampleRate: Int,
        wavBitsPerSample: Int,
        wavChannels: Int,
        ownerGeneration: Int
    ): Boolean {
        if (!isGenerationCurrent(ownerGeneration)) {
            AppLogger.w(tag, "Gapless: skip prepare for stale generation path=$path gen=$ownerGeneration")
            return false
        }
        clear("prepare_next")
        AppLogger.d(tag, "Gapless: prepareNextDecoder START path=$path gen=$ownerGeneration")
        val prepStart = System.nanoTime()
        return try {
            val resolvedPath = resolvePath(path)
            val openStart = System.nanoTime()
            var handle = 0L
            try {
                handle = FFmpegBridge.openDecoder(resolvedPath, wavSampleRate, wavBitsPerSample, wavChannels)
            } catch (t: Throwable) {
                if (handle != 0L) {
                    try { FFmpegBridge.closeDecoder(handle) } catch (_: Throwable) {}
                }
                AppLogger.e(tag, "Gapless: openDecoder threw for $path", t)
                return false
            }
            val openMs = (System.nanoTime() - openStart) / 1_000_000.0
            AppLogger.d(tag, "Gapless: prepareNextDecoder FFmpegBridge.openDecoder took ${"%.1f".format(openMs)}ms (handle=$handle)")
            if (handle == 0L) {
                AppLogger.w(tag, "Gapless: failed to open next decoder for $path")
                return false
            }
            try {
                val sr = FFmpegBridge.getDecoderSampleRate(handle)
                val ch = FFmpegBridge.getDecoderChannels(handle)
                val bits = FFmpegBridge.getDecoderBitsPerSample(handle)
                if (!isGenerationCurrent(ownerGeneration)) {
                    try { FFmpegBridge.closeDecoder(handle) } catch (_: Throwable) {}
                    AppLogger.w(tag, "Gapless: prepared decoder discarded because generation is obsolete path=$path gen=$ownerGeneration")
                    return false
                }
                prepared = Prepared(path, handle, sr, ch, bits, ownerGeneration)
                AppLogger.d(tag, "Gapless: next decoder prepared: $path " +
                    "(sr=$sr, ch=$ch, bits=$bits, gen=$ownerGeneration) " +
                    "TOTAL=${"%.1f".format((System.nanoTime() - prepStart) / 1_000_000.0)}ms")
                true
            } catch (t: Throwable) {
                // Read format failed — close the just-opened handle to avoid leak.
                try { FFmpegBridge.closeDecoder(handle) } catch (_: Throwable) {}
                prepared = null
                AppLogger.e(tag, "Gapless: prepareNextDecoder format read failed for $path", t)
                false
            }
        } catch (t: Throwable) {
            AppLogger.e(tag, "Gapless: prepareNextDecoder failed", t)
            false
        }
    }

    /**
     * Returns the prepared state only when both path and playback generation match.
     * Old streaming loops can race with a new play() request; generation ownership
     * prevents an obsolete loop from consuming or closing the new session decoder.
     */
    fun consumeIfPathMatches(expectedPath: String, ownerGeneration: Int): Prepared? {
        val existing = prepared ?: return null
        if (existing.ownerGeneration != ownerGeneration) {
            if (existing.ownerGeneration < ownerGeneration) {
                clear("stale_generation_${existing.ownerGeneration}_expected_$ownerGeneration")
            } else {
                AppLogger.w(
                    tag,
                    "Gapless: refusing to consume decoder from newer generation " +
                        "preparedGen=${existing.ownerGeneration} expectedGen=$ownerGeneration path=${existing.path}"
                )
            }
            return null
        }
        if (existing.path != expectedPath) {
            clear("next_path_changed")
            return null
        }
        prepared = null
        return existing
    }

    /** Clears the prepared state, closing the decoder handle if one is held. */
    fun clear(reason: String) {
        val existing = prepared
        prepared = null
        if (existing != null && existing.handle != 0L) {
            try { FFmpegBridge.closeDecoder(existing.handle) } catch (_: Throwable) {}
            AppLogger.d(tag, "Gapless: cleared next decoder (reason=$reason, path=${existing.path})")
        }
    }
}
