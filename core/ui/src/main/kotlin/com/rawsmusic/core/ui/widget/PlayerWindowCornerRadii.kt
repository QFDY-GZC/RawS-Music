package com.rawsmusic.core.ui.widget

/** Physical pixels, in Android Path order (TL, TR, BR, BL), independent of layout direction. */
internal data class PlayerWindowCornerRadii(
    val topLeft: Float,
    val topRight: Float,
    val bottomRight: Float,
    val bottomLeft: Float,
) {
    fun touchingEdges(left: Boolean, top: Boolean, right: Boolean, bottom: Boolean) =
        PlayerWindowCornerRadii(
            if (left && top) topLeft else 0f,
            if (right && top) topRight else 0f,
            if (right && bottom) bottomRight else 0f,
            if (left && bottom) bottomLeft else 0f,
        )

    companion object {
        val Zero = PlayerWindowCornerRadii(0f, 0f, 0f, 0f)
    }
}

internal fun resolvePlayerSurfaceCornerRadii(
    sourceRadius: Float,
    expanded: PlayerWindowCornerRadii,
    fraction: Float,
    width: Float,
    height: Float,
): PlayerWindowCornerRadii {
    val t = resolvePlayerArtworkHandoffFraction(fraction)
    val limit = (minOf(width, height) * 0.5f).coerceAtLeast(0f)
    fun radius(target: Float): Float {
        val start = sourceRadius.takeIf { it.isFinite() && it >= 0f } ?: 0f
        val end = target.takeIf { it.isFinite() && it >= 0f } ?: 0f
        return (start + (end - start) * t).coerceIn(0f, limit)
    }
    return PlayerWindowCornerRadii(
        radius(expanded.topLeft), radius(expanded.topRight),
        radius(expanded.bottomRight), radius(expanded.bottomLeft),
    )
}

/** Xiaomi resources describe the natural portrait orientation. */
internal fun rotatedPlayerWindowCorners(top: Float, bottom: Float, rotation: Int) = when (rotation) {
    1 -> PlayerWindowCornerRadii(top, bottom, bottom, top)
    2 -> PlayerWindowCornerRadii(bottom, bottom, top, top)
    3 -> PlayerWindowCornerRadii(bottom, top, top, bottom)
    else -> PlayerWindowCornerRadii(top, top, bottom, bottom)
}
