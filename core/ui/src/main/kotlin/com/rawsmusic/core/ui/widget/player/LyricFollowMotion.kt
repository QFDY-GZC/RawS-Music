package com.rawsmusic.core.ui.widget.player

import kotlin.math.pow

internal data class LyricFollowMotion(
    val dampingRatio: Float,
    val stiffness: Float,
)

/**
 * Chooses a fast critically-damped family for the Android stack. The raw AMLL 170..220 values
 * feel too slow after several line distances are retargeted into one continuous stack motion.
 * Keeping damping at (or just below) 1 avoids visible bounce while preserving velocity.
 */
internal fun lyricFollowMotion(
    intervalMs: Long?,
    isInterlude: Boolean = false,
): LyricFollowMotion {
    if (isInterlude) {
        return LyricFollowMotion(dampingRatio = 1.03f, stiffness = 240f)
    }
    val interval = intervalMs?.coerceIn(100L, 800L)
    if (interval == null) {
        return LyricFollowMotion(dampingRatio = 1f, stiffness = 240f)
    }

    val linearRatio = 1f - (interval - 100L) / 700f
    val ratio = linearRatio.coerceIn(0f, 1f).pow(0.2f)
    val stiffness = 360f + ratio * 160f
    val damping = 0.98f + (1f - ratio) * 0.02f
    return LyricFollowMotion(dampingRatio = damping, stiffness = stiffness)
}
