package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import kotlin.math.abs

internal const val IMMERSIVE_CLEAR_ARTWORK_FRACTION = 0.41f
internal val IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION = 32.dp

/**
 * Immersive artwork/backdrop layer.
 *
 * Queue fullscreen owns a separate visual route: the clear artwork lifts and fades out while the
 * queue expands over the whole page. Keeping that motion here prevents queue state from leaking
 * into the rest of the immersive player layout.
 */
@Composable
internal fun ImmersiveBackdrop(
    coverPath: String?,
    pageProgress: Float = 1f,
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    clearArtworkVisible: Boolean = true
) {
    val playerProgress = (1f - abs(pageProgress - 1f)).coerceIn(0f, 1f)
    val density = LocalDensity.current
    val clearArtworkPresence by animateFloatAsState(
        targetValue = if (clearArtworkVisible) 1f else 0f,
        animationSpec = tween(
            durationMillis = 520,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        ),
        label = "immersive-clear-artwork-presence"
    )
    val liftPx = with(density) { 14.dp.toPx() }

    BoxWithConstraints(modifier = Modifier.fillMaxSize().clipToBounds()) {
        StandardPlayerBackdrop(
            coverPath = coverPath,
            accent = Color.Transparent,
            artworkTransitionState = artworkTransitionState,
            modifier = Modifier.fillMaxSize()
        )

        val clearArtworkLayers = artworkTransitionState?.backgroundLayers().orEmpty()
        val hasClearArtwork = clearArtworkLayers.isNotEmpty() || !coverPath.isNullOrBlank()
        if (hasClearArtwork) {
            val splitY = maxHeight * IMMERSIVE_CLEAR_ARTWORK_FRACTION
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(splitY + IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION)
                    .align(Alignment.TopCenter)
                    .clipToBounds()
                    .graphicsLayer {
                        val hiddenFraction = 1f - clearArtworkPresence
                        alpha = playerProgress * clearArtworkPresence
                        scaleX = 1f + hiddenFraction * 0.045f
                        scaleY = 1f + hiddenFraction * 0.045f
                        translationY = -liftPx * hiddenFraction
                        transformOrigin = TransformOrigin(0.5f, 0.32f)
                    }
            ) {
                val fadeHeightPx = with(density) { 118.dp.toPx() }
                if (clearArtworkLayers.isEmpty()) {
                    ImmersiveClearArtworkLayer(
                        coverKey = coverPath.orEmpty(),
                        alpha = 1f,
                        fadeHeightPx = fadeHeightPx
                    )
                } else {
                    clearArtworkLayers.forEach { layer ->
                        androidx.compose.runtime.key(layer.token) {
                            ImmersiveClearArtworkLayer(
                                coverKey = layer.key,
                                alpha = layer.alpha,
                                fadeHeightPx = fadeHeightPx
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ImmersiveClearArtworkLayer(
    coverKey: String,
    alpha: Float,
    fadeHeightPx: Float
) {
    if (coverKey.isBlank() || alpha <= 0f) return
    BitmapImage(
        key = coverKey,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { this.alpha = alpha.coerceIn(0f, 1f) }
            .bottomEdgeTransparent(fadeHeightPx),
        contentScale = ContentScale.Crop,
        targetWidth = 1080,
        targetHeight = 1080,
        priority = BitmapRequest.Priority.LOADING_WIDGET,
        surface = ArtworkSurface.Playback,
        fadeInMillis = 0,
        holdPreviousOnKeyChange = false,
        fadeOnBitmapChange = false
    )
}

private fun Modifier.bottomEdgeTransparent(widthPx: Float): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()
        if (widthPx <= 0f) return@drawWithContent

        drawRect(
            brush = Brush.verticalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Transparent,
                    1.00f to Color.White
                ),
                startY = size.height - widthPx,
                endY = size.height
            ),
            topLeft = Offset(0f, size.height - widthPx),
            size = Size(size.width, widthPx),
            blendMode = BlendMode.DstOut
        )
    }
