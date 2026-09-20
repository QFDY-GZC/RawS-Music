package com.rawsmusic.core.ui.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Mini-player body using the same source-project glass stack as the bottom navigation body.
 * No manual outline, accent wash, or second combined full-surface lens is added here.
 */
@Composable
fun LiquidGlassMiniPlayerBg(
    backdrop: Backdrop? = null,
    isLight: Boolean = false,
) {
    val scheme = MiuixTheme.colorScheme
    val performanceMode by PersonalizationPreferences.performanceMode.collectAsState()
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val resolvedIsLight = isLight || scheme.background.luminance() > 0.5f
    val shape = RoundedCornerShape(32.dp)
    val containerColor = if (resolvedIsLight) {
        Color(0xFFFAFAFA).copy(alpha = if (performanceMode) 0.52f else 0.40f)
    } else {
        Color(0xFF121212).copy(alpha = if (performanceMode) 0.52f else 0.40f)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .then(
                if (backdrop != null && !performanceMode) {
                    Modifier.drawBackdrop(
                        backdrop = backdrop,
                        shape = { shape },
                        effects = {
                            val minDimension = size.minDimension
                            vibrancy(glassSettings.vibrancyStrength)
                            blur(glassSettings.blurRadiusDp.dp.toPx())
                            lens(
                                refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.5f,
                                refractionAmount = glassSettings.refractionAmountFraction * minDimension,
                                depthEffect = true,
                                chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                            )
                        },
                        highlight = {
                            Highlight.Default.copy(alpha = (0.30f * glassSettings.highlightStrength).coerceIn(0f, 1f))
                        },
                        shadow = {
                            Shadow(alpha = (0.10f * glassSettings.shadowStrength).coerceIn(0f, 1f))
                        },
                        onDrawSurface = { drawRect(containerColor) },
                    )
                } else {
                    Modifier.background(containerColor, shape)
                },
            ),
    )
}
