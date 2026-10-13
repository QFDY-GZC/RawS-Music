package com.rawsmusic.core.ui.widget

import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.player.IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION
import com.rawsmusic.core.ui.widget.player.IMMERSIVE_CLEAR_ARTWORK_FRACTION
import kotlin.math.roundToInt

/**
 * Immersive uses the exact same physical MiniPlayer -> fullscreen surface as ordinary PLAYER.
 * The foreground raster is borrowed from the current artwork wrapper, never re-decoded solely
 * for a transition. The target is the actual immersive clear-artwork lane (not a fake square).
 *
 * All per-frame updates happen in draw/layer phases. In particular, measured root bounds and
 * frozen endpoints must not follow the animated artwork's boundsInRoot on every frame.
 */
@Composable
internal fun ImmersiveMainExpansionHost(
    coverPath: String?,
    artworkTransitionState: PlaybackArtworkTransitionState,
    mainTransition: Boolean,
    expansionState: State<Float>?,
    sourceCoverTarget: CoverTransitionTarget?,
    miniPlayerBoundsInRoot: Rect?,
    miniPlayerExpandedHeightDp: Float,
    miniPlayerCornerRadiusDp: Float,
    onSheetGeometryChanged: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable (handoffOwnsArtwork: Boolean, collapsedSurfaceTop: Float) -> Unit,
) {
    val density = LocalDensity.current
    var rootInWindow by remember { mutableStateOf<Rect?>(null) }
    BoxWithConstraints(
        modifier = modifier.fillMaxSize().onGloballyPositioned { coordinates ->
            rootInWindow = coordinates.boundsInRoot()
        },
    ) {
        val viewportWidth = constraints.maxWidth.toFloat().coerceAtLeast(1f)
        val viewportHeight = constraints.maxHeight.toFloat().coerceAtLeast(1f)
        val miniHeight = with(density) { miniPlayerExpandedHeightDp.dp.toPx() }
        val rootBounds = rootInWindow
        val realMiniBounds = miniPlayerBoundsInRoot?.takeIf {
            rootBounds != null && it.width > 0f && it.height > 0f
        }?.let { rect ->
            RectF(
                rect.left - rootBounds!!.left,
                rect.top - rootBounds.top,
                rect.right - rootBounds.left,
                rect.bottom - rootBounds.top,
            )
        }
        // Only a cold first frame should use the fallback; the controller deliberately waits for
        // the PLAYER tree to place before starting a programmatic MAIN -> PLAYER transition.
        val fallbackTop = (viewportHeight - miniHeight - with(density) { 22.dp.toPx() })
            .coerceAtLeast(0f)
        val measuredMini = realMiniBounds ?: RectF(
            0f, fallbackTop, viewportWidth,
            (fallbackTop + miniHeight).coerceAtMost(viewportHeight),
        )
        val surfaceSource = PlayerSurfaceHandoffBounds(
            left = measuredMini.left.coerceIn(0f, viewportWidth),
            top = measuredMini.top.coerceIn(0f, viewportHeight),
            right = measuredMini.right.coerceIn(0f, viewportWidth),
            bottom = measuredMini.bottom.coerceIn(0f, viewportHeight),
            cornerRadius = with(density) { miniPlayerCornerRadiusDp.dp.toPx() }
                .coerceAtMost(measuredMini.height() * 0.5f),
        )
        LaunchedEffect(surfaceSource.top, viewportWidth) {
            if (surfaceSource.top > 0f) {
                onSheetGeometryChanged(surfaceSource.top, viewportWidth)
            }
        }
        val sourceArtwork = sourceCoverTarget?.takeIf {
            it.source == CoverTransitionTarget.Source.MiniPlayer &&
                it.bounds.width() > 0f && it.bounds.height() > 0f &&
                rootBounds != null &&
                (coverPath.isNullOrBlank() || it.coverKey == coverPath)
        }?.bounds?.let {
            PlayerArtworkHandoffRect(
                it.left - rootBounds!!.left,
                it.top - rootBounds.top,
                it.width(),
                it.height(),
            )
        }
        val frozenSource = remember(coverPath) { PlayerArtworkEndpointSnapshot() }
        frozenSource.update(sourceArtwork, motionActive = mainTransition)
        val startArtwork = frozenSource.rect
        val landscape = viewportWidth > viewportHeight
        val fadeExtensionPx = with(density) { IMMERSIVE_CLEAR_ARTWORK_FADE_EXTENSION.toPx() }
        val landscapeTopInsetPx = if (landscape) {
            with(density) { (rawStableStatusBarTopPadding() + 76.dp).toPx() }
        } else 0f
        val landscapeBottomInsetPx = if (landscape) {
            with(density) { (rawStableNavigationBottomPadding() + 76.dp).toPx() }
        } else 0f
        val destinationArtwork = resolveImmersiveClearArtworkHandoffRect(
            viewportWidth = viewportWidth,
            viewportHeight = viewportHeight,
            edgeExtensionPx = fadeExtensionPx,
            clearFraction = IMMERSIVE_CLEAR_ARTWORK_FRACTION,
            landscapeTopInsetPx = landscapeTopInsetPx,
            landscapeBottomInsetPx = landscapeBottomInsetPx,
        )
        val artworkRaster = remember(coverPath, mainTransition) {
            coverPath?.let { artworkTransitionState.foregroundArtworkBitmapForKey(it) }
                ?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
        }
        val actorActive = mainTransition && startArtwork != null &&
            artworkRaster != null && !coverPath.isNullOrBlank()
        fun expansion(): Float =
            if (mainTransition) (expansionState?.value ?: 1f).coerceIn(0f, 1f) else 1f

        Box(
            modifier = Modifier
                .fillMaxSize()
                .playerMainExpansionSurfaceClip(
                    active = mainTransition,
                    collapsed = surfaceSource,
                    expansion = ::expansion,
                    expansionState = expansionState,
                ),
        ) {
            content(actorActive, surfaceSource.top)
        }
        if (actorActive) {
            val source = requireNotNull(startArtwork)
            val bitmap = requireNotNull(artworkRaster).asImageBitmap()
            val sourceRadius = sourceCoverTarget?.radiusDp ?: miniPlayerCornerRadiusDp
            Box(
                modifier = Modifier
                    .zIndex(2f)
                    .offset {
                        IntOffset(
                            destinationArtwork.left.roundToInt(),
                            destinationArtwork.top.roundToInt(),
                        )
                    }
                    .size(
                        width = with(density) { destinationArtwork.width.toDp() },
                        height = with(density) { destinationArtwork.height.toDp() },
                    )
                    .graphicsLayer {
                        val t = expansion()
                        val rect = resolvePlayerArtworkHandoffRect(
                            sourceLeft = source.left,
                            sourceTop = source.top,
                            sourceWidth = source.width,
                            sourceHeight = source.height,
                            targetLeft = destinationArtwork.left,
                            targetTop = destinationArtwork.top,
                            targetWidth = destinationArtwork.width,
                            targetHeight = destinationArtwork.height,
                            fraction = t,
                        )
                        val sx = rect.width / destinationArtwork.width.coerceAtLeast(1f)
                        val sy = rect.height / destinationArtwork.height.coerceAtLeast(1f)
                        translationX = rect.left - destinationArtwork.left
                        translationY = rect.top - destinationArtwork.top
                        scaleX = sx
                        scaleY = sy
                        transformOrigin = TransformOrigin(0f, 0f)
                        alpha = resolvePlayerSharedArtworkAlpha(t)
                        clip = true
                        shape = RoundedCornerShape(
                            (sourceRadius * (1f - t) / maxOf(sx, sy).coerceAtLeast(0.001f)).dp
                        )
                    },
            ) {
                Canvas(
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                    },
                ) {
                    // Adjust the source crop continuously as the visible rectangle changes from
                    // a compact (often square) cover into the wide immersive clear-artwork lane.
                    // Drawing an image cropped for the destination for the entire motion would
                    // make the thumbnail visibly change its contents at the first frame.
                    val t = expansion()
                    val rect = resolvePlayerArtworkHandoffRect(
                        sourceLeft = source.left,
                        sourceTop = source.top,
                        sourceWidth = source.width,
                        sourceHeight = source.height,
                        targetLeft = destinationArtwork.left,
                        targetTop = destinationArtwork.top,
                        targetWidth = destinationArtwork.width,
                        targetHeight = destinationArtwork.height,
                        fraction = t,
                    )
                    val aspect = rect.width / rect.height.coerceAtLeast(1f)
                    val bitmapAspect = bitmap.width.toFloat() / bitmap.height.coerceAtLeast(1)
                    val cropWidth = if (bitmapAspect > aspect) {
                        (bitmap.height * aspect).roundToInt().coerceIn(1, bitmap.width)
                    } else bitmap.width
                    val cropHeight = if (bitmapAspect <= aspect) {
                        (bitmap.width / aspect.coerceAtLeast(0.001f)).roundToInt()
                            .coerceIn(1, bitmap.height)
                    } else bitmap.height
                    drawImage(
                        image = bitmap,
                        srcOffset = IntOffset(
                            (bitmap.width - cropWidth) / 2,
                            (bitmap.height - cropHeight) / 2,
                        ),
                        srcSize = IntSize(cropWidth, cropHeight),
                        dstSize = IntSize(
                            size.width.roundToInt().coerceAtLeast(1),
                            size.height.roundToInt().coerceAtLeast(1),
                        ),
                        filterQuality = FilterQuality.Medium,
                    )
                    // The immersive endpoint has a feathered artwork boundary. Build that feather
                    // only in the final part of the morph; the MiniPlayer endpoint stays opaque.
                    val edgeAmount = ((t - 0.72f) / 0.28f).coerceIn(0f, 1f)
                    if (edgeAmount > 0f) {
                        val fadeEnd = if (landscape) size.width else size.height
                        val fadeDepth = with(density) {
                            (if (landscape) 96.dp else 118.dp).toPx()
                        }.coerceAtMost(fadeEnd)
                        if (!landscape) {
                            drawRect(
                                brush = Brush.verticalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = edgeAmount)),
                                    startY = size.height - fadeDepth,
                                    endY = size.height,
                                ),
                                topLeft = Offset(0f, size.height - fadeDepth),
                                size = Size(size.width, fadeDepth),
                                blendMode = BlendMode.DstOut,
                            )
                            val side = with(density) { 11.dp.toPx() }
                                .coerceAtMost(size.width * 0.12f)
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.White.copy(alpha = edgeAmount), Color.Transparent),
                                    startX = 0f, endX = side,
                                ),
                                size = Size(side, size.height),
                                blendMode = BlendMode.DstOut,
                            )
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = edgeAmount)),
                                    startX = size.width - side, endX = size.width,
                                ),
                                topLeft = Offset(size.width - side, 0f),
                                size = Size(side, size.height),
                                blendMode = BlendMode.DstOut,
                            )
                        } else {
                            drawRect(
                                brush = Brush.horizontalGradient(
                                    listOf(Color.Transparent, Color.White.copy(alpha = edgeAmount)),
                                    startX = size.width - fadeDepth,
                                    endX = size.width,
                                ),
                                blendMode = BlendMode.DstOut,
                            )
                        }
                        val top = with(density) { 7.dp.toPx() }
                            .coerceAtMost(size.height)
                        drawRect(
                            brush = Brush.verticalGradient(
                                listOf(Color.White.copy(alpha = edgeAmount), Color.Transparent),
                                startY = 0f, endY = top,
                            ),
                            size = Size(size.width, top),
                            blendMode = BlendMode.DstOut,
                        )
                    }
                }
            }
        }
    }
}
