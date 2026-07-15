package com.rawsmusic.module.player

import kotlin.math.floor

/**
 * Accumulates PCM progress without discarding the fractional millisecond from each write.
 * High sample-rate streams use many short writes, so truncating every chunk causes a
 * measurable clock-rate error that grows for the entire song.
 */
internal class FractionalPlaybackPositionAccumulator {
    private var remainderMs = 0.0

    fun reset() {
        remainderMs = 0.0
    }

    fun advance(
        currentPositionMs: Long,
        bytesAdvanced: Int,
        bytesPerMs: Double,
        durationMs: Long
    ): Long {
        if (bytesAdvanced <= 0 || !bytesPerMs.isFinite() || bytesPerMs <= 0.0) {
            return currentPositionMs.coerceToDuration(durationMs)
        }

        val exactAdvanceMs = bytesAdvanced.toDouble() / bytesPerMs + remainderMs
        val wholeAdvanceMs = floor(exactAdvanceMs).toLong()
        remainderMs = exactAdvanceMs - wholeAdvanceMs.toDouble()

        val next = (currentPositionMs + wholeAdvanceMs).coerceToDuration(durationMs)
        if (durationMs > 0L && next >= durationMs) reset()
        return next
    }

    private fun Long.coerceToDuration(durationMs: Long): Long {
        return if (durationMs > 0L) coerceIn(0L, durationMs) else coerceAtLeast(0L)
    }
}
