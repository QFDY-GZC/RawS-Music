package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.ui.widget.player.HOME_HORIZONTAL_CAROUSEL_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.HOME_PORTRAIT_DIAL_CORNER_RADIUS_DP

internal const val HOME_CANVAS_CENTER_SCALE = 0.62f
internal const val HOME_CANVAS_MIN_CORNER_SCALE = 0.72f

internal fun resolveHomeArtworkSourceCornerRadiusDp(
    style: HomeArtworkCarouselStyle,
): Float = when (style) {
    HomeArtworkCarouselStyle.CurrentCarousel ->
        HOME_HORIZONTAL_CAROUSEL_CORNER_RADIUS_DP * HOME_CANVAS_MIN_CORNER_SCALE
    HomeArtworkCarouselStyle.VerticalDial -> HOME_PORTRAIT_DIAL_CORNER_RADIUS_DP
}

internal data class HomeCanvasArtworkLocalBounds(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
) {
    val widthPx: Float get() = rightPx - leftPx
    val heightPx: Float get() = bottomPx - topPx
}

/**
 * Exact settled centre-cover rectangle used by [HomeArtworkCarouselCanvas]'s native renderer.
 *
 * The native Canvas uses a square logical slot whose side is the measured Canvas width, while the
 * home header itself has a fixed height. Clip the cover against the real layout height so launch
 * geometry describes only pixels that are actually visible.
 */
internal fun resolveHomeCanvasCenterArtworkLocalBounds(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
): HomeCanvasArtworkLocalBounds? {
    if (canvasWidthPx <= 1f || canvasHeightPx <= 1f) return null
    val side = canvasWidthPx * HOME_CANVAS_CENTER_SCALE
    val centerX = canvasWidthPx * 0.5f
    val centerY = canvasWidthPx * 0.5f
    val left = (centerX - side * 0.5f).coerceIn(0f, canvasWidthPx)
    val top = (centerY - side * 0.5f).coerceIn(0f, canvasHeightPx)
    val right = (centerX + side * 0.5f).coerceIn(0f, canvasWidthPx)
    val bottom = (centerY + side * 0.5f).coerceIn(0f, canvasHeightPx)
    if (right - left <= 1f || bottom - top <= 1f) return null
    return HomeCanvasArtworkLocalBounds(left, top, right, bottom)
}
