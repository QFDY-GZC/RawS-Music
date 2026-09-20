package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Pure ArtworkPager-style position helpers.
 *
 * Reference keeps one integer list index plus one signed fractional position. During a rapid
 * same-direction recapture Raw keeps three retained identities A/B/C on one absolute coordinate s:
 * A=0, B=1, C=2. Crossing s=1 is therefore geometry only, never a second transition lifecycle.
 */
internal fun referenceContinuousHolderSignedDistance(
    absolutePosition: Float,
    holderOffsetPages: Float,
    directionSign: Int,
): Float = directionSign.coerceIn(-1, 1).toFloat() * (holderOffsetPages - absolutePosition)

internal fun referenceContinuousLocalProgress(absolutePosition: Float): Float =
    (absolutePosition - 1f).coerceIn(0f, 1f)

internal fun referenceContinuousBaseRatio(absolutePosition: Float): Float =
    absolutePosition.coerceIn(0f, 1f)
