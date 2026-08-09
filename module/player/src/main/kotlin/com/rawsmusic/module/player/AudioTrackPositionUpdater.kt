package com.rawsmusic.module.player

/**
 * Chooses between AudioTimestamp hardware position and byte-advanced fallback
 * position for Android AudioTrack playback.
 */
internal class AudioTrackPositionUpdater(
    private val hardwarePositionMs: (sampleRate: Int) -> Long,
    private val useHardwareTimestamp: () -> Boolean,
    private val isFlushPending: () -> Boolean
) {
    private val fallbackAccumulator = FractionalPlaybackPositionAccumulator()

    data class Result(
        val previousPositionMs: Long,
        val positionMs: Long,
        val hardwarePositionMs: Long,
        val usedHardware: Boolean,
        val flushPending: Boolean
    )

    fun reset() {
        fallbackAccumulator.reset()
    }

    fun updateStreaming(
        currentPositionMs: Long,
        bytesAdvanced: Int,
        bytesPerMs: Double,
        sampleRate: Int,
        durationMs: Long
    ): Result {
        val previous = currentPositionMs
        val flushPending = isFlushPending()
        val useHw = useHardwareTimestamp()
        val hwPos = if (useHw && !flushPending) hardwarePositionMs(sampleRate) else -1L
        val next = when {
            hwPos >= 0L -> {
                fallbackAccumulator.reset()
                hwPos.coerceToDuration(durationMs)
            }
            !flushPending -> fallbackAccumulator.advance(
                currentPositionMs = currentPositionMs,
                bytesAdvanced = bytesAdvanced,
                bytesPerMs = bytesPerMs,
                durationMs = durationMs
            )
            else -> {
                fallbackAccumulator.reset()
                currentPositionMs.coerceToDuration(durationMs)
            }
        }
        return Result(previous, next, hwPos, useHw, flushPending)
    }

    fun updateAbsolute(
        currentPositionMs: Long,
        bytesReadTotal: Long,
        bytesPerMs: Double,
        sampleRate: Int,
        durationMs: Long
    ): Result {
        fallbackAccumulator.reset()
        val previous = currentPositionMs
        val hwPos = if (useHardwareTimestamp()) hardwarePositionMs(sampleRate) else -1L
        val next = if (hwPos >= 0L) {
            hwPos.coerceToDuration(durationMs)
        } else if (bytesPerMs > 0.0) {
            (bytesReadTotal.toDouble() / bytesPerMs).toLong().coerceToDuration(durationMs)
        } else {
            currentPositionMs.coerceToDuration(durationMs)
        }
        return Result(previous, next, hwPos, hwPos >= 0L, flushPending = false)
    }

    private fun Long.coerceToDuration(durationMs: Long): Long {
        return if (durationMs > 0L) this.coerceIn(0L, durationMs) else this.coerceAtLeast(0L)
    }
}
