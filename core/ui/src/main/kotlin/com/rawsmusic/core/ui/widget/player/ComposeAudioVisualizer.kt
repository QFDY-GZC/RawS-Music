package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import android.view.Choreographer
import androidx.compose.ui.graphics.graphicsLayer
import kotlin.math.ceil
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

private const val MONO_BAND_COUNT = 112

private class DisplaySpectrumMotion {
    val levels = FloatArray(MONO_BAND_COUNT)

    private val targets = FloatArray(MONO_BAND_COUNT)
    private val spatialScratch = FloatArray(MONO_BAND_COUNT)

    fun updateTarget(source: FloatArray, enabled: Boolean) {
        val count = if (enabled) min(source.size, targets.size) else 0
        for (index in targets.indices) {
            val raw = if (index < count) source[index].coerceIn(0f, 1f) else 0f
            // Keep the display stable between adjacent logarithmic bins without hiding attacks.
            val previous = if (index > 0 && index - 1 < count) source[index - 1].coerceIn(0f, 1f) else raw
            val next = if (index + 1 < count) source[index + 1].coerceIn(0f, 1f) else raw
            spatialScratch[index] = (raw * 0.78f + previous * 0.11f + next * 0.11f)
                .coerceIn(0f, 1f)
        }
        spatialScratch.copyInto(targets)
    }

    fun advance(deltaSeconds: Float, playing: Boolean) {
        val dt = deltaSeconds.coerceIn(1f / 240f, 1f / 20f)
        val attack = 1f - exp((-dt * 62f).toDouble()).toFloat()
        val releaseRate = if (playing) 28f else 8f
        val release = 1f - exp((-dt * releaseRate).toDouble()).toFloat()

        for (index in levels.indices) {
            val target = targets[index]
            val level = levels[index]
            val response = if (target > level) attack else release
            levels[index] = (level + (target - level) * response).coerceIn(0f, 1f)
        }
    }

    fun hasVisibleEnergy(): Boolean = levels.any { it > 0.002f }
}

/**
 * Direct draw-frame owner for one visualizer surface. The old path incremented Snapshot state every
 * display frame just to invalidate Canvas, forcing Recomposer work at 90/120 Hz. Keep spectrum
 * motion as plain render data and invalidate only this DrawModifierNode from Choreographer.
 */
private class AudioVisualizerFrameNode(
    var motion: DisplaySpectrumMotion,
    var spectrum: FloatArray,
    var visible: Boolean,
    var playing: Boolean,
) : Modifier.Node(), DrawModifierNode, Choreographer.FrameCallback {
    override val shouldAutoInvalidate: Boolean
        get() = false

    private var choreographer: Choreographer? = null
    private var callbackPosted = false
    private var lastFrameNs = 0L

    override fun onAttach() {
        super.onAttach()
        motion.updateTarget(spectrum, enabled = visible && playing)
        invalidateDraw()
        ensureFrameCallback()
    }

    override fun onDetach() {
        cancelFrameCallback()
        super.onDetach()
    }

    fun update(motion: DisplaySpectrumMotion, spectrum: FloatArray, visible: Boolean, playing: Boolean) {
        this.motion = motion
        this.spectrum = spectrum
        this.visible = visible
        this.playing = playing
        motion.updateTarget(spectrum, enabled = visible && playing)
        invalidateDraw()
        if (shouldRun()) ensureFrameCallback() else cancelFrameCallback()
    }

    override fun ContentDrawScope.draw() = drawContent()

    override fun doFrame(frameTimeNanos: Long) {
        callbackPosted = false
        if (!isAttached || !shouldRun()) return
        val deltaSeconds = if (lastFrameNs == 0L) {
            1f / 60f
        } else {
            ((frameTimeNanos - lastFrameNs) / 1_000_000_000f).coerceIn(1f / 240f, 1f / 20f)
        }
        lastFrameNs = frameTimeNanos
        // The FFT buffer is intentionally plain mutable render data. Re-sample it on the
        // Choreographer clock so a stable FloatArray reference does not require a Snapshot write.
        // A paused stream targets zero and releases to rest instead of keeping a 120 Hz loop alive.
        motion.updateTarget(spectrum, enabled = visible && playing)
        motion.advance(deltaSeconds, playing)
        invalidateDraw()
        ensureFrameCallback()
    }

    private fun shouldRun(): Boolean = (visible && playing) || motion.hasVisibleEnergy()

    private fun ensureFrameCallback() {
        if (!isAttached || callbackPosted || !shouldRun()) return
        val scheduler = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        callbackPosted = true
        scheduler.postFrameCallback(this)
    }

    private fun cancelFrameCallback() {
        if (callbackPosted) {
            choreographer?.removeFrameCallback(this)
            callbackPosted = false
        }
        lastFrameNs = 0L
    }
}

private data class AudioVisualizerFrameElement(
    val motion: DisplaySpectrumMotion,
    val spectrum: FloatArray,
    val visible: Boolean,
    val playing: Boolean,
) : ModifierNodeElement<AudioVisualizerFrameNode>() {
    override fun create() = AudioVisualizerFrameNode(motion, spectrum, visible, playing)
    override fun update(node: AudioVisualizerFrameNode) = node.update(motion, spectrum, visible, playing)
    override fun InspectorInfo.inspectableProperties() { name = "audioVisualizerFrame" }
}

private fun Modifier.audioVisualizerFrame(
    motion: DisplaySpectrumMotion,
    spectrum: FloatArray,
    visible: Boolean,
    playing: Boolean,
): Modifier = this then AudioVisualizerFrameElement(motion, spectrum, visible, playing)

enum class AudioVisualizerLayer {
    BehindArtwork,
    Foreground
}

/**
 * Mirrored inverted FFT presentation.
 *
 * Native input is a single mono FFT. The same logarithmic spectrum is mirrored from the centre
 * toward both sides and each column expands above and below one horizontal axis. Rendering follows
 * the display Choreographer, so 90/120 Hz devices interpolate smoothly between native snapshots.
 */
@Composable
fun AlbumArtworkSpectrumOverlay(
    spectrum: FloatArray,
    visible: Boolean,
    isPlaying: Boolean,
    layer: AudioVisualizerLayer,
    modifier: Modifier = Modifier
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 260 else 170,
            easing = FastOutSlowInEasing
        ),
        label = "album-spectrum-layer"
    )
    val motion = remember { DisplaySpectrumMotion() }

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy()) return

    Box(
        modifier = modifier.graphicsLayer { alpha = reveal }
    ) {
        Canvas(
            modifier = Modifier
                .matchParentSize()
                .audioVisualizerFrame(
                    motion = motion,
                    spectrum = spectrum,
                    visible = visible,
                    playing = isPlaying,
                )
        ) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas

            val foreground = layer == AudioVisualizerLayer.Foreground

            val widthDp = size.width / density
            val barCount = (widthDp / if (foreground) 3.2f else 3.0f)
                .roundToInt()
                .coerceIn(72, MONO_BAND_COUNT)
            val horizontalInset = 0f
            val availableWidth = size.width
            if (availableWidth <= 0f) return@Canvas

            val slotWidth = availableWidth / barCount
            val barWidth = (slotWidth * if (foreground) 0.34f else 0.40f)
                .coerceIn(0.55f * density, 1.15f * density)
            val haloWidth = (barWidth * 1.32f).coerceAtMost(slotWidth * 0.58f)
            val coreRadius = CornerRadius(barWidth * 0.5f, barWidth * 0.5f)
            val haloRadius = CornerRadius(haloWidth * 0.5f, haloWidth * 0.5f)
            val axisY = size.height * if (foreground) 0.535f else 0.555f
            val maximumHalfHeight = size.height * if (foreground) 0.465f else 0.42f
            val minimumHalfHeight = (if (foreground) 1.0f else 1.35f) * density
            val layerAlpha = if (foreground) 0.96f else 0.72f
            val pausedAlpha = if (isPlaying) 1f else 0.86f
            val totalAlpha = layerAlpha * pausedAlpha

            val coreBrush = Brush.verticalGradient(
                0.00f to Color.White.copy(alpha = 0.70f * totalAlpha),
                0.37f to Color.White.copy(alpha = 0.88f * totalAlpha),
                0.50f to Color.White.copy(alpha = 0.98f * totalAlpha),
                0.63f to Color.White.copy(alpha = 0.88f * totalAlpha),
                1.00f to Color.White.copy(alpha = 0.70f * totalAlpha),
                startY = axisY - maximumHalfHeight,
                endY = axisY + maximumHalfHeight
            )
            val haloColor = Color.White.copy(alpha = 0.07f * totalAlpha)

            fun sampledValue(displayBand: Int): Float {
                val sourceStart = floor(displayBand * MONO_BAND_COUNT.toFloat() / barCount)
                    .toInt()
                    .coerceIn(0, MONO_BAND_COUNT - 1)
                val sourceEnd = ceil((displayBand + 1) * MONO_BAND_COUNT.toFloat() / barCount)
                    .toInt()
                    .coerceIn(sourceStart + 1, MONO_BAND_COUNT)
                var maximum = 0f
                var squareSum = 0f
                var count = 0
                for (sourceBand in sourceStart until sourceEnd) {
                    val value = motion.levels[sourceBand].coerceIn(0f, 1f)
                    maximum = max(maximum, value)
                    squareSum += value * value
                    count++
                }
                val rms = if (count > 0) sqrt(squareSum / count) else 0f
                return (maximum * 0.76f + rms * 0.24f).coerceIn(0f, 1f)
            }

            fun drawSpectrumBar(x: Float, level: Float) {
                val gated = ((level - 0.006f) / 0.994f).coerceIn(0f, 1f)
                val shaped = gated.pow(0.96f)
                val halfHeight = minimumHalfHeight + maximumHalfHeight * shaped
                val top = axisY - halfHeight
                val fullHeight = halfHeight * 2f

                if (shaped > 0.01f) {
                    drawRoundRect(
                        color = haloColor,
                        topLeft = Offset(x - haloWidth * 0.5f, top - 0.5f * density),
                        size = Size(haloWidth, fullHeight + density),
                        cornerRadius = haloRadius
                    )
                }
                drawRoundRect(
                    brush = coreBrush,
                    topLeft = Offset(x - barWidth * 0.5f, top),
                    size = Size(barWidth, fullHeight),
                    cornerRadius = coreRadius
                )
            }

            // Native bands are already ordered logarithmically from 25 Hz to 20 kHz.
            for (band in 0 until barCount) {
                val level = sampledValue(band)
                val x = (band + 0.5f) * slotWidth
                drawSpectrumBar(x, level)
            }

            drawLine(
                color = Color.White.copy(alpha = if (foreground) 0.16f else 0.09f),
                start = Offset(horizontalInset, axisY),
                end = Offset(size.width - horizontalInset, axisY),
                strokeWidth = 0.7f * density,
                cap = StrokeCap.Round
            )
        }

    }
}
