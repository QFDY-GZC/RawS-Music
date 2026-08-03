package com.rawsmusic.core.ui.widget.player

/** Pixel-space scene frame shared by the landscape player and the full-cover carousel. */
data class LandscapeFullCoverTransitionFrame(
    val sharedLeftPx: Float,
    val sharedTopPx: Float,
    val sharedWidthPx: Float,
    val sharedHeightPx: Float,
    val sourceContentAlpha: Float,
    val sourceContentScale: Float,
    val targetContentAlpha: Float,
    val targetContentScale: Float,
    val targetLaneRevealProgress: Float,
)

/**
 * Resolves one continuous landscape-player -> full-cover frame.
 *
 * The artwork is the shared lane: its real player bounds interpolate to the exact centre-lane
 * bounds returned by [resolveFullscreenArtworkCarouselMetrics]. Everything else follows the same
 * source-only / target-only rule used by PowerList transitions.
 */
fun resolveLandscapeFullCoverTransitionFrame(
    sourceLeftPx: Float,
    sourceTopPx: Float,
    sourceWidthPx: Float,
    sourceHeightPx: Float,
    viewportWidthPx: Float,
    viewportHeightPx: Float,
    progress: Float,
): LandscapeFullCoverTransitionFrame {
    val raw = progress.coerceIn(0f, 1f)
    val sharedProgress = smoothLandscapeFullCoverStep(raw)
    val metrics = resolveFullscreenArtworkCarouselMetrics(viewportWidthPx, viewportHeightPx)
    val targetLeft = metrics.centerXPx - metrics.coverSidePx * 0.5f
    val targetTop = metrics.centerYPx - metrics.coverSidePx * 0.5f

    val sourceExit = smoothLandscapeFullCoverStep((raw / 0.78f).coerceIn(0f, 1f))
    val targetEnter = smoothLandscapeFullCoverStep(
        ((raw - 0.10f) / 0.90f).coerceIn(0f, 1f)
    )

    return LandscapeFullCoverTransitionFrame(
        sharedLeftPx = lerpLandscapeFullCover(sourceLeftPx, targetLeft, sharedProgress),
        sharedTopPx = lerpLandscapeFullCover(sourceTopPx, targetTop, sharedProgress),
        sharedWidthPx = lerpLandscapeFullCover(
            sourceWidthPx.coerceAtLeast(1f),
            metrics.coverSidePx,
            sharedProgress,
        ),
        sharedHeightPx = lerpLandscapeFullCover(
            sourceHeightPx.coerceAtLeast(1f),
            metrics.coverSidePx,
            sharedProgress,
        ),
        sourceContentAlpha = 1f - sourceExit,
        sourceContentScale = lerpLandscapeFullCover(1f, 0.84f, sourceExit),
        targetContentAlpha = targetEnter,
        targetContentScale = lerpLandscapeFullCover(0.62f, 1f, targetEnter),
        targetLaneRevealProgress = targetEnter,
    )
}

internal fun resolveFullscreenArtworkSceneLaneTransform(
    transform: FullscreenArtworkLaneTransform,
    revealProgress: Float,
): FullscreenArtworkLaneTransform {
    val reveal = smoothLandscapeFullCoverStep(revealProgress)
    return transform.copy(
        translationX = transform.translationX * reveal,
        scale = lerpLandscapeFullCover(0.58f, transform.scale, reveal),
        rotationY = transform.rotationY * reveal,
        pivotFractionX = transform.pivotFractionX * reveal,
        alpha = transform.alpha * reveal,
    )
}

private fun smoothLandscapeFullCoverStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpLandscapeFullCover(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount.coerceIn(0f, 1f)


data class FullCoverSourceBounds(
    val leftPx: Float,
    val topPx: Float,
    val rightPx: Float,
    val bottomPx: Float,
) {
    val widthPx: Float get() = rightPx - leftPx
    val heightPx: Float get() = bottomPx - topPx
}

/** Clips the reported centre artwork to the portion actually visible inside its Canvas. */
fun resolveVisibleFullCoverSourceBounds(
    artworkLeftPx: Float,
    artworkTopPx: Float,
    artworkRightPx: Float,
    artworkBottomPx: Float,
    canvasLeftPx: Float,
    canvasTopPx: Float,
    canvasRightPx: Float,
    canvasBottomPx: Float,
): FullCoverSourceBounds? {
    val left = maxOf(artworkLeftPx, canvasLeftPx)
    val top = maxOf(artworkTopPx, canvasTopPx)
    val right = minOf(artworkRightPx, canvasRightPx)
    val bottom = minOf(artworkBottomPx, canvasBottomPx)
    if (right - left <= 1f || bottom - top <= 1f) return null
    return FullCoverSourceBounds(left, top, right, bottom)
}

/** Direct gesture mapping used by both full-cover hosts during predictive return. */
fun resolveFullCoverPredictiveProgress(
    startSceneProgress: Float,
    backGestureProgress: Float,
): Float = startSceneProgress.coerceIn(0f, 1f) *
    (1f - backGestureProgress.coerceIn(0f, 1f))
