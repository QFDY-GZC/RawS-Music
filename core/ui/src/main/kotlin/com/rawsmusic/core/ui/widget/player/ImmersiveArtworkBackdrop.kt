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
    videoCoverUri: String? = null,
    pageProgress: Float = 1f,
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    clearArtworkVisible: Boolean = true,
    motionEnabled: Boolean = true,
) {
    val hasVideoCover = !videoCoverUri.isNullOrBlank()
    val transitionMotionActive = artworkTransitionState?.isGestureActive == true ||
        artworkTransitionState?.isSettling == true
    val effectiveMotionEnabled = motionEnabled || transitionMotionActive
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
            motionEnabled = effectiveMotionEnabled,
            modifier = Modifier.fillMaxSize()
        )

        val clearArtworkLayers = artworkTransitionState?.backgroundLayers().orEmpty()
        val hasClearArtwork = clearArtworkLayers.isNotEmpty() ||
            !coverPath.isNullOrBlank() ||
            !videoCoverUri.isNullOrBlank()
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
                val sideFadePx = with(density) { 11.dp.toPx() }
                val topFadePx = with(density) { 7.dp.toPx() }
                // Apply the feather to the composed artwork once. Applying it to every
                // cross-fading layer made the bottom edge alpha multiply differently during a
                // song switch, which produced a visible jump below the clear artwork.
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .immersiveArtworkEdgeFeather(
                            bottomFadePx = fadeHeightPx,
                            sideFadePx = sideFadePx,
                            topFadePx = topFadePx
                        )
                ) {
                    // A selected video owns the clear-artwork lane completely. Keeping bitmap
                    // transition layers underneath lets them show through the video's faded edge.
                    if (!hasVideoCover) {
                        if (clearArtworkLayers.isEmpty()) {
                            ImmersiveClearArtworkLayer(
                                coverKey = coverPath.orEmpty(),
                            )
                        } else {
                            clearArtworkLayers.forEach { layer ->
                                androidx.compose.runtime.key(layer.token) {
                                    ImmersiveClearArtworkLayer(
                                        coverKey = layer.key,
                                        artworkTransitionState = artworkTransitionState,
                                        backgroundRole = layer.role,
                                    )
                                }
                            }
                        }
                    }
                    if (hasVideoCover) {
                        FfmpegVideoCover(
                            uri = videoCoverUri,
                            active = effectiveMotionEnabled && clearArtworkVisible && playerProgress > 0f,
                            cornerRadiusDp = 0f,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ImmersiveClearArtworkLayer(
    coverKey: String,
    artworkTransitionState: PlaybackArtworkTransitionState? = null,
    backgroundRole: com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkBackgroundRole? = null,
) {
    if (coverKey.isBlank()) return
    BitmapImage(
        key = coverKey,
        contentDescription = null,
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                alpha = if (artworkTransitionState != null && backgroundRole != null) {
                    artworkTransitionState.backgroundLayerAlpha(backgroundRole).coerceIn(0f, 1f)
                } else {
                    1f
                }
            },
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

private fun Modifier.immersiveArtworkEdgeFeather(
    bottomFadePx: Float,
    sideFadePx: Float,
    topFadePx: Float
): Modifier = this
    .graphicsLayer {
        compositingStrategy = CompositingStrategy.Offscreen
    }
    .drawWithContent {
        drawContent()

        if (topFadePx > 0f) {
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    startY = 0f,
                    endY = topFadePx
                ),
                size = Size(size.width, topFadePx.coerceAtMost(size.height)),
                blendMode = BlendMode.DstOut
            )
        }

        if (sideFadePx > 0f) {
            val width = sideFadePx.coerceAtMost(size.width * 0.12f)
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    startX = 0f,
                    endX = width
                ),
                size = Size(width, size.height),
                blendMode = BlendMode.DstOut
            )
            drawRect(
                brush = Brush.horizontalGradient(
                    colors = listOf(Color.Transparent, Color.White),
                    startX = size.width - width,
                    endX = size.width
                ),
                topLeft = Offset(size.width - width, 0f),
                size = Size(width, size.height),
                blendMode = BlendMode.DstOut
            )
        }

        if (bottomFadePx > 0f) {
            val height = bottomFadePx.coerceAtMost(size.height)
            drawRect(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, Color.White),
                    startY = size.height - height,
                    endY = size.height
                ),
                topLeft = Offset(0f, size.height - height),
                size = Size(size.width, height),
                blendMode = BlendMode.DstOut
            )
        }
    }
