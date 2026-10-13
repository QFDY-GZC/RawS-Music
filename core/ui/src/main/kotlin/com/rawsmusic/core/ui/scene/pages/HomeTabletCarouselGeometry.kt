package com.rawsmusic.core.ui.scene.pages

internal fun homeCarouselLaneRadius(tabletLandscape: Boolean): Int = if (tabletLandscape) 5 else 3
internal fun tabletCarouselLaneAlpha(offset: Float): Float {
    if (!offset.isFinite()) return 0f
    val t = (5f - kotlin.math.abs(offset)).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}
internal fun homeCarouselUsesDefaultArtwork(enabled: Boolean, hasBitmap: Boolean, missing: Boolean): Boolean =
    enabled && !hasBitmap && missing

/** Keep side covers on almost the same visual plane, without the phone's double minification. */
internal fun tabletCarouselPlaneScale(offset: Float, centerScale: Float): Float {
    if (!offset.isFinite()) return centerScale
    val distance = kotlin.math.abs(offset)
    val near = distance.coerceIn(0f, 1f)
    val outer = ((distance - 1f) / 3f).coerceIn(0f, 1f)
    val nearEase = near * near * (3f - 2f * near)
    val outerEase = outer * outer * (3f - 2f * outer)
    return centerScale * (1f - 0.06f * nearEase - 0.04f * outerEase)
}

/** Four visible neighbours per side; one additional lane per side is a hidden decode buffer. */
internal fun tabletCarouselTranslationX(
    railOffset: Float, viewportWidth: Float, slotWidth: Float, edgePadding: Float,
): Float {
    if (!railOffset.isFinite() || !viewportWidth.isFinite() || !slotWidth.isFinite()) return 0f
    // Reserve the centre/near cover's projected half-widths first. Dividing the entire
    // rail equally by eight puts the nearest neighbours underneath the larger centre cover.
    val outerProjectedWidth = slotWidth.coerceAtLeast(0f) * 0.12f
    val outerCenter = ((viewportWidth - edgePadding.coerceAtLeast(0f) * 2f - outerProjectedWidth) / 2f)
        .coerceAtLeast(0f)
    val firstCenter = maxOf(outerCenter / 4f, slotWidth.coerceAtLeast(0f) * 0.50f +
        edgePadding.coerceAtLeast(0f) * 0.5f).coerceAtMost(outerCenter)
    val distance = kotlin.math.abs(railOffset)
    val position = if (distance <= 1f) firstCenter * distance else {
        firstCenter + (distance - 1f) * (outerCenter - firstCenter) / 3f
    }
    return if (railOffset < 0f) -position else position
}
