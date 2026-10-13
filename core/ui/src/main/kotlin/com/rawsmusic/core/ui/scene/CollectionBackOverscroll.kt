package com.rawsmusic.core.ui.scene

import kotlin.math.abs
import kotlin.math.max

/** Only the reverse/origin boundary. Forward-end ownership is intentionally unchanged. */
internal fun collectionBackOriginOverscroll(rawProgress: Float): Float =
    if (rawProgress.isFinite()) rawProgress.coerceAtMost(0f) else 0f

internal fun collectionBackOriginOffset(overflow: Float, direction: Float, width: Float): Float =
    (overflow * direction * width * 0.25f).toInt().toFloat()

internal fun collectionOverscrollReturnFraction(timeFraction: Float): Float {
    val remaining = 1f - timeFraction.coerceIn(0f, 1f)
    return remaining * remaining * remaining
}

internal fun collectionOverscrollReturnMillis(overflow: Float): Int =
    (max(0.35f, abs(overflow) / 2f) * 1000f).toInt()
