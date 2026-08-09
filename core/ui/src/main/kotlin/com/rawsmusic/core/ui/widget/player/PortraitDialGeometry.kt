package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import kotlin.math.min

/** Nine visible lanes: four above, the centre lane, and four below. */
internal const val PORTRAIT_DIAL_VISIBLE_RADIUS = 4

/** Hidden preloaded lanes needed while a far visible lane travels continuously to the centre. */
internal const val PORTRAIT_DIAL_RENDER_RADIUS = PORTRAIT_DIAL_VISIBLE_RADIUS * 2

internal const val HOME_HORIZONTAL_CAROUSEL_CORNER_RADIUS_DP = 18f
internal const val HOME_PORTRAIT_DIAL_CORNER_RADIUS_DP = 20f
internal const val FULLSCREEN_PORTRAIT_DIAL_CORNER_RADIUS_DP = 24f

internal data class PortraitDialMetrics(
    val cardSidePx: Float,
    val stridePx: Float,
    val centerXPx: Float,
    val centerYPx: Float,
)

internal data class PortraitDialLaneTransform(
    val translationXPx: Float,
    val translationYPx: Float,
    val scale: Float,
    val alpha: Float,
    val rotationX: Float,
    val rotationZ: Float,
    val zIndex: Float,
)

internal data class PortraitDialCardBounds(
    val leftPx: Float,
    val topPx: Float,
    val widthPx: Float,
    val heightPx: Float,
)

internal fun resolvePortraitDialMetrics(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): PortraitDialMetrics {
    val safeWidth = viewportWidthPx.coerceAtLeast(1f)
    val safeHeight = viewportHeightPx.coerceAtLeast(1f)
    // Step86 used 72% of the portrait width, which made the centre card dominate and forced
    // the outer cards into a short five-lane window. Keep one shared metric for home and full-screen
    // dial surfaces, but leave enough vertical room for all nine lanes and the lyric preview.
    val cardSide = min(safeWidth * 0.58f, safeHeight * 0.30f).coerceAtLeast(1f)
    return PortraitDialMetrics(
        cardSidePx = cardSide,
        stridePx = min(cardSide * 0.32f, safeHeight * 0.082f).coerceAtLeast(1f),
        centerXPx = safeWidth * 0.5f,
        centerYPx = safeHeight * 0.46f,
    )
}

internal fun resolvePortraitDialLaneTransform(
    position: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): PortraitDialLaneTransform {
    val metrics = resolvePortraitDialMetrics(viewportWidthPx, viewportHeightPx)
    val signedPosition = position.coerceIn(
        -PORTRAIT_DIAL_RENDER_RADIUS.toFloat(),
        PORTRAIT_DIAL_RENDER_RADIUS.toFloat(),
    )
    val distance = abs(signedPosition)
    val scale = interpolatePortraitDialStops(
        distance,
        1.00f,
        0.83f,
        0.68f,
        0.55f,
        0.44f,
        0.36f,
    )
    val alpha = interpolatePortraitDialStops(
        distance,
        1.00f,
        0.84f,
        0.64f,
        0.43f,
        0.24f,
        0f,
    )
    return PortraitDialLaneTransform(
        // The lane translation is expressed from the actual Box centre, not from the dial centre.
        // Both the home dial and the portrait full-screen dial place cards with Alignment.Center,
        // so including this base offset keeps the rendered card, tap target and shared-element
        // direct owner handoff on the exact same axis.
        translationXPx = metrics.centerXPx - viewportWidthPx.coerceAtLeast(1f) * 0.5f,
        translationYPx =
            metrics.centerYPx - viewportHeightPx.coerceAtLeast(1f) * 0.5f +
                signedPosition * metrics.stridePx,
        scale = scale,
        alpha = alpha,
        rotationX = -signedPosition * 2.6f,
        rotationZ = 0f,
        zIndex = 40f - distance * 6f,
    )
}


/**
 * Full-screen-only rail spacing. The home dial keeps the compact shared geometry used by the
 * header, while the portrait full-cover rail gives the outer lanes progressively more breathing
 * room so the nine cards read as separate depth planes instead of one compressed stack.
 */
internal fun resolvePortraitDialFullscreenLaneTransform(
    position: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): PortraitDialLaneTransform {
    val base = resolvePortraitDialLaneTransform(position, viewportWidthPx, viewportHeightPx)
    val metrics = resolvePortraitDialMetrics(viewportWidthPx, viewportHeightPx)
    val distance = abs(position).coerceIn(0f, PORTRAIT_DIAL_RENDER_RADIUS.toFloat())
    val separation = interpolatePortraitDialStops(
        distance,
        1.00f,
        1.00f,
        1.06f,
        1.13f,
        1.20f,
        1.28f,
    )
    val centreTranslationY = metrics.centerYPx - viewportHeightPx.coerceAtLeast(1f) * 0.5f
    return base.copy(
        translationYPx = centreTranslationY +
            (base.translationYPx - centreTranslationY) * separation,
    )
}

internal fun resolvePortraitDialCardBoundsInRoot(
    containerLeftInRootPx: Float,
    containerTopInRootPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): PortraitDialCardBounds {
    val metrics = resolvePortraitDialMetrics(viewportWidthPx, viewportHeightPx)
    return PortraitDialCardBounds(
        leftPx = containerLeftInRootPx + metrics.centerXPx - metrics.cardSidePx * 0.5f,
        topPx = containerTopInRootPx + metrics.centerYPx - metrics.cardSidePx * 0.5f,
        widthPx = metrics.cardSidePx,
        heightPx = metrics.cardSidePx,
    )
}

internal fun nearestPortraitDialVirtualIndex(
    currentVirtualIndex: Int,
    targetWrappedIndex: Int,
    size: Int,
): Int {
    if (size <= 0) return 0
    val currentWrapped = ((currentVirtualIndex % size) + size) % size
    val forward = (targetWrappedIndex - currentWrapped + size) % size
    val backward = (currentWrapped - targetWrappedIndex + size) % size
    return if (forward <= backward) currentVirtualIndex + forward else currentVirtualIndex - backward
}

private fun interpolatePortraitDialStops(distance: Float, vararg stops: Float): Float {
    if (stops.isEmpty()) return 0f
    val safeDistance = distance.coerceIn(0f, (stops.size - 1).toFloat())
    val lower = safeDistance.toInt().coerceIn(0, stops.lastIndex)
    val upper = (lower + 1).coerceAtMost(stops.lastIndex)
    if (lower == upper) return stops[lower]
    val local = smoothPortraitDialStep(safeDistance - lower)
    return lerpPortraitDial(stops[lower], stops[upper], local)
}

private fun smoothPortraitDialStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpPortraitDial(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount.coerceIn(0f, 1f)

/**
 * Reveals one portrait-dial side lane from the shared centre lane along the same distance-driven
 * geometry used at rest. This mirrors the landscape full-cover PowerList scene reveal: translation,
 * depth, rotation and alpha all grow from the centre instead of appearing through a flat fade.
 */
internal fun resolvePortraitDialSceneLaneTransform(
    position: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    revealProgress: Float,
): PortraitDialLaneTransform {
    val transform = resolvePortraitDialFullscreenLaneTransform(
        position = position,
        viewportWidthPx = viewportWidthPx,
        viewportHeightPx = viewportHeightPx,
    )
    val metrics = resolvePortraitDialMetrics(viewportWidthPx, viewportHeightPx)
    val reveal = smoothPortraitDialStep(revealProgress)
    val centerTranslationX = metrics.centerXPx - viewportWidthPx.coerceAtLeast(1f) * 0.5f
    val centerTranslationY = metrics.centerYPx - viewportHeightPx.coerceAtLeast(1f) * 0.5f
    val laneDeltaX = transform.translationXPx - centerTranslationX
    val laneDeltaY = transform.translationYPx - centerTranslationY
    return transform.copy(
        translationXPx = centerTranslationX + laneDeltaX * reveal,
        translationYPx = centerTranslationY + laneDeltaY * reveal,
        scale = lerpPortraitDial(0.58f, transform.scale, reveal),
        alpha = transform.alpha * reveal,
        rotationX = transform.rotationX * reveal,
        rotationZ = transform.rotationZ * reveal,
    )
}
