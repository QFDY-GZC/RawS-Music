package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.min

internal const val FULLSCREEN_CAROUSEL_LANE_RADIUS = 5
internal const val FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS = 4
internal const val FULLSCREEN_CAROUSEL_LANE_COUNT = FULLSCREEN_CAROUSEL_LANE_RADIUS * 2 + 1
internal const val FULLSCREEN_CAROUSEL_VISIBLE_LANE_COUNT =
    FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS * 2 + 1

private val FullscreenTranslationMultipliers = floatArrayOf(0f, 0.74f, 1.15f, 1.47f, 1.72f, 1.94f)
private val FullscreenScaleAnchors = floatArrayOf(1f, 0.82f, 0.70f, 0.61f, 0.54f, 0.48f)
private val FullscreenRotationAnchors = floatArrayOf(0f, 24f, 35f, 43f, 49f, 55f)
private val FullscreenAlphaAnchors = floatArrayOf(1f, 1f, 0.96f, 0.86f, 0.70f, 0f)

internal data class FullscreenArtworkCarouselMetrics(
    val viewportWidthPx: Float,
    val viewportHeightPx: Float,
    val coverSidePx: Float,
    val centerXPx: Float,
    val centerYPx: Float,
    val laneStridePx: Float,
)

internal data class FullscreenArtworkLaneTransform(
    val railOffset: Float,
    val translationX: Float,
    val scale: Float,
    val rotationY: Float,
    val pivotFractionX: Float,
    val alpha: Float,
    val zIndex: Float,
)

/**
 * Resolve the common geometry used by the full-cover Canvas and the zoomable current-art overlay.
 *
 * Eleven logical lanes are kept alive. At rest only offsets -4..4 have non-zero alpha; -5/+5 are
 * transition buffers that enter as the carousel approaches the next queue position.
 */
internal fun resolveFullscreenArtworkCarouselMetrics(
    viewportWidthPx: Float,
    viewportHeightPx: Float,
): FullscreenArtworkCarouselMetrics {
    val width = viewportWidthPx.coerceAtLeast(1f)
    val height = viewportHeightPx.coerceAtLeast(1f)
    val landscape = width >= height
    val coverSide = if (landscape) {
        min(height * 0.68f, width * 0.245f)
    } else {
        min(width * 0.62f, height * 0.48f)
    }.coerceAtLeast(1f)

    // Leave a little more room below the cover for reflection and title metadata.
    val centerY = if (landscape) height * 0.42f else height * 0.40f

    // laneStridePx represents the first side-rail translation and is also the neutral drag extent.
    // Far rails use a non-linear anchor curve in resolveFullscreenArtworkLaneTransform().
    val stride = coverSide * if (landscape) 0.74f else 0.70f

    return FullscreenArtworkCarouselMetrics(
        viewportWidthPx = width,
        viewportHeightPx = height,
        coverSidePx = coverSide,
        centerXPx = width * 0.5f,
        centerYPx = centerY,
        laneStridePx = stride,
    )
}

internal fun resolveFullscreenArtworkLaneTransform(
    railOffset: Float,
    metrics: FullscreenArtworkCarouselMetrics,
): FullscreenArtworkLaneTransform {
    val distance = abs(railOffset).coerceIn(0f, FULLSCREEN_CAROUSEL_LANE_RADIUS.toFloat())
    val sign = when {
        railOffset < 0f -> -1f
        railOffset > 0f -> 1f
        else -> 0f
    }
    // These anchors intentionally compress more strongly after the first rail. The previous
    // implementation multiplied one stride by distance while rotating rail one directly to 54°;
    // that combination made the carousel read as evenly spaced thin cards. A piecewise curve keeps
    // the neighbouring cover detailed, then packs the remaining rails into a real depth stack.
    val translationMultiplier = interpolateFullscreenAnchor(distance, FullscreenTranslationMultipliers)
    val scale = interpolateFullscreenAnchor(distance, FullscreenScaleAnchors)
    val rotationMagnitude = interpolateFullscreenAnchor(distance, FullscreenRotationAnchors)
    val alpha = interpolateFullscreenAnchor(distance, FullscreenAlphaAnchors).coerceIn(0f, 1f)
    val centerWeight = (1f - distance).coerceIn(0f, 1f)
    val edgePivot = if (railOffset < 0f) 0.26f else -0.26f

    return FullscreenArtworkLaneTransform(
        railOffset = railOffset,
        translationX = sign * metrics.coverSidePx * translationMultiplier,
        scale = scale,
        rotationY = sign * -rotationMagnitude,
        pivotFractionX = edgePivot * (1f - centerWeight),
        alpha = alpha,
        zIndex = 100f - distance * 10f,
    )
}

internal fun fullscreenCarouselDecodeSide(logicalOffset: Int): Int = when (abs(logicalOffset)) {
    0 -> 1440
    1 -> 1280
    2 -> 1024
    3 -> 768
    4 -> 512
    else -> 384
}

internal fun wrapFullscreenCarouselIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0
    return ((index % size) + size) % size
}

private fun smoothFullscreenCarouselStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun interpolateFullscreenAnchor(
    distance: Float,
    anchors: FloatArray,
): Float {
    val clamped = distance.coerceIn(0f, anchors.lastIndex.toFloat())
    val lowerIndex = floor(clamped).toInt().coerceIn(0, anchors.lastIndex)
    val upperIndex = (lowerIndex + 1).coerceAtMost(anchors.lastIndex)
    if (lowerIndex == upperIndex) return anchors[lowerIndex]
    val localProgress = smoothFullscreenCarouselStep(clamped - lowerIndex.toFloat())
    return lerpFullscreen(anchors[lowerIndex], anchors[upperIndex], localProgress)
}

private fun lerpFullscreen(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount.coerceIn(0f, 1f)

/**
 * Resolve the top-most visible non-centre lane under a pointer.
 *
 * The hit rectangle is intentionally a little wider than the perspectively compressed bitmap. When
 * the depth stack overlaps, the closest rendered lane centre wins; this keeps the exposed outer
 * rails reachable instead of letting a nearer z-layer consume every tap.
 */
internal fun resolveFullscreenArtworkTappedLane(
    positionX: Float,
    positionY: Float,
    metrics: FullscreenArtworkCarouselMetrics,
    progress: Float,
): Int? {
    return (-FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS..FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS)
        .asSequence()
        .filter { it != 0 }
        .map { logicalOffset ->
            logicalOffset to resolveFullscreenArtworkLaneTransform(
                railOffset = logicalOffset.toFloat() - progress,
                metrics = metrics,
            )
        }
        .filter { (_, transform) -> transform.alpha > 0.001f }
        .mapNotNull { (logicalOffset, transform) ->
            val drawSide = metrics.coverSidePx * transform.scale
            val halfWidth = (drawSide * 0.58f).coerceAtLeast(metrics.coverSidePx * 0.10f)
            val halfHeight = (drawSide * 0.54f).coerceAtLeast(metrics.coverSidePx * 0.10f)
            val centerX = metrics.centerXPx + transform.translationX
            val inside = positionX in (centerX - halfWidth)..(centerX + halfWidth) &&
                positionY in (metrics.centerYPx - halfHeight)..(metrics.centerYPx + halfHeight)
            if (!inside) null else {
                val normalizedDistance = kotlin.math.abs(positionX - centerX) / halfWidth
                Triple(logicalOffset, transform.zIndex, normalizedDistance)
            }
        }
        // Perspective rails overlap by design. Selecting the closest rendered centre keeps the
        // exposed outer rails clickable instead of always letting a nearer z-layer steal the tap.
        .minWithOrNull(
            compareBy<Triple<Int, Float, Float>> { it.third }
                .thenByDescending { it.second }
        )
        ?.first
}

/** One visual commit per rail keeps far-lane taps continuous instead of snapping the centre index. */
internal fun fullscreenCarouselSelectionSteps(laneOffset: Int): List<Int> {
    val clamped = laneOffset.coerceIn(
        -FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS,
        FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS,
    )
    if (clamped == 0) return emptyList()
    val direction = if (clamped > 0) 1 else -1
    return List(kotlin.math.abs(clamped)) { direction }
}


internal data class FullscreenCarouselSelectionFrame(
    val centerIndex: Int,
    val progress: Float,
)

/** Maps one eased multi-rail travel value onto an atomically re-bound centre plus local progress. */
internal fun resolveFullscreenCarouselSelectionFrame(
    startIndex: Int,
    laneOffset: Int,
    travelledRails: Float,
    queueSize: Int,
): FullscreenCarouselSelectionFrame {
    if (queueSize <= 0 || laneOffset == 0) {
        return FullscreenCarouselSelectionFrame(
            centerIndex = wrapFullscreenCarouselIndex(startIndex, queueSize),
            progress = 0f,
        )
    }
    val clampedOffset = laneOffset.coerceIn(
        -FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS,
        FULLSCREEN_CAROUSEL_VISIBLE_LANE_RADIUS,
    )
    val direction = if (clampedOffset < 0) -1 else 1
    val distance = abs(clampedOffset)
    val travelled = travelledRails.coerceIn(0f, distance.toFloat())
    val completed = floor(travelled).toInt().coerceIn(0, distance)
    val fraction = travelled - completed
    return FullscreenCarouselSelectionFrame(
        centerIndex = wrapFullscreenCarouselIndex(
            startIndex + direction * completed,
            queueSize,
        ),
        progress = if (completed >= distance) 0f else direction * fraction,
    )
}
