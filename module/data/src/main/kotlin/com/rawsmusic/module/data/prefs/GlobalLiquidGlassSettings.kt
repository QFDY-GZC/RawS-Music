package com.rawsmusic.module.data.prefs

data class GlobalLiquidGlassSettings(
    val blurRadiusDp: Float = 8f,
    val refractionHeightFraction: Float = 0.75f,
    val refractionAmountFraction: Float = 0.375f,
    val chromaticAberration: Float = 1f,
    val vibrancyStrength: Float = 1f,
    val highlightStrength: Float = 1f,
    val shadowStrength: Float = 1f,
) {
    fun normalized(): GlobalLiquidGlassSettings = copy(
        blurRadiusDp = blurRadiusDp.finiteOr(8f).coerceIn(0f, 32f),
        refractionHeightFraction = refractionHeightFraction.finiteOr(0.75f).coerceIn(0f, 1f),
        refractionAmountFraction = refractionAmountFraction.finiteOr(0.375f).coerceIn(0f, 1f),
        chromaticAberration = chromaticAberration.finiteOr(1f).coerceIn(0f, 1f),
        vibrancyStrength = vibrancyStrength.finiteOr(1f).coerceIn(0f, 2f),
        highlightStrength = highlightStrength.finiteOr(1f).coerceIn(0f, 1.5f),
        shadowStrength = shadowStrength.finiteOr(1f).coerceIn(0f, 1.5f),
    )

    companion object {
        val Default = GlobalLiquidGlassSettings()
    }
}

private fun Float.finiteOr(fallback: Float): Float = if (isFinite()) this else fallback
