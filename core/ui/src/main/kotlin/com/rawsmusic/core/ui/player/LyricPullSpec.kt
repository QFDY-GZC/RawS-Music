package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/** Timing and interpolation constants for the lyric pull timeline. */
internal object LyricPullSpec {
    const val DURATION_MS: Int = 550
    const val MAX_ITEM_DELAY_MS: Int = 50
    const val MIN_ITEM_DELAY_MS: Int = 4
    private const val DECELERATE_DISTANCE_NORMALIZER_PX = 1_000_000f

    fun itemDelayMs(pullDistancePx: Float, viewportHeightPx: Float): Int {
        if (!pullDistancePx.isFinite() || !viewportHeightPx.isFinite() || viewportHeightPx <= 0f) {
            return MAX_ITEM_DELAY_MS
        }
        val ratio = (abs(pullDistancePx) / viewportHeightPx).coerceIn(0f, 1f)
        return (MAX_ITEM_DELAY_MS + ratio * (MIN_ITEM_DELAY_MS - MAX_ITEM_DELAY_MS)).roundToInt()
    }

    /** Android-style deceleration with a distance-derived factor. */
    fun interpolate(progress: Float, movementPx: Float): Float {
        val input = progress.coerceIn(0f, 1f)
        val factor = 1f + (
            abs(movementPx).coerceAtMost(DECELERATE_DISTANCE_NORMALIZER_PX) /
                DECELERATE_DISTANCE_NORMALIZER_PX
            )
        return if (factor == 1f) {
            1f - (1f - input) * (1f - input)
        } else {
            1f - (1f - input).pow(2f * factor)
        }
    }
}
