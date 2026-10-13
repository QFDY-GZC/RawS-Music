package com.rawsmusic.core.ui.widget.flow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas as AndroidCanvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkBitmapRuntime
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.google.android.renderscript.Toolkit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Texture parameters for the artwork-driven background field. */
private const val ALBUM_COLOR_TEXTURE_SIDE = 384
private const val ALBUM_COLOR_BLUR_RADIUS = 25
private const val ALBUM_COLOR_SLOT_COUNT = 4
private const val ARTWORK_BACKGROUND_BUFFER_OVERSCAN = 1.3f
private const val ARTWORK_BACKGROUND_SATURATION = 2.5f
private const val ARTWORK_BACKGROUND_ROTATION_1_SECONDS = 120f
private const val ARTWORK_BACKGROUND_ROTATION_2_SECONDS = 90f
private const val ARTWORK_BACKGROUND_ROTATION_3_SECONDS = 70f

private data class AlbumTextureEntry(val texture: Bitmap, val visibleSource: Long?)
private val albumColorTextureCache = LruCache<String, AlbumTextureEntry>(24)
private var albumColorTextureRevision by mutableLongStateOf(0L)


private data class AlbumColorTextureSlot(
    val key: String,
    val bitmap: Bitmap,
)

internal fun clearRawAlbumColorMemoryCache() {
    albumColorTextureCache.evictAll()
    albumColorTextureRevision++
}

internal fun cachedAlbumTexture(key: String?): Bitmap? =
    key?.takeIf { it.isNotBlank() }
        ?.let(albumColorTextureCache::get)
        ?.texture
        ?.takeUnless { it.isRecycled }

internal fun effectiveAlbumColorMode(mode: RawFlowMode): RawFlowMode =
    when (mode) {
        RawFlowMode.OFF -> RawFlowMode.OFF
        // Album-color mode owns its contrast policy: always derive the darker palette so the
        // global theme can use one stable light/white foreground treatment regardless of the
        // user's normal LIGHT/DARK/UNIVERSAL flow preference. That preference is left intact and
        // becomes visible again as soon as the user switches back to normal FLOW.
        else -> RawFlowMode.DARK
    }

private fun isCpuReadable(bitmap: Bitmap?): Boolean {
    val value = bitmap?.takeUnless { it.isRecycled } ?: return false
    return Build.VERSION.SDK_INT < 26 || value.config != Bitmap.Config.HARDWARE
}

internal fun createAlbumSourceTexture(source: Bitmap): Bitmap {
    val side = min(source.width, source.height).coerceAtLeast(1)
    val left = (source.width - side) / 2
    val top = (source.height - side) / 2
    val texture = Bitmap.createBitmap(
        ALBUM_COLOR_TEXTURE_SIDE,
        ALBUM_COLOR_TEXTURE_SIDE,
        Bitmap.Config.ARGB_8888,
    )
    val textureCanvas = AndroidCanvas(texture)
    textureCanvas.drawBitmap(
        source,
        Rect(left, top, left + side, top + side),
        RectF(0f, 0f, ALBUM_COLOR_TEXTURE_SIDE.toFloat(), ALBUM_COLOR_TEXTURE_SIDE.toFloat()),
        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
    )
    return texture
}

private fun blurAlbumTexture(source: Bitmap, radius: Int, passes: Int): Bitmap {
    if (radius <= 0 || passes <= 0) return source
    val width = source.width
    val height = source.height
    var input = IntArray(width * height)
    var output = IntArray(width * height)
    source.getPixels(input, 0, width, 0, 0, width, height)

    repeat(passes) {
        boxBlurHorizontal(input, output, width, height, radius)
        val swapH = input
        input = output
        output = swapH
        boxBlurVertical(input, output, width, height, radius)
        val swapV = input
        input = output
        output = swapV
    }

    val result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    result.setPixels(input, 0, width, 0, 0, width, height)
    source.recycle()
    return result
}

private fun boxBlurHorizontal(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val diameter = radius * 2 + 1
    for (y in 0 until height) {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        val row = y * width
        for (x in -radius..radius) {
            val color = input[row + x.coerceIn(0, width - 1)]
            a += color ushr 24
            r += color ushr 16 and 0xFF
            g += color ushr 8 and 0xFF
            b += color and 0xFF
        }
        for (x in 0 until width) {
            output[row + x] =
                ((a / diameter) shl 24) or
                    ((r / diameter) shl 16) or
                    ((g / diameter) shl 8) or
                    (b / diameter)
            val remove = input[row + (x - radius).coerceIn(0, width - 1)]
            val add = input[row + (x + radius + 1).coerceIn(0, width - 1)]
            a += (add ushr 24) - (remove ushr 24)
            r += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
            g += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
            b += (add and 0xFF) - (remove and 0xFF)
        }
    }
}

private fun boxBlurVertical(
    input: IntArray,
    output: IntArray,
    width: Int,
    height: Int,
    radius: Int,
) {
    val diameter = radius * 2 + 1
    for (x in 0 until width) {
        var a = 0
        var r = 0
        var g = 0
        var b = 0
        for (y in -radius..radius) {
            val color = input[y.coerceIn(0, height - 1) * width + x]
            a += color ushr 24
            r += color ushr 16 and 0xFF
            g += color ushr 8 and 0xFF
            b += color and 0xFF
        }
        for (y in 0 until height) {
            output[y * width + x] =
                ((a / diameter) shl 24) or
                    ((r / diameter) shl 16) or
                    ((g / diameter) shl 8) or
                    (b / diameter)
            val remove = input[(y - radius).coerceIn(0, height - 1) * width + x]
            val add = input[(y + radius + 1).coerceIn(0, height - 1) * width + x]
            a += (add ushr 24) - (remove ushr 24)
            r += (add ushr 16 and 0xFF) - (remove ushr 16 and 0xFF)
            g += (add ushr 8 and 0xFF) - (remove ushr 8 and 0xFF)
            b += (add and 0xFF) - (remove and 0xFF)
        }
    }
}

internal suspend fun loadAlbumColorTexture(
    context: Context,
    sourceCoverKey: String?,
    sourceArtwork: Bitmap?,
    allowProviderDecode: Boolean,
): Bitmap? {
    val key = sourceCoverKey?.takeIf { it.isNotBlank() } ?: return null
    val visible = sourceArtwork?.takeUnless { it.isRecycled }
    val visibleSource = visible?.let {
        (System.identityHashCode(it).toLong() shl 32) xor (it.generationId.toLong() and 0xffffffffL)
    }
    albumColorTextureCache.get(key)?.takeIf {
        !it.texture.isRecycled && canReuseAlbumTexture(it.visibleSource, visibleSource)
    }?.let {
        return it.texture
    }
    // The visible cover is authoritative. A fresh decode may select another encoded picture.
    // Hardware readback is only done once on a worker when this visible bitmap changes.
    val supplied = withContext(Dispatchers.Default) {
        visible?.let {
            if (isCpuReadable(it)) it else it.copy(Bitmap.Config.ARGB_8888, false)
        }
    }
    if (visible != null && supplied == null) return null
    val source = supplied ?: if (allowProviderDecode) {
        ArtworkBitmapRuntime.executePixelAnalysisBitmap(
            context = context,
            key = key,
            targetSide = ALBUM_COLOR_TEXTURE_SIDE,
        )
    } else {
        null
    }
    val usable = source?.takeIf(::isCpuReadable) ?: run {
        return null
    }
    val sourceTexture = try {
        withContext(Dispatchers.Default) { createAlbumSourceTexture(usable) }
    } finally {
        if (supplied != null && supplied !== visible) supplied.recycle()
    }
    val cached = albumColorTextureCache.get(key)
    if (cached != null && !cached.texture.isRecycled &&
        !canPublishAlbumTexture(cached.visibleSource, visibleSource)) {
        sourceTexture.recycle()
        return cached.texture
    }
    albumColorTextureCache.put(key, AlbumTextureEntry(sourceTexture, visibleSource))
    // Wake retained background consumers which missed this source during startup.
    albumColorTextureRevision++
    return sourceTexture
}

/**
 * Builds an artwork-driven background in a downsampled buffer:
 * three copies of the same artwork are composited into a tiny buffer, highly saturated, dark/light
 * scrims are applied, radius-25 blur runs in that tiny space, then a MIRROR shader enlarges it.
 */
internal class ArtworkColorRenderer(
    densityDpi: Int,
) {
    private val downsampleScale = if (densityDpi >= 420) 24f else 16f
    private val sourcePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val outputPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val sourceMatrix = Matrix()
    private val outputMatrix = Matrix()
    private val colorMatrix = ColorMatrix()
    private val brightnessMatrix = ColorMatrix()

    private var workingBitmap: Bitmap? = null
    private var blurredBitmap: Bitmap? = null
    private var workingCanvas: AndroidCanvas? = null
    private var outputShader: BitmapShader? = null
    private var inputPixels = IntArray(0)
    private var outputPixels = IntArray(0)
    private var toolkitAvailable = true

    private fun ensureBuffers(width: Int, height: Int) {
        val active = workingBitmap
        if (
            active != null &&
            !active.isRecycled &&
            active.width == width &&
            active.height == height &&
            blurredBitmap?.let { !it.isRecycled && it.width == width && it.height == height } == true
        ) {
            return
        }
        workingBitmap?.recycle()
        blurredBitmap?.recycle()
        workingBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        blurredBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        workingCanvas = AndroidCanvas(requireNotNull(workingBitmap))
        outputShader = BitmapShader(
            requireNotNull(blurredBitmap),
            Shader.TileMode.MIRROR,
            Shader.TileMode.MIRROR,
        ).also { outputPaint.shader = it }
        inputPixels = IntArray(width * height)
        outputPixels = IntArray(width * height)
    }

    private fun rotation(timeSeconds: Float, durationSeconds: Float, direction: Float): Float {
        val phase = (timeSeconds.toDouble() % durationSeconds.toDouble()) / durationSeconds.toDouble()
        return (phase * 360.0 * direction).toFloat()
    }

    private fun drawSource(
        canvas: AndroidCanvas,
        source: Bitmap,
        scale: Float,
        rotation: Float,
        pivot: Float,
        offsetX: Float,
        offsetY: Float,
        secondRotationPivotX: Float? = null,
        secondRotationPivotY: Float? = null,
    ) {
        sourceMatrix.reset()
        sourceMatrix.setScale(scale, scale)
        sourceMatrix.postRotate(rotation, pivot, pivot)
        sourceMatrix.postTranslate(offsetX, offsetY)
        if (secondRotationPivotX != null && secondRotationPivotY != null) {
            sourceMatrix.postRotate(rotation, secondRotationPivotX, secondRotationPivotY)
        }
        canvas.drawBitmap(source, sourceMatrix, sourcePaint)
    }

    private fun blurWorkingBuffer(width: Int, height: Int) {
        val working = requireNotNull(workingBitmap)
        if (toolkitAvailable) {
            try {
                val toolkitOutput = Toolkit.blur(working, ALBUM_COLOR_BLUR_RADIUS)
                val previous = blurredBitmap
                blurredBitmap = toolkitOutput
                outputShader = BitmapShader(
                    toolkitOutput,
                    Shader.TileMode.MIRROR,
                    Shader.TileMode.MIRROR,
                ).also { outputPaint.shader = it }
                if (previous !== toolkitOutput && previous != null && !previous.isRecycled) {
                    previous.recycle()
                }
                return
            } catch (error: Throwable) {
                toolkitAvailable = false
                val fallback = blurredBitmap
                if (
                    fallback == null ||
                    fallback.isRecycled ||
                    fallback.width != width ||
                    fallback.height != height
                ) {
                    fallback?.takeUnless { it.isRecycled }?.recycle()
                    blurredBitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                    outputShader = BitmapShader(
                        requireNotNull(blurredBitmap),
                        Shader.TileMode.MIRROR,
                        Shader.TileMode.MIRROR,
                    ).also { outputPaint.shader = it }
                }
            }
        }
        val blurred = requireNotNull(blurredBitmap)
        working.getPixels(inputPixels, 0, width, 0, 0, width, height)
        boxBlurHorizontal(
            input = inputPixels,
            output = outputPixels,
            width = width,
            height = height,
            radius = ALBUM_COLOR_BLUR_RADIUS,
        )
        boxBlurVertical(
            input = outputPixels,
            output = inputPixels,
            width = width,
            height = height,
            radius = ALBUM_COLOR_BLUR_RADIUS,
        )
        blurred.setPixels(inputPixels, 0, width, 0, 0, width, height)
    }

    fun draw(
        canvas: AndroidCanvas,
        source: Bitmap,
        width: Float,
        height: Float,
        timeSeconds: Float,
        saturationScale: Float,
        brightnessScale: Float,
        motionSpeed: Float,
        alpha: Float,
    ) {
        if (source.isRecycled || width <= 0f || height <= 0f || alpha <= 0f) return

        val bufferWidth = kotlin.math.round(
            (width * ARTWORK_BACKGROUND_BUFFER_OVERSCAN) / downsampleScale
        ).toInt().coerceAtLeast(2)
        val bufferHeight = kotlin.math.round(
            (height * ARTWORK_BACKGROUND_BUFFER_OVERSCAN) / downsampleScale
        ).toInt().coerceAtLeast(2)
        ensureBuffers(bufferWidth, bufferHeight)

        val working = requireNotNull(workingBitmap)
        val lowCanvas = requireNotNull(workingCanvas)
        working.eraseColor(0)

        colorMatrix.reset()
        colorMatrix.setSaturation(ARTWORK_BACKGROUND_SATURATION * saturationScale)
        if (brightnessScale != 1f) {
            brightnessMatrix.setScale(brightnessScale, brightnessScale, brightnessScale, 1f)
            colorMatrix.postConcat(brightnessMatrix)
        }
        sourcePaint.colorFilter = ColorMatrixColorFilter(colorMatrix)
        sourcePaint.alpha = 255

        val fieldSide = kotlin.math.round(
            max(bufferWidth, bufferHeight) * ARTWORK_BACKGROUND_BUFFER_OVERSCAN
        )
        val scale = fieldSide / source.height.toFloat().coerceAtLeast(1f)
        val centerOffsetX = (-(fieldSide - bufferWidth)) * 0.5f
        val centerOffsetY = (-(fieldSide - bufferHeight)) * 0.5f
        val fieldPivot = fieldSide * 0.5f

        val motionTimeSeconds = timeSeconds * motionSpeed.coerceIn(0.25f, 6f)
        val r1 = rotation(motionTimeSeconds, ARTWORK_BACKGROUND_ROTATION_1_SECONDS, -1f)
        val r2 = rotation(motionTimeSeconds, ARTWORK_BACKGROUND_ROTATION_2_SECONDS, 1f)
        val r3 = rotation(motionTimeSeconds, ARTWORK_BACKGROUND_ROTATION_3_SECONDS, 1f)

        drawSource(
            canvas = lowCanvas,
            source = source,
            scale = scale,
            rotation = r1,
            pivot = fieldPivot,
            offsetX = centerOffsetX,
            offsetY = centerOffsetY,
        )
        drawSource(
            canvas = lowCanvas,
            source = source,
            scale = scale,
            rotation = r2,
            pivot = fieldPivot,
            offsetX = centerOffsetX - 0.95f * bufferWidth,
            offsetY = centerOffsetY - 0.70f * bufferHeight,
        )
        drawSource(
            canvas = lowCanvas,
            source = source,
            scale = scale,
            rotation = r3,
            pivot = fieldPivot,
            offsetX = centerOffsetX - 0.50f * bufferWidth,
            offsetY = centerOffsetY + 0.70f * bufferHeight,
            secondRotationPivotX = bufferWidth * 0.5f,
            secondRotationPivotY = bufferHeight * 0.5f,
        )

        // Apply the dark scrim at 30%, then the light scrim at 10%.
        lowCanvas.drawColor(0x4D000000)
        lowCanvas.drawColor(0x1AFFFFFF)
        blurWorkingBuffer(bufferWidth, bufferHeight)

        outputMatrix.reset()
        outputMatrix.setScale(downsampleScale, downsampleScale)
        outputMatrix.preTranslate(
            (-(bufferWidth - (bufferWidth / ARTWORK_BACKGROUND_BUFFER_OVERSCAN))) * 0.5f,
            (-(bufferHeight - (bufferHeight / ARTWORK_BACKGROUND_BUFFER_OVERSCAN))) * 0.5f,
        )
        requireNotNull(outputShader).setLocalMatrix(outputMatrix)
        outputPaint.alpha = (alpha.coerceIn(0f, 1f) * 255f).toInt()
        canvas.drawRect(0f, 0f, width, height, outputPaint)
    }
}

private data class OkLabColor(val l: Float, val a: Float, val b: Float)

private fun srgbToLinear(value: Float): Float =
    if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)

private fun linearToSrgb(value: Float): Float {
    val v = value.coerceAtLeast(0f)
    return if (v <= 0.0031308f) 12.92f * v else 1.055f * v.pow(1f / 2.4f) - 0.055f
}

private fun toOkLab(color: Color): OkLabColor {
    val r = srgbToLinear(color.red)
    val g = srgbToLinear(color.green)
    val b = srgbToLinear(color.blue)
    val l = 0.41222146f * r + 0.53633255f * g + 0.051445995f * b
    val m = 0.2119035f * r + 0.6806995f * g + 0.10739696f * b
    val s = 0.08830246f * r + 0.28171885f * g + 0.6299787f * b
    val lRoot = Math.cbrt(l.toDouble()).toFloat()
    val mRoot = Math.cbrt(m.toDouble()).toFloat()
    val sRoot = Math.cbrt(s.toDouble()).toFloat()
    return OkLabColor(
        l = 0.21045426f * lRoot + 0.7936178f * mRoot - 0.004072047f * sRoot,
        a = 1.9779985f * lRoot - 2.4285922f * mRoot + 0.4505937f * sRoot,
        b = 0.025904037f * lRoot + 0.78277177f * mRoot - 0.80867577f * sRoot,
    )
}

private fun fromOkLab(color: OkLabColor): Color {
    val lRoot = color.l + 0.39633778f * color.a + 0.21580376f * color.b
    val mRoot = color.l - 0.105561346f * color.a - 0.06385417f * color.b
    val sRoot = color.l - 0.08948418f * color.a - 1.2914855f * color.b
    val l = lRoot * lRoot * lRoot
    val m = mRoot * mRoot * mRoot
    val s = sRoot * sRoot * sRoot
    return Color(
        red = linearToSrgb(4.0767417f * l - 3.3077116f * m + 0.23096994f * s).coerceIn(0f, 1f),
        green = linearToSrgb(-1.268438f * l + 2.6097574f * m - 0.3413194f * s).coerceIn(0f, 1f),
        blue = linearToSrgb(-0.004196086f * l - 0.7034186f * m + 1.7076147f * s).coerceIn(0f, 1f),
        alpha = 1f,
    )
}

private fun lerpOkLab(start: Color, end: Color, fraction: Float): Color {
    val t = fraction.coerceIn(0f, 1f)
    val from = toOkLab(start)
    val to = toOkLab(end)
    return fromOkLab(
        OkLabColor(
            l = from.l + (to.l - from.l) * t,
            a = from.a + (to.a - from.a) * t,
            b = from.b + (to.b - from.b) * t,
        )
    )
}

private fun punchAlbumColor(color: Color, mode: RawFlowMode, index: Int): Color {
    val source = toOkLab(color)
    val chromaBoost = when (index) {
        2 -> 1.10f
        0, 1 -> 1.06f
        else -> 1.03f
    }
    val lightness = when {
        mode == RawFlowMode.DARK && index == 2 -> source.l + 0.025f
        mode == RawFlowMode.DARK -> source.l + 0.006f
        index == 2 -> source.l - 0.004f
        index == 3 -> source.l - 0.016f
        else -> source.l - 0.006f
    }
    return fromOkLab(
        OkLabColor(
            l = lightness.coerceIn(0.08f, 0.94f),
            a = source.a * chromaBoost,
            b = source.b * chromaBoost,
        )
    )
}

private fun expandAlbumPalette(colors: List<Color>, fallback: List<Color>): List<Color> {
    val source = colors.ifEmpty { fallback }.ifEmpty { listOf(Color.Black) }
    return List(ALBUM_COLOR_SLOT_COUNT) { index -> source.getOrElse(index) { source.last() } }
}

private fun albumSeed(key: String?): Float {
    val hash = key?.hashCode() ?: 0
    return ((hash.toLong() and 0xffffL).toFloat() / 65535f) * (2f * PI.toFloat())
}

private fun darken(color: Color, amount: Float): Color {
    val factor = (1f - amount).coerceIn(0f, 1f)
    return Color(color.red * factor, color.green * factor, color.blue * factor, 1f)
}

private fun lighten(color: Color, amount: Float): Color =
    lerpFlowColor(color, Color.White, amount.coerceIn(0f, 1f)).copy(alpha = 1f)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawAlbumColorScene(
    renderer: ArtworkColorRenderer,
    texture: Bitmap?,
    timeSeconds: Float,
    saturationScale: Float,
    brightnessScale: Float,
    motionSpeed: Float,
    sceneAlpha: Float = 1f,
) {
    val source = texture?.takeUnless { it.isRecycled } ?: return
    renderer.draw(
        canvas = drawContext.canvas.nativeCanvas,
        source = source,
        width = size.width,
        height = size.height,
        timeSeconds = timeSeconds,
        saturationScale = saturationScale,
        brightnessScale = brightnessScale,
        motionSpeed = motionSpeed,
        alpha = sceneAlpha,
    )
}

@Composable
internal fun RawAlbumColorBackground(
    mode: RawFlowMode,
    sourceCoverKey: String?,
    fallbackSourceCoverKey: String?,
    sourceArtwork: Bitmap?,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    motionEnabled: Boolean = true,
    frameIntervalMs: Long = 16L,
) {
    val context = LocalContext.current.applicationContext
    val frameAnimationActive = LocalUiFrameAnimationActive.current
    val effectiveMode = effectiveAlbumColorMode(mode)
    val textureRevision = albumColorTextureRevision
    val artworkRevision = BitmapProvider.artworkRevision
    val texture by produceState(
        initialValue = cachedAlbumTexture(sourceCoverKey) ?: cachedAlbumTexture(fallbackSourceCoverKey),
        sourceCoverKey,
        sourceArtwork,
        active,
        fallbackSourceCoverKey,
        textureRevision,
        artworkRevision,
    ) {
        if (!active || sourceCoverKey.isNullOrBlank()) return@produceState
        recoverBackgroundTexture {
            loadAlbumColorTexture(
                context = context,
                sourceCoverKey = sourceCoverKey,
                sourceArtwork = sourceArtwork,
                allowProviderDecode = true,
            )
        }?.let { value = it }
    }
    val saturation = RawFlowTuningState.saturation
    val brightness = RawFlowTuningState.brightness
    val motionSpeed = RawFlowTuningState.albumColorSpeed
    val timeState = rememberRawFlowTimeState(
        enabled = active && motionEnabled && frameAnimationActive,
        modeName = "album_color_${effectiveMode.prefValue}",
        targetFps = RawFlowTuningState.frameRateFps,
    )
    val densityDpi = context.resources.configuration.densityDpi
    val renderer = remember(densityDpi) {
        ArtworkColorRenderer(densityDpi = densityDpi)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        drawAlbumColorScene(
            renderer = renderer,
            texture = texture,
            timeSeconds = timeState.value,
            saturationScale = saturation,
            brightnessScale = brightness,
            motionSpeed = motionSpeed,
        )
    }
}

@Composable
internal fun RawAlbumColorTransitionBackground(
    mode: RawFlowMode,
    currentCoverKey: String?,
    targetCoverKey: String?,
    currentArtwork: Bitmap?,
    targetArtwork: Bitmap?,
    progressProvider: () -> Float,
    modifier: Modifier = Modifier,
    active: Boolean = true,
    motionEnabled: Boolean = true,
    frameIntervalMs: Long = 16L,
    prefetchPreviousCoverKey: String? = null,
    prefetchPreviousArtwork: Bitmap? = null,
    prefetchNextCoverKey: String? = null,
    prefetchNextArtwork: Bitmap? = null,
    deferHeavyEndpointWork: Boolean = false,
) {
    val context = LocalContext.current.applicationContext
    val frameAnimationActive = LocalUiFrameAnimationActive.current
    val effectiveMode = effectiveAlbumColorMode(mode)
    val textureRevision = albumColorTextureRevision
    val artworkRevision = BitmapProvider.artworkRevision
    val currentTextureSlot by produceState<AlbumColorTextureSlot?>(
        initialValue = currentCoverKey
            ?.takeIf { it.isNotBlank() }
            ?.let { key -> cachedAlbumTexture(key)?.let { AlbumColorTextureSlot(key, it) } },
        currentCoverKey,
        currentArtwork,
        textureRevision,
        artworkRevision,
    ) {
        val key = currentCoverKey?.takeIf { it.isNotBlank() } ?: return@produceState
        loadAlbumColorTexture(
            context = context,
            sourceCoverKey = key,
            sourceArtwork = currentArtwork,
            allowProviderDecode = true,
        )?.let { value = AlbumColorTextureSlot(key, it) }
    }
    val targetTextureSlot by produceState<AlbumColorTextureSlot?>(
        initialValue = targetCoverKey
            ?.takeIf { it.isNotBlank() }
            ?.let { key -> cachedAlbumTexture(key)?.let { AlbumColorTextureSlot(key, it) } },
        targetCoverKey,
        targetArtwork,
        textureRevision,
        artworkRevision,
    ) {
        val key = targetCoverKey?.takeIf { it.isNotBlank() } ?: return@produceState
        loadAlbumColorTexture(
            context = context,
            sourceCoverKey = key,
            sourceArtwork = targetArtwork,
            // The active swipe target is part of the visible transition and must be allowed to
            // resolve even while gesture motion is in progress. The decode runs on Dispatchers.IO
            // and the texture build runs on Dispatchers.Default, so this does not block the UI
            // gesture thread. Deferring it until commit made the backdrop snap only after the song
            // switch completed.
            allowProviderDecode = true,
        )?.let { value = AlbumColorTextureSlot(key, it) }
    }

    LaunchedEffect(
        prefetchPreviousCoverKey,
        prefetchPreviousArtwork,
        deferHeavyEndpointWork,
    ) {
        if (deferHeavyEndpointWork) return@LaunchedEffect
        loadAlbumColorTexture(
            context = context,
            sourceCoverKey = prefetchPreviousCoverKey,
            sourceArtwork = prefetchPreviousArtwork,
            allowProviderDecode = true,
        )
    }
    LaunchedEffect(
        prefetchNextCoverKey,
        prefetchNextArtwork,
        deferHeavyEndpointWork,
    ) {
        if (deferHeavyEndpointWork) return@LaunchedEffect
        loadAlbumColorTexture(
            context = context,
            sourceCoverKey = prefetchNextCoverKey,
            sourceArtwork = prefetchNextArtwork,
            allowProviderDecode = true,
        )
    }

    val saturation = RawFlowTuningState.saturation
    val brightness = RawFlowTuningState.brightness
    val motionSpeed = RawFlowTuningState.albumColorSpeed
    val timeState = rememberRawFlowTimeState(
        enabled = active && motionEnabled && frameAnimationActive,
        modeName = "album_color_transition_${effectiveMode.prefValue}",
        targetFps = RawFlowTuningState.frameRateFps,
    )
    val densityDpi = context.resources.configuration.densityDpi
    val currentRenderer = remember(densityDpi) {
        ArtworkColorRenderer(densityDpi = densityDpi)
    }
    val targetRenderer = remember(densityDpi) {
        ArtworkColorRenderer(densityDpi = densityDpi)
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val progress = progressProvider().coerceIn(0f, 1f)
        val currentTexture = when {
            currentTextureSlot?.key == currentCoverKey -> currentTextureSlot?.bitmap
            targetTextureSlot?.key == currentCoverKey -> targetTextureSlot?.bitmap
            else -> currentTextureSlot?.bitmap ?: targetTextureSlot?.bitmap
        }
        val targetTexture = targetTextureSlot
            ?.takeIf { slot ->
                slot.key == targetCoverKey &&
                    !targetCoverKey.isNullOrBlank() &&
                    targetCoverKey != currentCoverKey
            }
            ?.bitmap
        if (currentTexture != null) {
            drawAlbumColorScene(
                renderer = currentRenderer,
                texture = currentTexture,
                timeSeconds = timeState.value,
                saturationScale = saturation,
                brightnessScale = brightness,
                motionSpeed = motionSpeed,
            )
        }
        if (targetTexture != null && (currentTexture == null || progress > 0f)) {
            drawAlbumColorScene(
                renderer = targetRenderer,
                texture = targetTexture,
                timeSeconds = timeState.value,
                saturationScale = saturation,
                brightnessScale = brightness,
                motionSpeed = motionSpeed,
                sceneAlpha = if (currentTexture == null) 1f else progress,
            )
        }
    }
}
