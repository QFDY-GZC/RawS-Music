package com.rawsmusic.module.data.prefs

data class MiniPlayerStyleSettings(
    val expandedHeightDp: Float = 62f,
    val compactHeightDp: Float = 54f,
    val cornerRadiusDp: Float = 22f,
    val leadingMarginDp: Float = 16f,
    val trailingMarginDp: Float = 20f,
    val cornerCurve: Float = 2f,
    val artworkSizeDp: Float = 44f,
    val originalArtworkCornerRadiusDp: Float = 8f,
    val artworkTextGapDp: Float = 8f,
    val controlGapDp: Float = 0f,
) {
    fun normalized(): MiniPlayerStyleSettings {
        val normalizedExpandedHeight = expandedHeightDp.coerceIn(36f, 104f)
        return copy(
            expandedHeightDp = normalizedExpandedHeight,
            compactHeightDp = compactHeightDp
                .coerceIn(34f, 88f)
                .coerceAtMost(normalizedExpandedHeight),
            cornerRadiusDp = cornerRadiusDp.coerceIn(0f, 52f),
            leadingMarginDp = leadingMarginDp.coerceIn(0f, 64f),
            trailingMarginDp = trailingMarginDp.coerceIn(0f, 64f),
            cornerCurve = cornerCurve.coerceIn(1.2f, 8f),
            // The live upper/lower artwork limits are derived from the current MiniPlayer height
            // and artwork mode in core/ui. Keep persistence broad here so changing the height is
            // the operation that constrains the artwork, never the other way around.
            artworkSizeDp = artworkSizeDp.coerceIn(16f, 64f),
            originalArtworkCornerRadiusDp = originalArtworkCornerRadiusDp.coerceIn(0f, 32f),
            artworkTextGapDp = artworkTextGapDp.coerceIn(0f, 24f),
            controlGapDp = controlGapDp.coerceIn(0f, 20f),
        )
    }

    companion object {
        val Default = MiniPlayerStyleSettings()
    }
}
