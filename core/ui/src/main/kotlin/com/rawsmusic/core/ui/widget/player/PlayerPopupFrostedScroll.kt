package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Background-only edge gradients; never mask, blur or change the opacity of menu content. */
internal fun Modifier.popupFrostedScroll(state: ScrollState): Modifier = composed {
    val density = LocalDensity.current
    val edgePx = with(density) { 24.dp.toPx() }
    val edgeColor = MiuixTheme.colorScheme.background
    this.drawWithCache {
            val stops = Array(65) { i ->
                val t = i / 64f
                t to edgeColor.copy(alpha = 0.16f * popupEdgeBlurWeight(t * size.height,
                    size.height, edgePx, true, true))
            }
            val gradient = Brush.verticalGradient(*stops)
            onDrawBehind { drawRect(gradient) }
        }
        .verticalScroll(state)
}
