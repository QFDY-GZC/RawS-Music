package com.rawsmusic.core.ui.widget.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * A deliberately short and light edge fade for the immersive mini lyric viewport.
 * It softens clipped rows without turning the preview into a large masked panel.
 */
internal fun Modifier.miniLyricShortEdgeFade(
    top: Dp = 10.dp,
    bottom: Dp = 12.dp,
    minimumEdgeAlpha: Float = 0.52f
): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()
        if (size.height <= 0f) return@drawWithContent

        val topFraction = (top.toPx() / size.height).coerceIn(0f, 0.35f)
        val bottomFraction = (bottom.toPx() / size.height).coerceIn(0f, 0.35f)
        val edgeAlpha = minimumEdgeAlpha.coerceIn(0f, 1f)

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = edgeAlpha),
                    topFraction to Color.White,
                    (1f - bottomFraction).coerceAtLeast(topFraction) to Color.White,
                    1f to Color.White.copy(alpha = edgeAlpha)
                )
            ),
            blendMode = BlendMode.DstIn
        )
    }
