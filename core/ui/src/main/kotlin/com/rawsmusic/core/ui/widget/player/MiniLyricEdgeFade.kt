package com.rawsmusic.core.ui.widget.player

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Keeps the mini-lyric slot itself fixed while giving the lyric layout a little vertical
 * overscan. Rows can therefore enter/leave through a feather instead of being hard-clipped at
 * the slot boundary. The parent still owns the physical title-to-progress bounds.
 */
internal fun Modifier.miniLyricVerticalOverscan(
    extra: Dp = 18.dp,
): Modifier = layout { measurable, constraints ->
    val extraPx = extra.roundToPx().coerceAtLeast(0)
    if (extraPx == 0 || !constraints.hasBoundedHeight) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {
            placeable.placeRelative(0, 0)
        }
    } else {
        val outerHeight = constraints.maxHeight
        val expandedHeight = (outerHeight.toLong() + extraPx.toLong() * 2L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val expandedConstraints = constraints.copy(
            minHeight = expandedHeight,
            maxHeight = expandedHeight,
        )
        val placeable = measurable.measure(expandedConstraints)
        val outerWidth = placeable.width.coerceIn(constraints.minWidth, constraints.maxWidth)

        layout(outerWidth, outerHeight) {
            placeable.placeRelative(0, -extraPx)
        }
    }
}

/**
 * A soft vertical alpha feather for the mini-lyric viewport. Unlike the old short edge tint, the
 * edge now approaches transparency so the extra overscan rows dissolve naturally. The fade is
 * intentionally compact and the modifier never changes layout size.
 */
internal fun Modifier.miniLyricShortEdgeFade(
    top: Dp = 18.dp,
    bottom: Dp = 20.dp,
    minimumEdgeAlpha: Float = 0.06f,
): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithCache {
        if (size.height <= 0f) {
            onDrawWithContent { drawContent() }
        } else {
            val topFraction = (top.toPx() / size.height).coerceIn(0f, 0.35f)
            val bottomFraction = (bottom.toPx() / size.height).coerceIn(0f, 0.35f)
            val edgeAlpha = minimumEdgeAlpha.coerceIn(0f, 1f)
            val bottomOpaqueStop = (1f - bottomFraction).coerceAtLeast(topFraction)
            val mask = Brush.verticalGradient(
                colorStops = arrayOf(
                    0f to Color.White.copy(alpha = edgeAlpha),
                    topFraction to Color.White,
                    bottomOpaqueStop to Color.White,
                    1f to Color.White.copy(alpha = edgeAlpha),
                )
            )

            onDrawWithContent {
                drawContent()
                drawRect(brush = mask, blendMode = BlendMode.DstIn)
            }
        }
    }
