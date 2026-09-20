package com.rawsmusic.core.ui.scene.pages

/**
 * HOME's VirtualList owns hard item bounds, while Text grows with LocalDensity.fontScale
 * (including RawSMusic's global UI font-size multiplier). Preserve the 100% geometry, then
 * add only the extra vertical text envelope required above 1x so section statistics and
 * card metadata cannot be clipped by their physical holder.
 */
internal fun homeFontScaleGrowth(fontScale: Float): Float =
    fontScale.coerceAtLeast(1f) - 1f

internal fun homeSectionHeaderHeightDp(fontScale: Float): Float =
    54f + 48f * homeFontScaleGrowth(fontScale)

internal fun homeCardRowExtraHeightDp(fontScale: Float): Float =
    82f + 42f * homeFontScaleGrowth(fontScale)

internal fun homeMostPlayedContentHeightDp(fontScale: Float): Float =
    74f + 40f * homeFontScaleGrowth(fontScale)

internal fun homeMostPlayedHolderHeightDp(fontScale: Float): Float =
    homeMostPlayedContentHeightDp(fontScale) + 8f
