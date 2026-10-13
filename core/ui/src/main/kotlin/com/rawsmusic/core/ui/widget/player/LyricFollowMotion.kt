package com.rawsmusic.core.ui.widget.player


internal data class LyricFollowMotion(
    val dampingRatio: Float,
    val stiffness: Float,
)

/** The archive's default position spring. Quick lines retarget it without changing frequency. */
@Suppress("UNUSED_PARAMETER")
internal fun lyricFollowMotion(
    intervalMs: Long?,
    isInterlude: Boolean = false,
): LyricFollowMotion {
    return LyricFollowMotion(LyricAmMotionSpec.DAMPING_RATIO, LyricAmMotionSpec.STIFFNESS)
}
