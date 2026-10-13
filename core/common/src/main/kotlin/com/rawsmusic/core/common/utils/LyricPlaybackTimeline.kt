package com.rawsmusic.core.common.utils

import kotlin.math.roundToLong

/** The committed decoder rate, not the preference for a pending decoder replacement. */
object LyricPlaybackTimeline {
    @Volatile var speed: Float = 1f
        set(value) { field = value.takeIf { it.isFinite() && it > 0f } ?: 1f }

    fun toLyricTime(playbackMs: Long): Long =
        (playbackMs.coerceAtLeast(0L).toDouble() * speed).roundToLong()

    fun toPlaybackTime(lyricMs: Long): Long =
        (lyricMs.coerceAtLeast(0L).toDouble() / speed).roundToLong()
}
