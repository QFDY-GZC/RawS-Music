package com.rawsmusic.core.ui.widget.player

import android.graphics.Bitmap
import android.graphics.Camera
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkDisplayResolver
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkHandle
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.decodeDefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.shouldShowDefaultAlbumArtwork
import kotlin.math.abs

private const val FullscreenReflectionHeightFraction = 0.30f
private const val FullscreenReflectionAlpha = 0.28f

private data class FullscreenCanvasLane(
    val logicalOffset: Int,
    val bitmap: Bitmap?,
    val transform: FullscreenArtworkLaneTransform,
)

/**
 * Single-pass full-cover renderer.
 *
 * It deliberately follows the existing Home carousel's native Canvas path instead of composing an
 * eleven-item LazyRow. Eleven logical lanes stay resident, while the geometry profile exposes only
 * the central nine at rest and uses -5/+5 as transition buffers.
 */
@Composable
internal fun FullscreenArtworkCarouselCanvas(
    songs: List<AudioFile>,
    centerIndex: Int,
    progress: Float,
    hideCenterLane: Boolean,
    sideLaneAlpha: Float,
    sceneRevealProgress: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val laneSongs = (-FULLSCREEN_CAROUSEL_LANE_RADIUS..FULLSCREEN_CAROUSEL_LANE_RADIUS).map { logicalOffset ->
        if (songs.isEmpty() || (songs.size == 1 && logicalOffset != 0)) {
            null
        } else {
            songs[wrapFullscreenCarouselIndex(centerIndex + logicalOffset, songs.size)]
        }
    }
    val laneBitmaps = laneSongs.mapIndexed { laneIndex, song ->
        val logicalOffset = laneIndex - FULLSCREEN_CAROUSEL_LANE_RADIUS
        val artworkKey = song?.resolvePlaybackArtworkKey(null).orEmpty()
        val identityBase = song?.let {
            "${it.path}|${it.cueOffsetMs}|${it.cueTrackIndex}"
        } ?: "fullscreen-empty-$laneIndex"
        val virtualQueueIndex = centerIndex + logicalOffset
        val holderIdentity = if (songs.size < FULLSCREEN_CAROUSEL_LANE_COUNT) {
            "$identityBase|virtual=$virtualQueueIndex"
        } else {
            identityBase
        }
        key(holderIdentity) {
            rememberFullscreenCarouselBitmap(
                key = artworkKey,
                targetSide = fullscreenCarouselDecodeSide(logicalOffset),
            )
        }
    }

    val density = LocalDensity.current.density
    val camera = remember { Camera() }
    val matrix = remember { Matrix() }
    val coverPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
    val reflectionPaint = remember {
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    }
    val gradientPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val layerPaint = remember { Paint(Paint.ANTI_ALIAS_FLAG) }
    val sourceRect = remember { Rect() }
    val reflectionSourceRect = remember { Rect() }
    val coverRect = remember { RectF() }
    val reflectionRect = remember { RectF() }
    val reflectionDestination = remember { RectF() }
    val clipPath = remember { Path() }

    Canvas(modifier = modifier) {
        val metrics = resolveFullscreenArtworkCarouselMetrics(size.width, size.height)
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val reflectionGap = 4f * density
        val cornerRadius = 22f * density
        val lanes = buildList {
            for (index in laneSongs.indices) {
                if (laneSongs[index] == null) continue
                val logicalOffset = index - FULLSCREEN_CAROUSEL_LANE_RADIUS
                val railOffset = logicalOffset - progress
                if (abs(railOffset) > FULLSCREEN_CAROUSEL_LANE_RADIUS.toFloat()) continue
                val baseTransform = resolveFullscreenArtworkLaneTransform(railOffset, metrics)
                val isCenter = abs(railOffset) < 0.001f
                val transform = if (isCenter) {
                    baseTransform
                } else {
                    resolveFullscreenArtworkSceneLaneTransform(
                        transform = baseTransform,
                        revealProgress = sceneRevealProgress,
                    )
                }
                val resolvedAlpha = when {
                    hideCenterLane && isCenter -> 0f
                    !isCenter -> transform.alpha * sideLaneAlpha.coerceIn(0f, 1f)
                    else -> transform.alpha
                }
                if (resolvedAlpha <= 0.001f) continue
                add(
                    FullscreenCanvasLane(
                        logicalOffset = logicalOffset,
                        bitmap = laneBitmaps[index],
                        transform = transform.copy(alpha = resolvedAlpha),
                    )
                )
            }
        }.sortedBy { it.transform.zIndex }

        drawIntoCanvas {
            lanes.forEach { lane ->
                drawFullscreenCanvasLane(
                    canvas = nativeCanvas,
                    lane = lane,
                    metrics = metrics,
                    reflectionGap = reflectionGap,
                    cornerRadius = cornerRadius,
                    cameraDistance = 36f * density,
                    camera = camera,
                    matrix = matrix,
                    coverPaint = coverPaint,
                    reflectionPaint = reflectionPaint,
                    gradientPaint = gradientPaint,
                    layerPaint = layerPaint,
                    sourceRect = sourceRect,
                    reflectionSourceRect = reflectionSourceRect,
                    coverRect = coverRect,
                    reflectionRect = reflectionRect,
                    reflectionDestination = reflectionDestination,
                    clipPath = clipPath,
                )
            }
        }
    }
}

@Composable
private fun rememberFullscreenCarouselBitmap(
    key: String,
    targetSide: Int,
): Bitmap? {
    val context = LocalContext.current
    remember(context) {
        BitmapProvider.init(context)
        true
    }

    val initialHandle = remember(key, targetSide) {
        ArtworkDisplayResolver.acquireBest(
            key = key,
            targetWidth = targetSide,
            targetHeight = targetSide,
            surface = ArtworkSurface.Fullscreen,
            allowHiRes = targetSide >= 1024,
        )?.handle
    }
    var handle by remember(key, targetSide) { mutableStateOf<ArtworkHandle?>(initialHandle) }
    var fallback by remember(key, targetSide) {
        mutableStateOf(
            if (shouldShowDefaultAlbumArtwork(key, targetSide, targetSide)) {
                decodeDefaultAlbumArtwork(context.resources, minOf(targetSide, 1024))
            } else {
                null
            }
        )
    }

    DisposableEffect(key, targetSide, context) {
        var active = true
        val lowSide = minOf(targetSide, 512)

        fun acceptLoaded(loaded: Bitmap?, requestedSide: Int) {
            if (!active) return
            val next = BitmapProvider.acquireLoaded(
                key = key,
                bitmap = loaded,
                targetWidth = requestedSide,
                targetHeight = requestedSide,
                surface = ArtworkSurface.Fullscreen,
            )
            if (next?.isValid == true) {
                val currentSide = handle
                    ?.takeIf { it.isValid }
                    ?.bitmap
                    ?.let { maxOf(it.width, it.height) }
                    ?: 0
                val nextSide = maxOf(next.bitmap.width, next.bitmap.height)
                if (nextSide >= currentSide) {
                    handle?.release()
                    handle = next
                    fallback?.takeIf { !it.isRecycled }?.recycle()
                    fallback = null
                } else {
                    next.release()
                }
            } else {
                next?.release()
            }
        }

        val lowRequest = if (key.isBlank()) {
            null
        } else {
            BitmapProvider.loadThumbnail(
                key = key,
                targetWidth = lowSide,
                targetHeight = lowSide,
                priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                surface = ArtworkSurface.Fullscreen,
            ) { loaded -> acceptLoaded(loaded, lowSide) }
        }
        val highRequest = if (key.isBlank() || targetSide <= lowSide) {
            null
        } else {
            BitmapProvider.load(
                key = key,
                targetWidth = targetSide,
                targetHeight = targetSide,
                priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                surface = ArtworkSurface.Fullscreen,
            ) { loaded -> acceptLoaded(loaded, targetSide) }
        }

        onDispose {
            active = false
            lowRequest?.let { BitmapProvider.cancel(it, keepDecoding = true) }
            highRequest?.let { BitmapProvider.cancel(it, keepDecoding = true) }
            handle?.release()
            handle = null
            fallback?.takeIf { !it.isRecycled }?.recycle()
            fallback = null
        }
    }

    return handle?.takeIf { it.isValid }?.bitmap ?: fallback
}

private fun drawFullscreenCanvasLane(
    canvas: android.graphics.Canvas,
    lane: FullscreenCanvasLane,
    metrics: FullscreenArtworkCarouselMetrics,
    reflectionGap: Float,
    cornerRadius: Float,
    cameraDistance: Float,
    camera: Camera,
    matrix: Matrix,
    coverPaint: Paint,
    reflectionPaint: Paint,
    gradientPaint: Paint,
    layerPaint: Paint,
    sourceRect: Rect,
    reflectionSourceRect: Rect,
    coverRect: RectF,
    reflectionRect: RectF,
    reflectionDestination: RectF,
    clipPath: Path,
) {
    val bitmap = lane.bitmap ?: return
    if (bitmap.isRecycled) return

    val transform = lane.transform
    val drawSide = metrics.coverSidePx * transform.scale
    val pivotX = drawSide * transform.pivotFractionX
    val scaledCornerRadius = cornerRadius * transform.scale.coerceAtLeast(0.60f)

    canvas.save()
    canvas.translate(metrics.centerXPx + transform.translationX, metrics.centerYPx)
    if (abs(transform.rotationY) > 0.01f) {
        camera.save()
        camera.setLocation(0f, 0f, -cameraDistance)
        camera.rotateY(transform.rotationY)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-pivotX, 0f)
        matrix.postTranslate(pivotX, 0f)
        canvas.concat(matrix)
    }

    coverRect.set(-drawSide * 0.5f, -drawSide * 0.5f, drawSide * 0.5f, drawSide * 0.5f)
    centerCropFullscreenSource(bitmap, drawSide, drawSide, sourceRect)
    canvas.save()
    clipPath.reset()
    clipPath.addRoundRect(coverRect, scaledCornerRadius, scaledCornerRadius, Path.Direction.CW)
    canvas.clipPath(clipPath)
    coverPaint.alpha = (transform.alpha.coerceIn(0f, 1f) * 255f).toInt()
    coverPaint.isFilterBitmap = true
    canvas.drawBitmap(bitmap, sourceRect, coverRect, coverPaint)
    canvas.restore()

    if (transform.alpha > 0.02f) {
        drawFullscreenReflection(
            canvas = canvas,
            bitmap = bitmap,
            drawSide = drawSide,
            reflectionGap = reflectionGap * transform.scale,
            cornerRadius = scaledCornerRadius,
            alpha = transform.alpha,
            reflectionPaint = reflectionPaint,
            gradientPaint = gradientPaint,
            layerPaint = layerPaint,
            reflectionSourceRect = reflectionSourceRect,
            reflectionRect = reflectionRect,
            reflectionDestination = reflectionDestination,
            clipPath = clipPath,
        )
    }
    canvas.restore()
}

private fun drawFullscreenReflection(
    canvas: android.graphics.Canvas,
    bitmap: Bitmap,
    drawSide: Float,
    reflectionGap: Float,
    cornerRadius: Float,
    alpha: Float,
    reflectionPaint: Paint,
    gradientPaint: Paint,
    layerPaint: Paint,
    reflectionSourceRect: Rect,
    reflectionRect: RectF,
    reflectionDestination: RectF,
    clipPath: Path,
) {
    val reflectionHeight = drawSide * FullscreenReflectionHeightFraction
    val top = drawSide * 0.5f + reflectionGap
    val bottom = top + reflectionHeight
    reflectionRect.set(-drawSide * 0.5f, top, drawSide * 0.5f, bottom)
    centerCropFullscreenSource(bitmap, drawSide, drawSide, reflectionSourceRect)
    val sourceSliceHeight =
        (reflectionSourceRect.height() * FullscreenReflectionHeightFraction)
            .toInt()
            .coerceIn(1, reflectionSourceRect.height())
    reflectionSourceRect.top = reflectionSourceRect.bottom - sourceSliceHeight

    canvas.save()
    clipPath.reset()
    clipPath.addRoundRect(reflectionRect, cornerRadius, cornerRadius, Path.Direction.CW)
    canvas.clipPath(clipPath)
    layerPaint.alpha = (FullscreenReflectionAlpha * alpha.coerceIn(0f, 1f) * 255f).toInt()
    val layer = canvas.saveLayer(reflectionRect, layerPaint)
    reflectionPaint.alpha = 255
    reflectionPaint.xfermode = null
    canvas.save()
    canvas.translate(0f, bottom)
    canvas.scale(1f, -1f)
    reflectionDestination.set(-drawSide * 0.5f, 0f, drawSide * 0.5f, reflectionHeight)
    canvas.drawBitmap(bitmap, reflectionSourceRect, reflectionDestination, reflectionPaint)
    canvas.restore()

    gradientPaint.shader = LinearGradient(
        0f,
        top,
        0f,
        bottom,
        intArrayOf(0xFFFFFFFF.toInt(), 0xB8FFFFFF.toInt(), 0x24FFFFFF, 0x00FFFFFF),
        floatArrayOf(0f, 0.38f, 0.72f, 1f),
        Shader.TileMode.CLAMP,
    )
    gradientPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    gradientPaint.alpha = 255
    canvas.drawRect(reflectionRect, gradientPaint)
    gradientPaint.xfermode = null
    gradientPaint.shader = null
    canvas.restoreToCount(layer)
    canvas.restore()
}

private fun centerCropFullscreenSource(
    bitmap: Bitmap,
    destinationWidth: Float,
    destinationHeight: Float,
    output: Rect,
) {
    val bitmapWidth = bitmap.width
    val bitmapHeight = bitmap.height
    if (bitmapWidth <= 0 || bitmapHeight <= 0) {
        output.set(0, 0, bitmapWidth, bitmapHeight)
        return
    }
    val destinationRatio = destinationWidth / destinationHeight
    val sourceRatio = bitmapWidth.toFloat() / bitmapHeight
    if (sourceRatio > destinationRatio) {
        val cropWidth = (bitmapHeight * destinationRatio).toInt().coerceAtMost(bitmapWidth)
        val x = (bitmapWidth - cropWidth) / 2
        output.set(x, 0, x + cropWidth, bitmapHeight)
    } else {
        val cropHeight = (bitmapWidth / destinationRatio).toInt().coerceAtMost(bitmapHeight)
        val y = (bitmapHeight - cropHeight) / 2
        output.set(0, y, bitmapWidth, y + cropHeight)
    }
}
