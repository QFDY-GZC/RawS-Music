package com.rawsmusic.core.ui.widget.flow

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.rawsmusic.core.ui.theme.ThemeManager
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Compose-free, deterministic renderer of the user's CURRENT background style for offline export
 * (lyric video frames, share posters).
 *
 * Every style reuses the exact on-screen drawing code path:
 * - FLOW: the player-surface blob scene (same seeds, stops 0/0.58/1, alpha 1/0.42/0, 0.92 scale).
 * - ALBUM_COLOR: [ArtworkColorRenderer] with an explicit time value.
 * - STATIC: the native prepared artwork endpoint, center-cropped exactly like the player.
 * - SIMPLE: the theme background colour supplied by the caller.
 *
 * All inputs (palette, textures, tuning) are resolved once in [prepareRawBackgroundFrameRenderer];
 * [drawFrame] is then a pure function of (canvas size, time) and can run on any thread.
 */
interface RawBackgroundFrameRenderer {
    fun drawFrame(canvas: AndroidCanvas, width: Int, height: Int, timeSeconds: Float)
}

/**
 * Resolve the currently selected background for [coverKey]/[artwork] into a frame renderer.
 *
 * [artwork] should be a CPU-readable bitmap of the song cover (the STATIC style requires it).
 * [simpleBackgroundArgb] is the theme background used by the SIMPLE style and as a safe fallback.
 */
suspend fun prepareRawBackgroundFrameRenderer(
    context: Context,
    coverKey: String?,
    artwork: Bitmap?,
    simpleBackgroundArgb: Int,
): RawBackgroundFrameRenderer {
    val appContext = context.applicationContext
    RawFlowTuningState.ensureInitialized(appContext)
    val isDark = ThemeManager.isDarkMode(appContext)
    val fallbackMode = if (isDark) RawFlowMode.DARK else RawFlowMode.LIGHT
    val flowMode = RawFlowRuntimeState.mode ?: RawFlowRuntimeState.readPersisted(appContext, fallbackMode)
    val usableArtwork = artwork?.takeUnless { it.isRecycled }
    val fallback = SolidBackgroundFrameRenderer(simpleBackgroundArgb)

    return when (RawFlowTuningState.style) {
        RawBackgroundStyle.SIMPLE -> fallback
        RawBackgroundStyle.FLOW -> {
            val colors = loadRawFlowEndpointColors(
                context = appContext,
                sourceCoverKey = coverKey,
                sourceArtwork = usableArtwork,
                mode = flowMode,
                isSystemDark = isDark,
                fallbackColors = defaultFlowColors(flowMode, isDark),
                backgroundStyle = RawBackgroundStyle.FLOW,
                traceLane = "export",
            )
            val resolved = expandReferenceFlowDrawSlots(
                colors.ifEmpty { defaultFlowColors(flowMode, isDark) }
                    .take(FLOW_EXPORT_COLOR_COUNT)
                    .map { tuneFlowColor(it, RawFlowTuningState.saturation, RawFlowTuningState.brightness) }
            )
            FlowBlobFrameRenderer(
                resolved = resolved,
                baseArgb = baseFlowColor(flowMode, isDark, resolved.firstOrNull()).toArgb(),
                motionSpeed = RawFlowTuningState.speed,
                isDark = isDark,
            )
        }
        RawBackgroundStyle.ALBUM_COLOR -> {
            val texture = loadAlbumColorTexture(
                context = appContext,
                sourceCoverKey = coverKey,
                sourceArtwork = usableArtwork,
                allowProviderDecode = true,
            ) ?: usableArtwork?.let { createAlbumSourceTexture(it) }
            if (texture == null) {
                fallback
            } else {
                AlbumColorFrameRenderer(
                    texture = texture,
                    densityDpi = appContext.resources.configuration.densityDpi,
                    saturation = RawFlowTuningState.saturation,
                    brightness = RawFlowTuningState.brightness,
                    motionSpeed = RawFlowTuningState.albumColorSpeed,
                )
            }
        }
        RawBackgroundStyle.STATIC -> {
            val config = StaticAaConfig(
                saturation = RawFlowTuningState.staticSaturation,
                brightness = RawFlowTuningState.staticBrightness,
                gradient = RawFlowTuningState.staticGradient,
                gradientColor = RawFlowTuningState.staticGradientColor,
                blur = RawFlowTuningState.staticBlur,
                detail = RawFlowTuningState.staticDetail,
            )
            val prepared = usableArtwork?.let {
                prepareStaticAaEndpoint(coverKey, it, config, traceLane = "export")
            }
            if (prepared == null || prepared.isRecycled) fallback else StaticArtworkFrameRenderer(prepared)
        }
    }
}

private const val FLOW_EXPORT_COLOR_COUNT = 5

private class SolidBackgroundFrameRenderer(private val argb: Int) : RawBackgroundFrameRenderer {
    override fun drawFrame(canvas: AndroidCanvas, width: Int, height: Int, timeSeconds: Float) {
        canvas.drawColor(argb)
    }
}

/** Exact copy of the PLAYER-surface FLOW Canvas (RawFlowTransitionBackground) at progress 0. */
private class FlowBlobFrameRenderer(
    private val resolved: List<androidx.compose.ui.graphics.Color>,
    private val baseArgb: Int,
    private val motionSpeed: Float,
    private val isDark: Boolean,
) : RawBackgroundFrameRenderer {
    private val seeds = flowBlobSeeds()
    // Independent shader instances: RetainedFlowRadialBlob mutates its local matrix per draw, so the
    // export must never share the on-screen renderer's objects.
    private val blobs = List(FLOW_EXPORT_COLOR_COUNT) {
        RetainedFlowRadialBlob(
            stops = floatArrayOf(0f, 0.58f, 1f),
            alphaMultipliers = floatArrayOf(1f, 0.42f, 0f),
        )
    }

    override fun drawFrame(canvas: AndroidCanvas, width: Int, height: Int, timeSeconds: Float) {
        canvas.drawColor(baseArgb)
        if (resolved.isEmpty()) return
        val w = width.toFloat()
        val h = height.toFloat()
        val countScale = 0.92f
        val maxDimension = max(w, h)
        val animatedTime = timeSeconds * motionSpeed
        repeat(FLOW_EXPORT_COLOR_COUNT) { index ->
            val color = resolved.getOrElse(index) { resolved.last() }
            val seed = seeds[index % seeds.size]
            val phase = seed.phase + index * 0.83f
            val x = w * seed.baseX + w * seed.amplitudeX * sin(animatedTime * seed.speedX + phase)
            val y = h * seed.baseY + h * seed.amplitudeY * cos(animatedTime * seed.speedY + phase * 1.21f)
            val radiusPulse = 1f + 0.11f * sin(animatedTime * seed.radiusSpeed + phase)
            val radius = maxDimension * seed.radius * countScale * radiusPulse
            blobs[index].draw(
                canvas = canvas,
                color = color,
                coreAlpha = if (isDark) 0.88f else 0.82f,
                centerX = x,
                centerY = y,
                radius = radius,
            )
        }
    }
}

private class AlbumColorFrameRenderer(
    private val texture: Bitmap,
    densityDpi: Int,
    private val saturation: Float,
    private val brightness: Float,
    private val motionSpeed: Float,
) : RawBackgroundFrameRenderer {
    private val renderer = ArtworkColorRenderer(densityDpi = densityDpi)

    override fun drawFrame(canvas: AndroidCanvas, width: Int, height: Int, timeSeconds: Float) {
        canvas.drawColor(android.graphics.Color.BLACK)
        renderer.draw(
            canvas = canvas,
            source = texture,
            width = width.toFloat(),
            height = height.toFloat(),
            timeSeconds = timeSeconds,
            saturationScale = saturation,
            brightnessScale = brightness,
            motionSpeed = motionSpeed,
            alpha = 1f,
        )
    }
}

/** Same center-crop mapping as drawStaticArtworkCenterCrop (unit-square artwork, excess clipped). */
private class StaticArtworkFrameRenderer(private val image: Bitmap) : RawBackgroundFrameRenderer {
    private val paint = AndroidPaint(AndroidPaint.FILTER_BITMAP_FLAG)
    private val src = Rect()
    private val dst = RectF()

    override fun drawFrame(canvas: AndroidCanvas, width: Int, height: Int, timeSeconds: Float) {
        canvas.drawColor(android.graphics.Color.BLACK)
        if (width <= 0 || height <= 0 || image.isRecycled || image.width <= 0 || image.height <= 0) return
        val targetAspect = width.toFloat() / height.toFloat()
        val sourceAspect = image.width.toFloat() / image.height.toFloat()
        if (sourceAspect > targetAspect) {
            val srcHeight = image.height
            val srcWidth = (srcHeight * targetAspect).roundToInt().coerceIn(1, image.width)
            val srcX = ((image.width - srcWidth) / 2).coerceAtLeast(0)
            src.set(srcX, 0, srcX + srcWidth, srcHeight)
        } else {
            val srcWidth = image.width
            val srcHeight = (srcWidth / targetAspect).roundToInt().coerceIn(1, image.height)
            val srcY = ((image.height - srcHeight) / 2).coerceAtLeast(0)
            src.set(0, srcY, srcWidth, srcY + srcHeight)
        }
        dst.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(image, src, dst, paint)
    }
}
