package com.rawsmusic.module.data.prefs

/** Independent geometry/style controls for the bottom navigation surface. */
data class BottomNavigationStyleSettings(
    val heightDp: Float = 64f,
    val bottomLiftDp: Float = 0f,
    val cornerRadiusDp: Float = 32f,
    val leadingMarginDp: Float = 16f,
    val trailingMarginDp: Float = 20f,
    val cornerCurve: Float = 2f,
) {
    fun normalized(): BottomNavigationStyleSettings {
        val normalizedHeight = heightDp.coerceIn(44f, 96f)
        return copy(
            heightDp = normalizedHeight,
            bottomLiftDp = bottomLiftDp.coerceIn(0f, 64f),
            cornerRadiusDp = cornerRadiusDp.coerceIn(0f, normalizedHeight / 2f),
            leadingMarginDp = leadingMarginDp.coerceIn(0f, 64f),
            trailingMarginDp = trailingMarginDp.coerceIn(0f, 64f),
            cornerCurve = cornerCurve.coerceIn(1.2f, 8f),
        )
    }

    companion object {
        val Default = BottomNavigationStyleSettings()
    }
}
