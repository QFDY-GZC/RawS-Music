package com.rawsmusic.module.player

/**
 * Safe buffer-duration math used by playback logging and sizing.
 *
 * All computations use Long arithmetic to avoid Int overflow on large buffers
 * (e.g. 3 MB ring buffer × 1000 exceeds Int.MAX_VALUE).
 */
internal object PlaybackBufferMath {
    fun durationMsForBytes(bytes: Int, bytesPerSecond: Long): Long {
        if (bytes <= 0 || bytesPerSecond <= 0) return 0L
        return bytes.toLong() * 1000L / bytesPerSecond
    }
}
