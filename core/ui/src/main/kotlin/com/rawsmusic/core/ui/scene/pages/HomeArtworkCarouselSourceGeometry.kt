package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.ui.widget.player.HOME_HORIZONTAL_CAROUSEL_CORNER_RADIUS_DP
import com.rawsmusic.core.ui.widget.player.HOME_PORTRAIT_DIAL_CORNER_RADIUS_DP

internal const val HOME_CANVAS_CENTER_SCALE = 0.62f
internal const val HOME_CANVAS_MIN_CORNER_SCALE = 0.72f

internal data class HomeCanvasBitmapCrop(val left: Int, val top: Int, val right: Int, val bottom: Int)

internal fun resolveHomeCanvasBitmapCrop(
    width: Int, height: Int, destinationWidth: Float, destinationHeight: Float,
): HomeCanvasBitmapCrop? {
    if (width <= 0 || height <= 0 ||
        !destinationWidth.isFinite() || !destinationHeight.isFinite() ||
        destinationWidth <= 0f || destinationHeight <= 0f) return null
    val ratio = destinationWidth / destinationHeight
    return if (width.toFloat() / height > ratio) {
        val cropWidth = (height * ratio).toInt().coerceIn(1, width)
        val left = (width - cropWidth) / 2
        HomeCanvasBitmapCrop(left, 0, left + cropWidth, height)
    } else {
        val cropHeight = (width / ratio).toInt().coerceIn(1, height)
        val top = (height - cropHeight) / 2
        HomeCanvasBitmapCrop(0, top, width, top + cropHeight)
    }
}

internal fun resolveHomeCanvasSlotSidePx(canvasWidthPx: Float, canvasHeightPx: Float): Float =
    minOf(canvasWidthPx.coerceAtLeast(0f), canvasHeightPx.coerceAtLeast(0f) * 1.2f)

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
 * The logical square is height-bounded on wide windows. Share that exact square with the native
 * renderer so the full cover and its launch/hit bounds agree instead of describing a clipped slice.
 */
internal fun resolveHomeCanvasCenterArtworkLocalBounds(
    canvasWidthPx: Float,
    canvasHeightPx: Float,
): HomeCanvasArtworkLocalBounds? {
    if (canvasWidthPx <= 1f || canvasHeightPx <= 1f) return null
    val slotSide = resolveHomeCanvasSlotSidePx(canvasWidthPx, canvasHeightPx)
    val side = slotSide * HOME_CANVAS_CENTER_SCALE
    val centerX = canvasWidthPx * 0.5f
    val centerY = slotSide * 0.5f
    val left = (centerX - side * 0.5f).coerceIn(0f, canvasWidthPx)
    val top = (centerY - side * 0.5f).coerceIn(0f, canvasHeightPx)
    val right = (centerX + side * 0.5f).coerceIn(0f, canvasWidthPx)
    val bottom = (centerY + side * 0.5f).coerceIn(0f, canvasHeightPx)
    if (right - left <= 1f || bottom - top <= 1f) return null
    return HomeCanvasArtworkLocalBounds(left, top, right, bottom)
}
