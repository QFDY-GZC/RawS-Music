package com.rawsmusic.core.ui.widget.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

internal data class ClassicConeFrame(
    val height: Float,
    val falloff: Float,
    val coneK: Float,
    val w0: Float,
    val alpha1: Float,
    val alpha2: Float,
)

internal class ClassicConeAnimation {
    private var mode = ClassicConeMode.Off
    private var from = ClassicConeFrame(0.1f, 0f, 0f, 0f, 0f, 0f)
    private var to = from
    private val shapeProgress = ClassicProgressSpring(1f)
    private val alphaProgress = ClassicProgressSpring(1f)
    private var shapeResponse = 0.4f
    private var alphaResponse = 0.4f
    val running get() = shapeProgress.running || alphaProgress.running

    fun transition(next: ClassicConeMode) {
        if (mode == next) return
        // APK captures the current vector, then interpolates ALL shape uniforms with one
        // normalized spring. Independent world-space velocities deform the head on reversal.
        from = frame()
        mode = next
        shapeResponse = if (next == ClassicConeMode.Playing) 0.9f else 0.4f
        alphaResponse = if (next == ClassicConeMode.Playing) 0.3f else 0.4f
        to = when (next) {
            ClassicConeMode.Playing -> ClassicConeFrame(0.6f, 0.6f, 0.2f, 0.008f, 1f, 0.6f)
            ClassicConeMode.Dragging -> ClassicConeFrame(0.2f, 2f, 0f, 0.014f, 0.3f, 0.3f)
            ClassicConeMode.Off -> ClassicConeFrame(0.1f, 0f, 0f, 0f, 0f, 0f)
        }
        shapeProgress.snap(0f)
        shapeProgress.target = 1f
        alphaProgress.snap(0f)
        alphaProgress.target = 1f
    }

    fun advance(dt: Float) {
        shapeProgress.advance(dt, shapeResponse)
        alphaProgress.advance(dt, alphaResponse)
    }

    fun frame(): ClassicConeFrame {
        val shape = shapeProgress.value.coerceIn(0f, 1f)
        val alpha = alphaProgress.value.coerceIn(0f, 1f)
        fun mix(a: Float, b: Float, progress: Float) = a + (b - a) * progress
        return ClassicConeFrame(mix(from.height, to.height, shape),
            mix(from.falloff, to.falloff, shape), mix(from.coneK, to.coneK, shape),
            mix(from.w0, to.w0, shape), mix(from.alpha1, to.alpha1, alpha),
            mix(from.alpha2, to.alpha2, alpha))
    }
}

/** Direct native Canvas painter, matching the original track -> white head -> SCREEN light order. */
internal class ClassicProgressPainter(context: Context) {
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val trackRect = RectF()
    private val playedRect = RectF()
    private val headRect = RectF()
    private val clip = Path()
    private var headGradient: LinearGradient? = null
    private var gradientWidth = -1f
    private var gradientRtl = false
    private val matrix = android.graphics.Matrix()
    private val conic = if (Build.VERSION.SDK_INT >= 33) ClassicConicShaderPainter(context) else null
    private var fallback: ClassicConicSoftwarePainter? = null

    fun draw(
        canvas: Canvas, width: Float, centerY: Float, height: Float,
        fraction: Float, headFraction: Float, headAlpha: Float,
        trackArgb: Int, fillArgb: Int, cone: ClassicConeFrame, rtl: Boolean,
        lightEnabled: Boolean,
        drawTrack: Boolean = true, drawCone: Boolean = true,
    ) {
        val p = fraction.coerceIn(0f, 1f)
        val radius = height / 2f
        trackRect.set(0f, centerY - radius, width, centerY + radius)
        fillPaint.color = trackArgb
        if (drawTrack) canvas.drawRoundRect(trackRect, radius, radius, fillPaint)
        val headX = if (rtl) width * (1f - p) else width * p
        if (drawTrack && p > 0f) {
            clip.reset()
            clip.addRoundRect(trackRect, radius, radius, Path.Direction.CW)
            val save = canvas.save()
            canvas.clipPath(clip)
            playedRect.set(if (rtl) headX else 0f, trackRect.top,
                if (rtl) width else headX, trackRect.bottom)
            fillPaint.color = fillArgb
            canvas.drawRoundRect(playedRect, radius, radius, fillPaint)
            // Retain the previous head length while alpha fades out; clip it to the track.
            val len = headFraction * width
            if (lightEnabled && len > 0f && headAlpha > 0f) {
                headRect.set(if (rtl) headX else headX - len, trackRect.top,
                    if (rtl) headX + len else headX, trackRect.bottom)
                if (headGradient == null || len != gradientWidth || rtl != gradientRtl) {
                    headGradient = LinearGradient(0f, 0f, len, 0f,
                        if (rtl) -1 else 0x00ffffff, if (rtl) 0x00ffffff else -1,
                        Shader.TileMode.CLAMP)
                    gradientWidth = len
                    gradientRtl = rtl
                }
                matrix.setTranslate(headRect.left, 0f)
                headGradient!!.setLocalMatrix(matrix)
                headPaint.shader = headGradient
                headPaint.alpha = (headAlpha.coerceIn(0f, 1f) * 255f).toInt()
                canvas.drawRoundRect(headRect, radius, radius, headPaint)
            }
            canvas.restoreToCount(save)
        }
        if (drawCone && lightEnabled && (cone.alpha1 > 0.00001f || cone.alpha2 > 0.00001f)) {
            // The original uses physical thumb position for this threshold, including in RTL.
            val physicalFraction = if (rtl) 1f - p else p
            val startScale = if (physicalFraction > 0.2f) 1f else
                (physicalFraction / 0.21f).coerceIn(0f, 1f)
            val reduced = cone.copy(height = cone.height * startScale,
                falloff = cone.falloff * startScale)
            val save = canvas.save()
            canvas.translate(-300f, centerY - 225f)
            if (conic != null && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated) {
                conic.draw(canvas, width, physicalFraction, fillArgb, reduced, rtl)
            } else {
                val software = fallback ?: ClassicConicSoftwarePainter().also { fallback = it }
                software.draw(canvas, width, physicalFraction, fillArgb, reduced, rtl)
            }
            canvas.restoreToCount(save)
        }
    }

    fun release() { fallback?.release() }
}

@RequiresApi(33)
private class ClassicConicShaderPainter(context: Context) {
    private val shader = RuntimeShader(context.assets.open("classic_progress_conic.agsl")
        .bufferedReader().use { it.readText() })
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.shader = this@ClassicConicShaderPainter.shader
        blendMode = BlendMode.SCREEN
    }

    fun draw(canvas: Canvas, width: Float, physicalFraction: Float, argb: Int,
        frame: ClassicConeFrame, rtl: Boolean) {
        shader.setFloatUniform("u_resolution", width + 600f, 450f)
        val r = ((argb shr 16) and 255).toFloat()
        val g = ((argb shr 8) and 255).toFloat()
        val b = (argb and 255).toFloat()
        shader.setFloatUniform("u_startColor", r, g, b)
        shader.setFloatUniform("u_endColor", r, g, b)
        shader.setFloatUniform("u_progressHeightParams", physicalFraction, width,
            frame.height, frame.falloff)
        shader.setFloatUniform("u_basicParams", 3f, 1f, 2f, 0.2f)
        shader.setFloatUniform("u_alphaParams", frame.alpha1, frame.alpha2, frame.w0, 500f)
        shader.setFloatUniform("u_coneParams", frame.coneK, frame.coneK, 2f, 1f)
        shader.setFloatUniform("u_miscParams", 1f, if (rtl) 1f else 0f, 0f, 0f)
        canvas.drawRect(0f, 0f, width + 600f, 450f, paint)
    }
}

/** Same bloom equations sampled at low resolution for Android 7–12. */
private class ClassicConicSoftwarePainter {
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply {
        xfermode = PorterDuffXfermode(PorterDuff.Mode.SCREEN)
    }
    private var bitmap: Bitmap? = null
    private var pixels = IntArray(0)
    private val destination = RectF()

    fun draw(canvas: Canvas, width: Float, fraction: Float, argb: Int,
        frame: ClassicConeFrame, rtl: Boolean) {
        val rasterWidth = ((width + 600f) / 6f).toInt().coerceIn(1, 512)
        val rasterHeight = 75
        if (bitmap?.width != rasterWidth) {
            release()
            bitmap = Bitmap.createBitmap(rasterWidth, rasterHeight, Bitmap.Config.ARGB_8888)
            pixels = IntArray(rasterWidth * rasterHeight)
        }
        val fullWidth = width + 600f
        val spotX = 300f + fraction * width
        val maxHeight = 500f * frame.height
        val red = ((argb shr 16) and 255) / 255f
        val green = ((argb shr 8) and 255) / 255f
        val blue = (argb and 255) / 255f
        for (y in 0 until rasterHeight) for (x in 0 until rasterWidth) {
            val qx = ((x + 0.5f) * fullWidth / rasterWidth - spotX) * if (rtl) -1f else 1f
            val index = y * rasterWidth + x
            if (qx >= 0f || maxHeight <= 0f || frame.falloff <= 0f) {
                pixels[index] = 0
                continue
            }
            val xp = -qx
            val ay = abs((y + 0.5f) * 450f / rasterHeight - 225f)
            val w = frame.coneK * xp + frame.w0 * 500f
            val edge = 1f - smoothStep(0f, 1000f, ay - w)
            val radial = exp(-(ay / (w + 0.0001f)).pow(2))
            val axial = exp(-2f * xp / 500f)
            val hf = (xp / (maxHeight + 0.0001f)).coerceIn(0f, 1f)
            val intensity = edge * radial * axial * (1f - hf.pow(frame.falloff)) *
                exp(-max(0f, xp - maxHeight) / 500f * 3f) * smoothStep(0f, 15f, xp)
            val a1 = intensity * frame.alpha1
            val a2 = intensity * frame.alpha2
            val combinedAlpha = a1 * (1f - a2) + a2
            val percent = abs(a2 - a1) / max(combinedAlpha, 0.00001f)
            val r = blendedChannel(red * intensity, a1, a2, percent)
            val g = blendedChannel(green * intensity, a1, a2, percent)
            val b = blendedChannel(blue * intensity, a1, a2, percent)
            val alpha = max(combinedAlpha, max(r, max(g, b))).coerceIn(0f, 1f)
            // Android setPixels expects straight alpha; AGSL returns premultiplied channels.
            fun channel(value: Float) = if (alpha <= 0f) 0 else
                (value / alpha * 255f).toInt().coerceIn(0, 255)
            pixels[index] = ((alpha * 255f).toInt() shl 24) or
                (channel(r) shl 16) or (channel(g) shl 8) or channel(b)
        }
        bitmap!!.setPixels(pixels, 0, rasterWidth, 0, 0, rasterWidth, rasterHeight)
        destination.set(0f, 0f, fullWidth, 450f)
        canvas.drawBitmap(bitmap!!, null, destination, paint)
    }

    fun release() { bitmap?.recycle(); bitmap = null }
    private fun blendedChannel(base: Float, a1: Float, a2: Float, percent: Float): Float {
        var sum = min(1f, base * 2f)
        if (a1 >= a2) sum += (base - sum) * percent
        if (a2 >= a1) sum += (base - sum) * percent
        return sum.coerceIn(0f, 1f)
    }
    private fun smoothStep(start: Float, end: Float, x: Float): Float {
        val t = ((x - start) / (end - start)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }
}
