package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.HazeProgressive
import dev.chrisbanes.haze.blur.blurEffect

/** Shared by library chrome and settings. Keep this host in fixed viewport coordinates. */
@Composable
fun ProgressiveTopBlur(
    hazeState: HazeState,
    tintColor: Color,
    tintAlpha: Float,
    modifier: Modifier = Modifier,
    radius: Dp = 72.dp,
) {
    Box(modifier.hazeEffect(state = hazeState) {
        inputScale = HazeInputScale.Fixed(0.67f)
        clipToAreasBounds = false
        expandLayerBounds = true
        drawContentBehind = true
        forceInvalidateOnPreDraw = false
        blurEffect {
            backgroundColor = Color.Transparent
            colorEffects = if (tintAlpha > 0f) {
                listOf(HazeColorEffect.tint(tintColor.copy(alpha = tintAlpha)))
            } else {
                emptyList()
            }
            blurRadius = radius
            noiseFactor = 0f
            progressive = HazeProgressive.verticalGradient(startIntensity = 1f, endIntensity = 0f)
        }
    })
}

/** A settings Activity owns its own source, including any transparent page's window backdrop. */
fun Modifier.settingsTopBlurSource(state: HazeState, windowBackdrop: Backdrop?): Modifier =
    hazeSource(state).then(
        windowBackdrop?.let {
            Modifier.drawBackdrop(
                backdrop = it,
                shape = { RectangleShape },
                effects = {},
                highlight = null,
                shadow = null,
            )
        } ?: Modifier
    )
