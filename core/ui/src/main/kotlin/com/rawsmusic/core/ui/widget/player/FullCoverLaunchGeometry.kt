package com.rawsmusic.core.ui.widget.player

import androidx.compose.ui.geometry.Rect

/**
 * Maps a source artwork rectangle captured in the portrait main window into the landscape window.
 * The mapping follows the display rotation and scales both axes to the actual target viewport.
 */
fun resolveLandscapeLaunchSourceBounds(
    sourceBounds: Rect,
    sourceViewportWidthPx: Float,
    sourceViewportHeightPx: Float,
    targetViewportWidthPx: Float,
    targetViewportHeightPx: Float,
    targetRotationDegrees: Int,
): Rect? {
    if (
        sourceBounds.width <= 1f || sourceBounds.height <= 1f ||
        sourceViewportWidthPx <= 1f || sourceViewportHeightPx <= 1f ||
        targetViewportWidthPx <= 1f || targetViewportHeightPx <= 1f
    ) {
        return null
    }

    val normalizedRotation = ((targetRotationDegrees % 360) + 360) % 360
    val mapped = when (normalizedRotation) {
        90 -> {
            val scaleX = targetViewportWidthPx / sourceViewportHeightPx
            val scaleY = targetViewportHeightPx / sourceViewportWidthPx
            Rect(
                left = (sourceViewportHeightPx - sourceBounds.bottom) * scaleX,
                top = sourceBounds.left * scaleY,
                right = (sourceViewportHeightPx - sourceBounds.top) * scaleX,
                bottom = sourceBounds.right * scaleY,
            )
        }
        270 -> {
            val scaleX = targetViewportWidthPx / sourceViewportHeightPx
            val scaleY = targetViewportHeightPx / sourceViewportWidthPx
            Rect(
                left = sourceBounds.top * scaleX,
                top = (sourceViewportWidthPx - sourceBounds.right) * scaleY,
                right = sourceBounds.bottom * scaleX,
                bottom = (sourceViewportWidthPx - sourceBounds.left) * scaleY,
            )
        }
        180 -> {
            val scaleX = targetViewportWidthPx / sourceViewportWidthPx
            val scaleY = targetViewportHeightPx / sourceViewportHeightPx
            Rect(
                left = (sourceViewportWidthPx - sourceBounds.right) * scaleX,
                top = (sourceViewportHeightPx - sourceBounds.bottom) * scaleY,
                right = (sourceViewportWidthPx - sourceBounds.left) * scaleX,
                bottom = (sourceViewportHeightPx - sourceBounds.top) * scaleY,
            )
        }
        else -> {
            val scaleX = targetViewportWidthPx / sourceViewportWidthPx
            val scaleY = targetViewportHeightPx / sourceViewportHeightPx
            Rect(
                left = sourceBounds.left * scaleX,
                top = sourceBounds.top * scaleY,
                right = sourceBounds.right * scaleX,
                bottom = sourceBounds.bottom * scaleY,
            )
        }
    }

    val clipped = Rect(
        left = mapped.left.coerceIn(0f, targetViewportWidthPx),
        top = mapped.top.coerceIn(0f, targetViewportHeightPx),
        right = mapped.right.coerceIn(0f, targetViewportWidthPx),
        bottom = mapped.bottom.coerceIn(0f, targetViewportHeightPx),
    )
    return clipped.takeIf { it.width > 1f && it.height > 1f }
}
