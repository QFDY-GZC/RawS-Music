package com.rawsmusic.core.ui.widget

internal data class AmPlayerArtworkRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)

/**
 * Recreates the curved screen-space path of AM's artwork handoff.
 *
 * AM transforms the artwork inside a moving bottom sheet. RawSMusic draws the handoff in a root
 * overlay, so a direct rectangle lerp loses the compound motion and looks like a straight diagonal.
 * These control points preserve that compound path while keeping size strictly tied to the finger.
 */
internal fun resolveAmPlayerArtworkRect(
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    targetLeft: Float,
    targetTop: Float,
    targetWidth: Float,
    targetHeight: Float,
    fraction: Float,
): AmPlayerArtworkRect {
    val t = fraction.coerceIn(0f, 1f)
    val sourceCenterX = sourceLeft + sourceWidth * 0.5f
    val sourceCenterY = sourceTop + sourceHeight * 0.5f
    val targetCenterX = targetLeft + targetWidth * 0.5f
    val targetCenterY = targetTop + targetHeight * 0.5f
    val deltaX = targetCenterX - sourceCenterX
    val deltaY = targetCenterY - sourceCenterY

    // The artwork first leaves the mini player laterally, then turns upward into the large cover.
    // Keeping both controls relative to the endpoints makes the same path reversible on collapse.
    val control1X = sourceCenterX + deltaX * 0.38f
    val control1Y = sourceCenterY + deltaY * 0.08f
    val control2X = sourceCenterX + deltaX * 0.92f
    val control2Y = sourceCenterY + deltaY * 0.62f
    val centerX = cubicBezier(
        start = sourceCenterX,
        control1 = control1X,
        control2 = control2X,
        end = targetCenterX,
        fraction = t,
    )
    val centerY = cubicBezier(
        start = sourceCenterY,
        control1 = control1Y,
        control2 = control2Y,
        end = targetCenterY,
        fraction = t,
    )
    val width = lerpMotionValue(sourceWidth, targetWidth, t)
    val height = lerpMotionValue(sourceHeight, targetHeight, t)
    return AmPlayerArtworkRect(
        left = centerX - width * 0.5f,
        top = centerY - height * 0.5f,
        width = width,
        height = height,
    )
}

private fun cubicBezier(
    start: Float,
    control1: Float,
    control2: Float,
    end: Float,
    fraction: Float,
): Float {
    val inverse = 1f - fraction
    return inverse * inverse * inverse * start +
        3f * inverse * inverse * fraction * control1 +
        3f * inverse * fraction * fraction * control2 +
        fraction * fraction * fraction * end
}

private fun lerpMotionValue(start: Float, end: Float, fraction: Float): Float {
    return start + (end - start) * fraction
}
