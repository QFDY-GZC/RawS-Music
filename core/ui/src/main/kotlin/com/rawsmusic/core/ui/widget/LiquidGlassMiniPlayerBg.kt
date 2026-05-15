package com.rawsmusic.core.ui.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.Shadow

@Composable
fun LiquidGlassMiniPlayerBg() {
    val backdrop = rememberLayerBackdrop()
    Box(
        Modifier
            .fillMaxSize()
            .drawBackdrop(
                backdrop = backdrop,
                shape = { androidx.compose.foundation.shape.RoundedCornerShape(24.dp) },
                effects = {
                    vibrancy()
                    blur(6.dp.toPx())
                    lens(12.dp.toPx(), 24.dp.toPx())
                },
                highlight = { Highlight.Plain },
                shadow = { Shadow.Default },
                onDrawSurface = {
                    drawRect(Color.White.copy(alpha = 0.6f))
                }
            )
    )
}
