package com.rawsmusic.core.ui.scene.pages

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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect as ComposeRect
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkDisplayResolver
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkHandle
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.decodeDefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.bitmaps.shouldShowDefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.player.HOME_HORIZONTAL_CAROUSEL_CORNER_RADIUS_DP
import kotlin.math.abs

private const val CanvasLaneRadius = 3
private const val CanvasDecodeSizePx = 768
private const val CanvasReflectionHeightFraction = 0.28f
private const val CanvasReflectionAlpha = 0.24f
private const val CanvasNearTranslationFraction = 0.49f
private const val CanvasOuterTranslationFraction = 0.60f
// The third logical rail is a transition buffer. Keep it beyond the viewport at rest so only
// the intended five covers are visible; it moves in naturally as progress approaches one lane.
private const val CanvasEdgeTranslationFraction = 1.05f
private const val CanvasNearSideScale = 0.85f
private const val CanvasOuterSideScale = 0.90f
// Keep the first side rail wide enough to retain real on-screen detail. A fixed 75-degree turn
// compresses the first side cover to roughly one quarter of its already scaled width, so increasing
// decode size cannot improve it. Outer rails may turn a little further because they are secondary.
private const val CanvasNearCameraRotation = 58f
private const val CanvasOuterCameraRotation = 64f
private const val CanvasEdgeCameraRotation = 68f

private data class CanvasLane(
    val logicalOffset: Int,
    val railOffset: Float,
    val bitmap: Bitmap?,
    val translationX: Float,
    val sizeScale: Float,
    val rotationY: Float,
    val pivotFractionX: Float,
    val zIndex: Float
)

/**
 * Compose-owned single-pass renderer matching Mica's Canvas pipeline.
 *
 * Keeping every lane on one native Canvas avoids the per-Composable offscreen texture that made
 * steeply rotated side covers blurry. Seven logical lanes remain available while canvas clipping
 * naturally leaves the central five visible at rest.
 */
@Composable
internal fun HomeArtworkCarouselCanvas(
    songs: List<AudioFile>,
    centerIndex: Int,
    progress: Float,
    hideCenterLane: Boolean = false,
    centerReflectionAlpha: Float = 1f,
    centerReflectionArtworkKey: String = "",
    centerArtworkBoundsHandle: HomeCanvasArtworkBoundsHandle? = null,
    onCenterArtworkBoundsChanged: (ComposeRect) -> Unit = {},
    modifier: Modifier = Modifier
) {
    DisposableEffect(centerArtworkBoundsHandle) {
        onDispose { centerArtworkBoundsHandle?.clear() }
    }

    val laneSongs = (-CanvasLaneRadius..CanvasLaneRadius).map { logicalOffset ->
        if (songs.isEmpty() || (songs.size == 1 && logicalOffset != 0)) {
            null
        } else {
            songs[wrapCanvasCarouselIndex(centerIndex + logicalOffset, songs.size)]
        }
    }
    LaunchedEffect(songs, centerIndex) {
        AppLogger.i(
            "HOME_CAROUSEL_TRACE",
            "artwork_bind center=$centerIndex lanes=" +
                laneSongs.mapIndexed { index, song ->
                    val offset = index - CanvasLaneRadius
                    "$offset:${song?.title}:${song?.path?.takeLast(36)}"
                }.joinToString("|")
        )
    }
    val laneBitmaps = laneSongs.mapIndexed { laneIndex, song ->
        val artworkKey = song?.resolvePlaybackArtworkKey(null).orEmpty()
        val playbackIdentityBase = song?.let {
            "${it.path}|${it.cueOffsetMs}|${it.cueTrackIndex}"
        } ?: "empty-lane-$laneIndex"
        val logicalOffset = laneIndex - CanvasLaneRadius
        // A virtual queue coordinate follows the same card while it moves from a side rail into
        // the center. Using the physical lane index here recreated every bitmap holder at commit
        // and produced the visible one-frame shake.
        val virtualQueueIndex = centerIndex + logicalOffset
        val playbackIdentity = if (songs.size < CanvasLaneRadius * 2 + 1) {
            "$playbackIdentityBase|virtual=$virtualQueueIndex"
        } else {
            playbackIdentityBase
        }
        key(playbackIdentity) {
            rememberHomeCarouselBitmap(artworkKey)
        }
    }
    val transitionCenterReflectionBitmap = if (
        hideCenterLane && centerReflectionAlpha > 0.001f && centerReflectionArtworkKey.isNotBlank()
    ) {
        key("home-return-reflection:$centerReflectionArtworkKey") {
            rememberHomeCarouselBitmap(centerReflectionArtworkKey)
        }
    } else {
        null
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

    Canvas(
        modifier = modifier.onGloballyPositioned { coordinates ->
            centerArtworkBoundsHandle?.update(coordinates)
            if (abs(progress) < 0.02f) {
                (centerArtworkBoundsHandle?.resolveInRoot()
                    ?: resolveHomeCanvasCenterArtworkBoundsInRoot(coordinates))?.let(
                    onCenterArtworkBoundsChanged
                )
            }
        }
    ) {
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val slotWidth = size.width
        val slotHeight = size.width
        val centerX = size.width * 0.5f
        val centerY = slotHeight * 0.5f
        val reflectionGap = 4f * density
        val cornerRadius = HOME_HORIZONTAL_CAROUSEL_CORNER_RADIUS_DP * density

        val lanes = buildList {
            for (index in laneSongs.indices) {
                val song = laneSongs[index] ?: continue
                val bitmap = laneBitmaps[index]
                val logicalOffset = index - CanvasLaneRadius
                val railOffset = logicalOffset - progress
                if (abs(railOffset) > CanvasLaneRadius.toFloat()) continue
                val hiddenCenter = hideCenterLane && abs(railOffset) < 0.001f
                // During the final return segment the shared overlay still owns the cover itself,
                // but the home Canvas may restore only its reflection underneath that moving lane.
                if (hiddenCenter && centerReflectionAlpha <= 0.001f) continue
                add(
                    buildCanvasLane(
                        logicalOffset = logicalOffset,
                        railOffset = railOffset,
                        bitmap = if (hiddenCenter) {
                            transitionCenterReflectionBitmap ?: bitmap
                        } else {
                            bitmap
                        },
                        slotWidth = slotWidth
                    )
                )
            }
        }.sortedBy { it.zIndex }

        drawIntoCanvas {
            lanes.forEach { lane ->
                drawCanvasLane(
                    canvas = nativeCanvas,
                    lane = lane,
                    centerX = centerX,
                    centerY = centerY,
                    slotWidth = slotWidth,
                    slotHeight = slotHeight,
                    reflectionGap = reflectionGap,
                    cornerRadius = cornerRadius,
                    cameraDistance = 18f * density,
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
                    drawCover = !(hideCenterLane && abs(lane.railOffset) < 0.001f),
                    reflectionAlpha = if (
                        hideCenterLane && abs(lane.railOffset) < 0.001f
                    ) {
                        centerReflectionAlpha.coerceIn(0f, 1f)
                    } else {
                        1f
                    },
                )
            }
        }
    }
}

@Composable
private fun rememberHomeCarouselBitmap(key: String): Bitmap? {
    val context = LocalContext.current
    remember(context) {
        BitmapProvider.init(context)
        true
    }

    val initialHandle = remember(key) {
        ArtworkDisplayResolver.acquireBest(
            key = key,
            targetWidth = CanvasDecodeSizePx,
            targetHeight = CanvasDecodeSizePx,
            surface = ArtworkSurface.Playback,
            allowHiRes = true
        )?.handle
    }
    var handle by remember(key) { mutableStateOf<ArtworkHandle?>(initialHandle) }
    var fallback by remember(key) {
        mutableStateOf(
            if (shouldShowDefaultAlbumArtwork(key, CanvasDecodeSizePx, CanvasDecodeSizePx)) {
                decodeDefaultAlbumArtwork(context.resources, 1024)
            } else {
                null
            }
        )
    }

    DisposableEffect(key, context) {
        var active = true
        var highRequestFinished = false

        fun acceptLoaded(
            loaded: Bitmap?,
            targetSize: Int,
            tier: String
        ) {
            if (!active) return
            val next = BitmapProvider.acquireLoaded(
                key = key,
                bitmap = loaded,
                targetWidth = targetSize,
                targetHeight = targetSize,
                surface = ArtworkSurface.Playback
            )
            if (next?.isValid == true) {
                val current = handle?.takeIf { it.isValid }
                val currentBitmap = current?.bitmap
                val currentSide = currentBitmap
                    ?.let { maxOf(it.width, it.height) }
                    ?: 0
                val nextSide = maxOf(next.bitmap.width, next.bitmap.height)
                val shouldReplace = current == null || nextSide > currentSide
                if (shouldReplace) {
                    val oldBitmapId = currentBitmap?.let { System.identityHashCode(it) } ?: 0
                    val nextBitmapId = System.identityHashCode(next.bitmap)
                    // Publish the new handle before releasing the previous one. A released handle
                    // becomes invalid immediately, so the opposite order can expose one empty
                    // Compose frame while a lane is being promoted to a higher-resolution bitmap.
                    handle = next
                    current?.release()
                    fallback?.takeIf { !it.isRecycled }?.recycle()
                    fallback = null
                    AppLogger.i(
                        "HOME_CAROUSEL_ART",
                        "bitmap_swap tier=$tier key=${key.takeLast(64)} " +
                            "old=${currentSide}px#$oldBitmapId new=${nextSide}px#$nextBitmapId"
                    )
                } else {
                    next.release()
                    AppLogger.i(
                        "HOME_CAROUSEL_ART",
                            "bitmap_keep tier=$tier key=${key.takeLast(64)} " +
                            "current=${currentSide}px#${currentBitmap?.let { System.identityHashCode(it) } ?: 0} " +
                            "candidate=${nextSide}px#${System.identityHashCode(next.bitmap)}"
                    )
                }
                return
            }
            next?.release()
            AppLogger.w(
                "HOME_CAROUSEL_ART",
                "load miss tier=$tier key=${key.takeLast(64)}"
            )
            if (
                highRequestFinished &&
                handle?.isValid != true &&
                fallback == null
            ) {
                fallback = decodeDefaultAlbumArtwork(context.resources, 1024)
                AppLogger.d(
                    "HOME_CAROUSEL_ART",
                    "using default key=${key.takeLast(64)}"
                )
            }
        }

        val highRequest = if (key.isBlank()) {
            null
        } else {
            BitmapProvider.load(
                key = key,
                targetWidth = CanvasDecodeSizePx,
                targetHeight = CanvasDecodeSizePx,
                priority = BitmapRequest.Priority.LOADING_NOTIFICATION_HIGH,
                surface = ArtworkSurface.Playback
            ) { loaded ->
                if (!active) return@load
                highRequestFinished = true
                acceptLoaded(loaded, CanvasDecodeSizePx, "high")
            }
        }

        onDispose {
            active = false
            highRequest?.let { BitmapProvider.cancel(it, keepDecoding = true) }
            handle?.release()
            handle = null
            fallback?.takeIf { !it.isRecycled }?.recycle()
            fallback = null
        }
    }

    return handle?.takeIf { it.isValid }?.bitmap ?: fallback
}


internal class HomeCanvasArtworkBoundsHandle {
    private var coordinates: LayoutCoordinates? = null

    internal fun update(next: LayoutCoordinates) {
        coordinates = next
    }

    internal fun clear() {
        coordinates = null
    }

    internal fun resolveInRoot(): ComposeRect? {
        val current = coordinates?.takeIf { it.isAttached } ?: return null
        return resolveHomeCanvasCenterArtworkBoundsInRoot(current)
    }
}

private fun resolveHomeCanvasCenterArtworkBoundsInRoot(
    coordinates: LayoutCoordinates,
): ComposeRect? {
    val local = resolveHomeCanvasCenterArtworkLocalBounds(
        canvasWidthPx = coordinates.size.width.toFloat(),
        canvasHeightPx = coordinates.size.height.toFloat(),
    ) ?: return null
    val corners = arrayOf(
        coordinates.localToRoot(Offset(local.leftPx, local.topPx)),
        coordinates.localToRoot(Offset(local.rightPx, local.topPx)),
        coordinates.localToRoot(Offset(local.rightPx, local.bottomPx)),
        coordinates.localToRoot(Offset(local.leftPx, local.bottomPx)),
    )
    val left = corners.minOf { it.x }
    val top = corners.minOf { it.y }
    val right = corners.maxOf { it.x }
    val bottom = corners.maxOf { it.y }
    return ComposeRect(left, top, right, bottom)
}

private fun buildCanvasLane(
    logicalOffset: Int,
    railOffset: Float,
    bitmap: Bitmap?,
    slotWidth: Float
): CanvasLane {
    val distance = abs(railOffset).coerceIn(0f, 3f)
    val sign = when {
        railOffset < 0f -> -1f
        railOffset > 0f -> 1f
        else -> 0f
    }
    val translationFraction = when {
        distance <= 1f -> CanvasNearTranslationFraction * distance
        distance <= 2f -> CanvasNearTranslationFraction +
            (CanvasOuterTranslationFraction - CanvasNearTranslationFraction) * (distance - 1f)
        else -> CanvasOuterTranslationFraction +
            (CanvasEdgeTranslationFraction - CanvasOuterTranslationFraction) * (distance - 2f)
    }
    val baseScale = when {
        distance <= 1f -> HOME_CANVAS_CENTER_SCALE + (0.52f - HOME_CANVAS_CENTER_SCALE) * distance
        else -> 0.52f + (0.44f - 0.52f) * (distance - 1f).coerceIn(0f, 1f)
    }
    val extraScale = when {
        distance < 0.08f -> 1f
        distance <= 1.05f -> {
            val t = ((distance - 0.08f) / (1.05f - 0.08f)).coerceIn(0f, 1f)
            1f + (CanvasNearSideScale - 1f) * t
        }
        else -> {
            val t = ((distance - 1.05f) / (2.05f - 1.05f)).coerceIn(0f, 1f)
            CanvasNearSideScale + (CanvasOuterSideScale - CanvasNearSideScale) * t
        }
    }
    val centerWeight = ((1.05f - distance) / 1.05f).coerceIn(0f, 1f)
    val edgePivotFraction = if (railOffset < 0f) 0.5f else -0.5f
    val pivotFractionX = edgePivotFraction * (1f - centerWeight)
    val turn = distance.coerceIn(0f, 1f)
    val easedTurn = turn * turn * (3f - 2f * turn)
    val rotationMagnitude = when {
        distance <= 1f -> CanvasNearCameraRotation * easedTurn
        distance <= 2f -> {
            val t = smoothCanvasStep(distance - 1f)
            CanvasNearCameraRotation +
                (CanvasOuterCameraRotation - CanvasNearCameraRotation) * t
        }
        else -> {
            val t = smoothCanvasStep(distance - 2f)
            CanvasOuterCameraRotation +
                (CanvasEdgeCameraRotation - CanvasOuterCameraRotation) * t
        }
    }
    val sizeScale = baseScale * extraScale

    return CanvasLane(
        logicalOffset = logicalOffset,
        railOffset = railOffset,
        bitmap = bitmap,
        translationX = sign * slotWidth * translationFraction,
        sizeScale = sizeScale,
        rotationY = sign * -rotationMagnitude,
        pivotFractionX = pivotFractionX,
        zIndex = 30f - distance * 10f
    )
}

private fun smoothCanvasStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

private fun drawCanvasLane(
    canvas: android.graphics.Canvas,
    lane: CanvasLane,
    centerX: Float,
    centerY: Float,
    slotWidth: Float,
    slotHeight: Float,
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
    drawCover: Boolean,
    reflectionAlpha: Float,
) {
    val bitmap = lane.bitmap ?: return
    if (bitmap.isRecycled) return

    val drawWidth = slotWidth * lane.sizeScale
    val drawHeight = slotHeight * lane.sizeScale
    val pivotX = drawWidth * lane.pivotFractionX
    val scaledCornerRadius = cornerRadius * lane.sizeScale.coerceAtLeast(HOME_CANVAS_MIN_CORNER_SCALE)

    canvas.save()
    canvas.translate(centerX + lane.translationX, centerY)
    if (abs(lane.rotationY) > 0.01f) {
        camera.save()
        camera.setLocation(0f, 0f, -cameraDistance)
        camera.rotateY(lane.rotationY)
        camera.getMatrix(matrix)
        camera.restore()
        matrix.preTranslate(-pivotX, 0f)
        matrix.postTranslate(pivotX, 0f)
        canvas.concat(matrix)
    }

    coverRect.set(
        -drawWidth * 0.5f,
        -drawHeight * 0.5f,
        drawWidth * 0.5f,
        drawHeight * 0.5f
    )
    if (drawCover) {
        centerCropSource(bitmap, drawWidth, drawHeight, sourceRect)
        canvas.save()
        clipPath.reset()
        clipPath.addRoundRect(
            coverRect,
            scaledCornerRadius,
            scaledCornerRadius,
            Path.Direction.CW
        )
        canvas.clipPath(clipPath)
        // The source is already decoded above the lane's final size. Side covers are minified and
        // perspective-mapped, so disabling filtering here makes every non-center lane look coarse or
        // smeared regardless of the requested source pixels.
        coverPaint.isFilterBitmap = true
        canvas.drawBitmap(bitmap, sourceRect, coverRect, coverPaint)
        canvas.restore()
    }

    if (reflectionAlpha > 0.001f) drawCanvasReflection(
        canvas = canvas,
        bitmap = bitmap,
        slotWidth = drawWidth,
        slotHeight = drawHeight,
        reflectionGap = reflectionGap * lane.sizeScale,
        cornerRadius = scaledCornerRadius,
        reflectionPaint = reflectionPaint,
        gradientPaint = gradientPaint,
        layerPaint = layerPaint,
        reflectionSourceRect = reflectionSourceRect,
        reflectionRect = reflectionRect,
        reflectionDestination = reflectionDestination,
        clipPath = clipPath,
        alpha = reflectionAlpha,
    )
    canvas.restore()
}

private fun drawCanvasReflection(
    canvas: android.graphics.Canvas,
    bitmap: Bitmap,
    slotWidth: Float,
    slotHeight: Float,
    reflectionGap: Float,
    cornerRadius: Float,
    reflectionPaint: Paint,
    gradientPaint: Paint,
    layerPaint: Paint,
    reflectionSourceRect: Rect,
    reflectionRect: RectF,
    reflectionDestination: RectF,
    clipPath: Path,
    alpha: Float,
) {
    val reflectionHeight = slotHeight * CanvasReflectionHeightFraction
    val top = slotHeight * 0.5f + reflectionGap
    val bottom = top + reflectionHeight
    reflectionRect.set(-slotWidth * 0.5f, top, slotWidth * 0.5f, bottom)
    centerCropSource(bitmap, slotWidth, slotHeight, reflectionSourceRect)
    val sourceSliceHeight =
        (reflectionSourceRect.height() * CanvasReflectionHeightFraction)
            .toInt()
            .coerceIn(1, reflectionSourceRect.height())
    reflectionSourceRect.top = reflectionSourceRect.bottom - sourceSliceHeight

    canvas.save()
    clipPath.reset()
    clipPath.addRoundRect(
        reflectionRect,
        cornerRadius,
        cornerRadius,
        Path.Direction.CW
    )
    canvas.clipPath(clipPath)
    layerPaint.alpha = (CanvasReflectionAlpha * alpha.coerceIn(0f, 1f) * 255f).toInt()
    val layer = canvas.saveLayer(reflectionRect, layerPaint)
    reflectionPaint.alpha = 255
    reflectionPaint.xfermode = null
    canvas.save()
    canvas.translate(0f, bottom)
    canvas.scale(1f, -1f)
    reflectionDestination.set(-slotWidth * 0.5f, 0f, slotWidth * 0.5f, reflectionHeight)
    canvas.drawBitmap(bitmap, reflectionSourceRect, reflectionDestination, reflectionPaint)
    canvas.restore()

    gradientPaint.shader = LinearGradient(
        0f,
        top,
        0f,
        bottom,
        intArrayOf(
            0xFFFFFFFF.toInt(),
            0x8CFFFFFF.toInt(),
            0x00FFFFFF
        ),
        floatArrayOf(0f, 0.45f, 1f),
        Shader.TileMode.CLAMP
    )
    gradientPaint.xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
    gradientPaint.alpha = 255
    canvas.drawRect(reflectionRect, gradientPaint)
    gradientPaint.xfermode = null
    gradientPaint.shader = null
    canvas.restoreToCount(layer)
    canvas.restore()
}

private fun centerCropSource(
    bitmap: Bitmap,
    destinationWidth: Float,
    destinationHeight: Float,
    output: Rect
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

private fun wrapCanvasCarouselIndex(index: Int, size: Int): Int {
    if (size <= 0) return 0
    return ((index % size) + size) % size
}
