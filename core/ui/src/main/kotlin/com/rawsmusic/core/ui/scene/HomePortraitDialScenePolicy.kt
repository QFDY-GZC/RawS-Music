package com.rawsmusic.core.ui.scene

/**
 * One PowerList-like scene frame for the home dial -> portrait full-cover transition.
 *
 * Both endpoints are real holder rectangles measured in the same host-local coordinate space.
 * Geometry must not be re-derived from viewport metrics here: the home holder lives inside a
 * padded 340dp carousel while the full-screen holder lives in the root scene, and visual parent
 * transforms can otherwise make a mathematically similar target differ from the rendered holder.
 */
internal data class HomePortraitDialSceneFrame(
    val sharedLeftPx: Float,
    val sharedTopPx: Float,
    val sharedWidthPx: Float,
    val sharedHeightPx: Float,
    val sharedCornerRadiusDp: Float,
    val homeForegroundAlpha: Float,
    val homeForegroundScale: Float,
    val fullscreenBackdropAlpha: Float,
    val fullscreenContentAlpha: Float,
    val fullscreenContentScale: Float,
    val targetLaneRevealProgress: Float,
)

internal fun resolveHomePortraitDialSceneFrame(
    sourceLeftPx: Float,
    sourceTopPx: Float,
    sourceWidthPx: Float,
    sourceHeightPx: Float,
    targetLeftPx: Float,
    targetTopPx: Float,
    targetWidthPx: Float,
    targetHeightPx: Float,
    sourceCornerRadiusDp: Float,
    targetCornerRadiusDp: Float,
    progress: Float,
): HomePortraitDialSceneFrame {
    val raw = progress.coerceIn(0f, 1f)
    val sharedProgress = smoothHomePortraitDialStep(raw)
    val sourceExit = smoothHomePortraitDialStep((raw / 0.78f).coerceIn(0f, 1f))
    val targetEnter = smoothHomePortraitDialStep(
        ((raw - 0.10f) / 0.90f).coerceIn(0f, 1f)
    )

    return HomePortraitDialSceneFrame(
        sharedLeftPx = lerpHomePortraitDial(sourceLeftPx, targetLeftPx, sharedProgress),
        sharedTopPx = lerpHomePortraitDial(sourceTopPx, targetTopPx, sharedProgress),
        sharedWidthPx = lerpHomePortraitDial(
            sourceWidthPx.coerceAtLeast(1f),
            targetWidthPx.coerceAtLeast(1f),
            sharedProgress,
        ),
        sharedHeightPx = lerpHomePortraitDial(
            sourceHeightPx.coerceAtLeast(1f),
            targetHeightPx.coerceAtLeast(1f),
            sharedProgress,
        ),
        sharedCornerRadiusDp = lerpHomePortraitDial(
            sourceCornerRadiusDp.coerceAtLeast(0f),
            targetCornerRadiusDp.coerceAtLeast(0f),
            sharedProgress,
        ),
        homeForegroundAlpha = 1f - sourceExit,
        homeForegroundScale = lerpHomePortraitDial(1f, 0.84f, sourceExit),
        fullscreenBackdropAlpha = targetEnter,
        fullscreenContentAlpha = targetEnter,
        fullscreenContentScale = lerpHomePortraitDial(0.62f, 1f, targetEnter),
        targetLaneRevealProgress = targetEnter,
    )
}

private fun smoothHomePortraitDialStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun lerpHomePortraitDial(start: Float, end: Float, amount: Float): Float =
    start + (end - start) * amount.coerceIn(0f, 1f)

/**
 * Home-carousel reflection reveal during a return from the portrait full-cover dial.
 *
 * Scene progress is 1 at settled full-screen and 0 at settled home. The home centre cover remains
 * owned by the shared lane until the transition ends, but its reflection can re-enter late in the
 * return. Keep it fully hidden until the scene reaches the final 40%, then restore it smoothly.
 */
internal const val HOME_PORTRAIT_DIAL_RETURN_REFLECTION_THRESHOLD = 0.40f

internal fun resolveHomePortraitDialReturnReflectionAlpha(
    sceneProgress: Float,
    returningToHome: Boolean,
): Float {
    if (!returningToHome) return 0f
    val progress = sceneProgress.coerceIn(0f, 1f)
    val reveal = (
        (HOME_PORTRAIT_DIAL_RETURN_REFLECTION_THRESHOLD - progress) /
            HOME_PORTRAIT_DIAL_RETURN_REFLECTION_THRESHOLD
        ).coerceIn(0f, 1f)
    return smoothHomePortraitDialStep(reveal)
}
