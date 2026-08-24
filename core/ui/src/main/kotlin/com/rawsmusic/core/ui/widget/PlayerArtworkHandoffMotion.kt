package com.rawsmusic.core.ui.widget

internal data class PlayerArtworkHandoffRect(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
)


/**
 * Visible bitmap rectangle inside the outer PLAYER AA item.
 *
 * Keep this formula shared by PlayerMainPage measurement and MAIN <-> PLAYER's frozen endpoint.
 * Reference keeps item geometry and AAImageView-local inset/scale as separate layers; using the same
 * two-layer calculation at both endpoints prevents a final-frame resize when the real player art
 * takes ownership after the shared actor.
 */
internal fun resolvePlayerArtworkContentRect(
    outerLeft: Float,
    outerTop: Float,
    outerWidth: Float,
    outerHeight: Float,
    contentInsetPx: Float,
    contentScale: Float,
): PlayerArtworkHandoffRect {
    val inset = contentInsetPx.coerceAtLeast(0f)
    val innerWidth = (outerWidth - inset * 2f).coerceAtLeast(1f)
    val innerHeight = (outerHeight - inset * 2f).coerceAtLeast(1f)
    val scale = contentScale.coerceIn(0.01f, 1f)
    val scaledWidth = innerWidth * scale
    val scaledHeight = innerHeight * scale
    return PlayerArtworkHandoffRect(
        left = outerLeft + inset + (innerWidth - scaledWidth) * 0.5f,
        top = outerTop + inset + (innerHeight - scaledHeight) * 0.5f,
        width = scaledWidth,
        height = scaledHeight,
    )
}

/**
 * Absolute MAIN <-> PLAYER sheet geometry fraction.
 *
 * Gesture expansion already represents the physical sheet position, while release/settle timing is
 * eased by PlayerSceneController. Do not ease it again in the renderer: the reference recording
 * keeps surface geometry and artwork size linearly locked to one fraction. Ordinary playback
 * information has no independent screen-space translation; it is revealed by the surface clip.
 */
internal fun resolvePlayerArtworkHandoffFraction(fraction: Float): Float =
    fraction.coerceIn(0f, 1f)

/**
 * Ordinary PLAYER information keeps a substantial upward travel, but that travel is rendered
 * inside the same dynamically clipped PLAYER surface as the backdrop. The reference motion is
 * roughly two thirds of the collapsed surface top: enough to preserve the sheet-like rise without
 * letting title/progress/controls become an independent screen-space layer.
 */
private const val PLAYER_SURFACE_CONTENT_TRAVEL_RATIO = 0.67f

internal fun resolvePlayerSurfaceContentOffsetY(
    collapsedSurfaceTop: Float,
    fraction: Float,
): Float {
    val t = resolvePlayerArtworkHandoffFraction(fraction)
    return collapsedSurfaceTop.coerceAtLeast(0f) *
        PLAYER_SURFACE_CONTENT_TRAVEL_RATIO *
        (1f - t)
}

internal data class PlayerSurfaceHandoffBounds(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val cornerRadius: Float,
)

/**
 * PLAYER surface geometry shared by the backdrop and ordinary player content.
 *
 * The information layer deliberately has no independent screen-space translation. It stays in the
 * final PLAYER local coordinate system and becomes visible only where this surface has expanded.
 * This keeps title/progress/transport visually inside the player instead of flying in from the
 * physical display edge.
 */
internal fun resolvePlayerSurfaceHandoffBounds(
    sourceLeft: Float,
    sourceTop: Float,
    sourceRight: Float,
    sourceBottom: Float,
    sourceCornerRadius: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    fraction: Float,
): PlayerSurfaceHandoffBounds {
    val t = resolvePlayerArtworkHandoffFraction(fraction)
    return PlayerSurfaceHandoffBounds(
        left = lerpMotionValue(sourceLeft, 0f, t),
        top = lerpMotionValue(sourceTop, 0f, t),
        right = lerpMotionValue(sourceRight, viewportWidth.coerceAtLeast(0f), t),
        bottom = lerpMotionValue(sourceBottom, viewportHeight.coerceAtLeast(0f), t),
        cornerRadius = lerpMotionValue(sourceCornerRadius.coerceAtLeast(0f), 0f, t),
    )
}

/**
 * Curved screen-space handoff between the real MiniPlayer and PLAYER artwork endpoints.
 *
 * The center follows a cubic path rather than a straight rectangle lerp: horizontal displacement
 * leads slightly while vertical displacement catches up through the middle of the gesture. Width,
 * height and corner radius use the same endpoint-flat motion fraction, so the spatial arc remains
 * visible without leaving a large residual scale change at either end.
 */
internal fun resolvePlayerArtworkHandoffRect(
    sourceLeft: Float,
    sourceTop: Float,
    sourceWidth: Float,
    sourceHeight: Float,
    targetLeft: Float,
    targetTop: Float,
    targetWidth: Float,
    targetHeight: Float,
    fraction: Float,
): PlayerArtworkHandoffRect {
    val t = resolvePlayerArtworkHandoffFraction(fraction)
    val sourceCenterX = sourceLeft + sourceWidth * 0.5f
    val sourceCenterY = sourceTop + sourceHeight * 0.5f
    val targetCenterX = targetLeft + targetWidth * 0.5f
    val targetCenterY = targetTop + targetHeight * 0.5f
    val deltaX = targetCenterX - sourceCenterX
    val deltaY = targetCenterY - sourceCenterY

    // Ratios measured from the supplied reference recording after normalizing every frame by
    // artwork-size progress. X leads strongly during the first half while Y catches up later:
    // fitted cubic controls are approximately X=(0.632, 0.967), Y=(0.250, 0.582). Keeping these
    // ratios endpoint-relative makes the same arc reversible for PLAYER -> MAIN.
    val control1X = sourceCenterX + deltaX * 0.632f
    val control1Y = sourceCenterY + deltaY * 0.250f
    val control2X = sourceCenterX + deltaX * 0.967f
    val control2Y = sourceCenterY + deltaY * 0.582f
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
    return PlayerArtworkHandoffRect(
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
