package com.rawsmusic.core.ui.widget

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * One optical definition for the fixed bottom chrome surfaces.
 *
 * Normal mode keeps the layered mini-player material that already matched the target.
 * Performance mode is deliberately different, but it degrades both the mini player and
 * navigation body in the same way instead of silently disabling only one component.
 */
internal object BottomChromeGlassMaterial {
    const val EDGE_WIDTH_DP = 0.8f

    fun profile(performanceMode: Boolean): BottomChromeGlassProfile {
        return if (performanceMode) {
            BottomChromeGlassProfile(
                bodyBlurDp = 22f,
                bodyLensHeightDp = 0f,
                bodyLensAmountDp = 0f,
                combinedLayerEnabled = false,
                combinedBlurDp = 0f,
                combinedLensHeightDp = 0f,
                combinedLensAmountDp = 0f,
                dragLensHeightDp = 7f,
                dragLensAmountDp = 9f,
                dragChromaticAberration = false,
            )
        } else {
            BottomChromeGlassProfile(
                bodyBlurDp = 8f,
                bodyLensHeightDp = 18f,
                bodyLensAmountDp = 17f,
                combinedLayerEnabled = true,
                combinedBlurDp = 2f,
                combinedLensHeightDp = 10f,
                combinedLensAmountDp = 12f,
                dragLensHeightDp = 10f,
                dragLensAmountDp = 14f,
                dragChromaticAberration = true,
            )
        }
    }

    fun palette(
        isLight: Boolean,
        accent: Color,
        performanceMode: Boolean,
    ): BottomChromeGlassPalette {
        return if (isLight) {
            BottomChromeGlassPalette(
                fallbackSurface = Color.White.copy(alpha = if (performanceMode) 0.72f else 0.62f),
                neutralSurface = Color.White.copy(alpha = if (performanceMode) 0.30f else 0.13f),
                accentWash = accent.copy(alpha = if (performanceMode) 0.050f else 0.030f),
                mainHighlightAlpha = 0.20f,
                combinedHighlightAlpha = 0.16f,
                shadowColor = Color.Black.copy(alpha = 0.10f),
                edgeColor = Color.White.copy(alpha = 0.24f),
                combinedSurface = Color.White.copy(alpha = 0.025f),
            )
        } else {
            BottomChromeGlassPalette(
                fallbackSurface = Color(0xFF111114).copy(alpha = if (performanceMode) 0.74f else 0.66f),
                neutralSurface = Color(0xFF111114).copy(alpha = if (performanceMode) 0.36f else 0.17f),
                accentWash = accent.copy(alpha = if (performanceMode) 0.075f else 0.048f),
                mainHighlightAlpha = 0.12f,
                combinedHighlightAlpha = 0.10f,
                shadowColor = Color.Black.copy(alpha = 0.24f),
                edgeColor = Color.White.copy(alpha = 0.13f),
                combinedSurface = Color.White.copy(alpha = 0.015f),
            )
        }
    }
}

@Immutable
internal data class BottomChromeGlassProfile(
    val bodyBlurDp: Float,
    val bodyLensHeightDp: Float,
    val bodyLensAmountDp: Float,
    val combinedLayerEnabled: Boolean,
    val combinedBlurDp: Float,
    val combinedLensHeightDp: Float,
    val combinedLensAmountDp: Float,
    val dragLensHeightDp: Float,
    val dragLensAmountDp: Float,
    val dragChromaticAberration: Boolean,
)

@Immutable
internal data class BottomChromeGlassPalette(
    val fallbackSurface: Color,
    val neutralSurface: Color,
    val accentWash: Color,
    val mainHighlightAlpha: Float,
    val combinedHighlightAlpha: Float,
    val shadowColor: Color,
    val edgeColor: Color,
    val combinedSurface: Color,
)
