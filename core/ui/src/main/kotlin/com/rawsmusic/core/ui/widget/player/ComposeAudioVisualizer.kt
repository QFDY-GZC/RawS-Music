package com.rawsmusic.core.ui.widget.player

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

private const val MONO_BAND_COUNT = 112
private const val PARTICLE_COUNT = 280
private const val PARTICLE_TRAIL_HISTORY = 4

enum class AudioVisualizerStyle(val value: Int) {
    Spectrum(0),
    Particle(1),
    ParticleTrails(2),
    PulseRings(3),
    EnergyRibbon(4),
    Ripple(5);

    companion object {
        fun from(value: Int): AudioVisualizerStyle =
            entries.firstOrNull { it.value == value } ?: Spectrum
    }
}

enum class AudioVisualizerParticleColorMode(val value: Int) {
    White(0),
    Rainbow(1);

    companion object {
        fun from(value: Int): AudioVisualizerParticleColorMode =
            entries.firstOrNull { it.value == value } ?: White
    }
}

val LocalAudioVisualizerStyle = staticCompositionLocalOf { AudioVisualizerStyle.Spectrum }
val LocalAudioVisualizerStyleChange =
    staticCompositionLocalOf<(AudioVisualizerStyle) -> Unit> { {} }
val LocalAudioVisualizerParticleColorMode =
    staticCompositionLocalOf { AudioVisualizerParticleColorMode.White }

internal class DisplaySpectrumMotion {
    val levels = FloatArray(MONO_BAND_COUNT)
    var animationTimeSeconds: Float = 0f

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
        animationTimeSeconds = (animationTimeSeconds + dt) % 4096f
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

private class ParticleFieldLayout {
    val baseX = FloatArray(PARTICLE_COUNT)
    val depth = FloatArray(PARTICLE_COUNT)
    val floorOffsetDp = FloatArray(PARTICLE_COUNT)
    val radiusScale = FloatArray(PARTICLE_COUNT)
    val launchBias = FloatArray(PARTICLE_COUNT)
    val driftDirection = FloatArray(PARTICLE_COUNT)
    val band = IntArray(PARTICLE_COUNT)
    val heightDp = FloatArray(PARTICLE_COUNT)
    val velocityYDp = FloatArray(PARTICLE_COUNT)
    val offsetXDp = FloatArray(PARTICLE_COUNT)
    val velocityXDp = FloatArray(PARTICLE_COUNT)
    val rainbowArgb = IntArray(PARTICLE_COUNT)
    val trailHeightDp = FloatArray(PARTICLE_COUNT * PARTICLE_TRAIL_HISTORY)
    val trailOffsetXDp = FloatArray(PARTICLE_COUNT * PARTICLE_TRAIL_HISTORY)

    private val previousBandEnergy = FloatArray(MONO_BAND_COUNT)
    private var previousBass = 0f
    private var lastTimeSeconds = Float.NaN
    private val lastLaunchSeconds = FloatArray(PARTICLE_COUNT) { -10f }
    private var trailCursor = 0

    init {
        var seed = 0x5EED1234
        fun nextUnit(): Float {
            seed = seed * 1664525 + 1013904223
            return ((seed ushr 8) and 0x00FFFFFF) / 16777215f
        }
        for (index in 0 until PARTICLE_COUNT) {
            baseX[index] = nextUnit()
            depth[index] = 0.38f + nextUnit() * 0.62f
            floorOffsetDp[index] = nextUnit() * 4.2f
            radiusScale[index] = 0.70f + nextUnit() * 0.82f
            launchBias[index] = nextUnit()
            driftDirection[index] = nextUnit() * 2f - 1f
            band[index] = (baseX[index] * (MONO_BAND_COUNT - 1))
                .roundToInt()
                .coerceIn(0, MONO_BAND_COUNT - 1)
            rainbowArgb[index] = Color.hsv(
                hue = 8f + baseX[index] * 312f,
                saturation = 0.88f,
                value = 1f,
            ).toArgb()
        }
    }

    fun advance(timeSeconds: Float, levels: FloatArray, playing: Boolean) {
        val rawDt = if (lastTimeSeconds.isNaN() || timeSeconds < lastTimeSeconds) {
            1f / 60f
        } else {
            timeSeconds - lastTimeSeconds
        }
        lastTimeSeconds = timeSeconds
        val dt = rawDt.coerceIn(1f / 240f, 1f / 20f)

        var bass = 0f
        for (index in 0 until 18) bass += levels[index]
        bass = (bass / 18f).coerceIn(0f, 1f)
        val bassOnset = (bass - previousBass).coerceAtLeast(0f)
        previousBass = bass

        val gravityDpPerSecond2 = 560f
        val horizontalDrag = exp((-dt * 3.4f).toDouble()).toFloat()

        for (index in 0 until PARTICLE_COUNT) {
            val sourceBand = band[index]
            val previousIndex = (sourceBand - 1).coerceAtLeast(0)
            val nextIndex = (sourceBand + 1).coerceAtMost(MONO_BAND_COUNT - 1)
            val localEnergy = (
                levels[sourceBand] * 0.70f +
                    levels[previousIndex] * 0.15f +
                    levels[nextIndex] * 0.15f
                ).coerceIn(0f, 1f)
            val onset = (localEnergy - previousBandEnergy[sourceBand]).coerceAtLeast(0f)
            val resting = heightDp[index] <= 0.05f && velocityYDp[index] <= 0f
            val sinceLaunch = timeSeconds - lastLaunchSeconds[index]
            val threshold = 0.055f + launchBias[index] * 0.09f
            val localTrigger =
                localEnergy > threshold &&
                    (
                        onset > 0.014f + launchBias[index] * 0.026f ||
                            (sinceLaunch > 0.12f + launchBias[index] * 0.14f && localEnergy > 0.19f)
                        )
            val bassTrigger =
                sourceBand < 34 &&
                    bass > 0.11f &&
                    bassOnset > 0.025f + launchBias[index] * 0.035f

            if (playing && resting && (localTrigger || bassTrigger)) {
                val frequencyTaper = 1f - (sourceBand / (MONO_BAND_COUNT - 1f)) * 0.18f
                val jumpVelocity = (
                    64f +
                        224f * localEnergy.pow(0.68f) +
                        310f * onset +
                        if (sourceBand < 34) 72f * bass else 0f
                    ) * frequencyTaper * (0.78f + depth[index] * 0.32f)
                velocityYDp[index] = jumpVelocity.coerceIn(58f, 370f)
                velocityXDp[index] +=
                    driftDirection[index] * (10f + 56f * onset + 18f * bassOnset)
                lastLaunchSeconds[index] = timeSeconds
            } else if (
                playing &&
                !resting &&
                onset > 0.07f &&
                velocityYDp[index] > -80f
            ) {
                velocityYDp[index] += (34f + onset * 92f) * (0.7f + depth[index] * 0.3f)
                velocityXDp[index] += driftDirection[index] * onset * 20f
            }

            velocityYDp[index] -= gravityDpPerSecond2 * dt
            heightDp[index] += velocityYDp[index] * dt
            velocityXDp[index] *= horizontalDrag
            offsetXDp[index] += velocityXDp[index] * dt

            if (heightDp[index] <= 0f) {
                heightDp[index] = 0f
                if (velocityYDp[index] < 0f) velocityYDp[index] = 0f
                offsetXDp[index] *= 0.90f
            }
            if (offsetXDp[index] > 24f) {
                offsetXDp[index] = 24f
                velocityXDp[index] = -abs(velocityXDp[index]) * 0.45f
            } else if (offsetXDp[index] < -24f) {
                offsetXDp[index] = -24f
                velocityXDp[index] = abs(velocityXDp[index]) * 0.45f
            }
        }

        for (index in 0 until MONO_BAND_COUNT) {
            previousBandEnergy[index] = levels[index]
        }
        trailCursor = (trailCursor + 1) % PARTICLE_TRAIL_HISTORY
        val trailBase = trailCursor * PARTICLE_COUNT
        for (index in 0 until PARTICLE_COUNT) {
            trailHeightDp[trailBase + index] = heightDp[index]
            trailOffsetXDp[trailBase + index] = offsetXDp[index]
        }
    }

    fun trailHeight(index: Int, framesAgo: Int): Float {
        val slot = (trailCursor - framesAgo + PARTICLE_TRAIL_HISTORY) % PARTICLE_TRAIL_HISTORY
        return trailHeightDp[slot * PARTICLE_COUNT + index]
    }

    fun trailOffsetX(index: Int, framesAgo: Int): Float {
        val slot = (trailCursor - framesAgo + PARTICLE_TRAIL_HISTORY) % PARTICLE_TRAIL_HISTORY
        return trailOffsetXDp[slot * PARTICLE_COUNT + index]
    }

    fun hasMotion(): Boolean {
        for (index in 0 until PARTICLE_COUNT) {
            if (
                heightDp[index] > 0.15f ||
                abs(velocityYDp[index]) > 1f ||
                abs(velocityXDp[index]) > 1f
            ) {
                return true
            }
        }
        return false
    }
}

private const val PULSE_RING_COUNT = 7

private class PulseRingState {
    val ageSeconds = FloatArray(PULSE_RING_COUNT) { -1f }
    val strength = FloatArray(PULSE_RING_COUNT)
    val originX = FloatArray(PULSE_RING_COUNT) { 0.5f }
    val colorArgb = IntArray(PULSE_RING_COUNT) { Color.White.toArgb() }

    private var previousBass = 0f
    private var lastTimeSeconds = Float.NaN
    private var lastSpawnTimeSeconds = -10f
    private var cursor = 0

    fun advance(timeSeconds: Float, levels: FloatArray, playing: Boolean) {
        val rawDt = if (lastTimeSeconds.isNaN() || timeSeconds < lastTimeSeconds) {
            1f / 60f
        } else {
            timeSeconds - lastTimeSeconds
        }
        lastTimeSeconds = timeSeconds
        val dt = rawDt.coerceIn(1f / 240f, 1f / 20f)
        for (index in 0 until PULSE_RING_COUNT) {
            if (ageSeconds[index] >= 0f) {
                ageSeconds[index] += dt
                if (ageSeconds[index] > 1.05f) ageSeconds[index] = -1f
            }
        }
        if (!playing) {
            previousBass *= 0.92f
            return
        }

        var bass = 0f
        var strongestBand = 0
        var strongest = 0f
        val lowCount = min(28, levels.size)
        for (index in 0 until lowCount) {
            val level = levels[index].coerceIn(0f, 1f)
            bass += level
            if (level > strongest) {
                strongest = level
                strongestBand = index
            }
        }
        bass = if (lowCount > 0) bass / lowCount else 0f
        val onset = (bass - previousBass).coerceAtLeast(0f)
        previousBass = bass
        if (
            bass > 0.10f &&
            onset > 0.018f &&
            timeSeconds - lastSpawnTimeSeconds > 0.085f
        ) {
            val slot = cursor
            cursor = (cursor + 1) % PULSE_RING_COUNT
            ageSeconds[slot] = 0f
            strength[slot] = (0.32f + bass * 0.90f + onset * 2.8f).coerceIn(0.32f, 1f)
            originX[slot] = (0.22f + strongestBand / 27f * 0.56f).coerceIn(0.18f, 0.82f)
            colorArgb[slot] = Color.hsv(
                hue = 8f + strongestBand / 27f * 78f,
                saturation = 0.88f,
                value = 1f,
            ).toArgb()
            lastSpawnTimeSeconds = timeSeconds
        }
    }

    fun hasMotion(): Boolean = ageSeconds.any { it >= 0f }
}

private const val RIPPLE_EVENT_COUNT = 10

private class RippleFieldState {
    val ageSeconds = FloatArray(RIPPLE_EVENT_COUNT) { -1f }
    val strength = FloatArray(RIPPLE_EVENT_COUNT)
    val originX = FloatArray(RIPPLE_EVENT_COUNT) { 0.5f }
    val colorArgb = IntArray(RIPPLE_EVENT_COUNT) { Color.White.toArgb() }

    private val previousBandEnergy = FloatArray(MONO_BAND_COUNT)
    private var lastTimeSeconds = Float.NaN
    private var lastSpawnTimeSeconds = -10f
    private var cursor = 0

    fun advance(timeSeconds: Float, levels: FloatArray, playing: Boolean) {
        val rawDt = if (lastTimeSeconds.isNaN() || timeSeconds < lastTimeSeconds) {
            1f / 60f
        } else {
            timeSeconds - lastTimeSeconds
        }
        lastTimeSeconds = timeSeconds
        val dt = rawDt.coerceIn(1f / 240f, 1f / 20f)
        for (index in 0 until RIPPLE_EVENT_COUNT) {
            if (ageSeconds[index] >= 0f) {
                ageSeconds[index] += dt
                if (ageSeconds[index] > 0.82f) ageSeconds[index] = -1f
            }
        }
        if (!playing) {
            for (index in 0 until min(levels.size, MONO_BAND_COUNT)) {
                previousBandEnergy[index] = levels[index]
            }
            return
        }

        var strongestOnset = 0f
        var strongestBand = 0
        val count = min(levels.size, MONO_BAND_COUNT)
        for (index in 0 until count) {
            val level = levels[index].coerceIn(0f, 1f)
            val onset = (level - previousBandEnergy[index]).coerceAtLeast(0f)
            if (onset > strongestOnset && level > 0.08f) {
                strongestOnset = onset
                strongestBand = index
            }
            previousBandEnergy[index] = level
        }
        if (
            strongestOnset > 0.032f &&
            timeSeconds - lastSpawnTimeSeconds > 0.070f
        ) {
            val slot = cursor
            cursor = (cursor + 1) % RIPPLE_EVENT_COUNT
            ageSeconds[slot] = 0f
            strength[slot] = (0.30f + strongestOnset * 4.2f).coerceIn(0.30f, 1f)
            originX[slot] = strongestBand / (MONO_BAND_COUNT - 1f)
            colorArgb[slot] = Color.hsv(
                hue = 8f + originX[slot] * 312f,
                saturation = 0.86f,
                value = 1f,
            ).toArgb()
            lastSpawnTimeSeconds = timeSeconds
        }
    }

    fun hasMotion(): Boolean = ageSeconds.any { it >= 0f }
}

/**
 * Direct draw-frame owner for one visualizer surface. The old path incremented Snapshot state every
 * display frame just to invalidate Canvas, forcing Recomposer work at 90/120 Hz. Keep spectrum
 * motion as plain render data and invalidate only this DrawModifierNode from Choreographer.
 */
private class AudioVisualizerFrameNode(
    var motion: DisplaySpectrumMotion,
    var spectrum: FloatArray,
    var spectrumState: State<FloatArray>?,
    var visible: Boolean,
    var playing: Boolean,
    var extraAnimationActive: (() -> Boolean)?,
) : Modifier.Node(), DrawModifierNode, Choreographer.FrameCallback {
    override val shouldAutoInvalidate: Boolean
        get() = false

    private var choreographer: Choreographer? = null
    private var callbackPosted = false
    private var lastFrameNs = 0L

    override fun onAttach() {
        super.onAttach()
        motion.updateTarget(currentSpectrum(), enabled = visible && playing)
        invalidateDraw()
        ensureFrameCallback()
    }

    override fun onDetach() {
        cancelFrameCallback()
        super.onDetach()
    }

    fun update(
        motion: DisplaySpectrumMotion,
        spectrum: FloatArray,
        spectrumState: State<FloatArray>?,
        visible: Boolean,
        playing: Boolean,
        extraAnimationActive: (() -> Boolean)?,
    ) {
        this.motion = motion
        this.spectrum = spectrum
        this.spectrumState = spectrumState
        this.visible = visible
        this.playing = playing
        this.extraAnimationActive = extraAnimationActive
        motion.updateTarget(currentSpectrum(), enabled = visible && playing)
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
        motion.updateTarget(currentSpectrum(), enabled = visible && playing)
        motion.advance(deltaSeconds, playing)
        invalidateDraw()
        ensureFrameCallback()
    }

    private fun shouldRun(): Boolean =
        (visible && playing) ||
            motion.hasVisibleEnergy() ||
            (extraAnimationActive?.invoke() == true)

    private fun currentSpectrum(): FloatArray = spectrumState?.value ?: spectrum

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
    val spectrumState: State<FloatArray>?,
    val visible: Boolean,
    val playing: Boolean,
    val extraAnimationActive: (() -> Boolean)?,
) : ModifierNodeElement<AudioVisualizerFrameNode>() {
    override fun create() =
        AudioVisualizerFrameNode(
            motion,
            spectrum,
            spectrumState,
            visible,
            playing,
            extraAnimationActive,
        )
    override fun update(node: AudioVisualizerFrameNode) =
        node.update(
            motion,
            spectrum,
            spectrumState,
            visible,
            playing,
            extraAnimationActive,
        )
    override fun InspectorInfo.inspectableProperties() { name = "audioVisualizerFrame" }
}

internal fun Modifier.audioVisualizerFrame(
    motion: DisplaySpectrumMotion,
    spectrum: FloatArray,
    visible: Boolean,
    playing: Boolean,
    spectrumState: State<FloatArray>? = null,
    extraAnimationActive: (() -> Boolean)? = null,
): Modifier = this then AudioVisualizerFrameElement(
    motion = motion,
    spectrum = spectrum,
    spectrumState = spectrumState,
    visible = visible,
    playing = playing,
    extraAnimationActive = extraAnimationActive,
)

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

/**
 * Bottom particle field driven by the same smoothed 112-band spectrum as the original visualizer.
 *
 * Particle topology is allocated once. The display hot path only samples Float arrays and issues
 * Canvas draw calls, so 90/120 Hz animation stays outside Compose Snapshot/recomposition.
 */
@Composable
fun BottomParticleSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    showTrails: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(
            durationMillis = if (visible) 240 else 170,
            easing = FastOutSlowInEasing,
        ),
        label = "bottom-particle-visualizer",
    )
    val motion = remember { DisplaySpectrumMotion() }
    val particles = remember { ParticleFieldLayout() }
    val particleColorMode = LocalAudioVisualizerParticleColorMode.current

    if (
        reveal <= 0.001f &&
        !visible &&
        !motion.hasVisibleEnergy() &&
        !particles.hasMotion()
    ) {
        return
    }

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .audioVisualizerFrame(
                motion = motion,
                spectrum = spectrum,
                visible = visible,
                playing = isPlaying,
                spectrumState = spectrumState,
                extraAnimationActive = particles::hasMotion,
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas

        particles.advance(
            timeSeconds = motion.animationTimeSeconds,
            levels = motion.levels,
            playing = isPlaying,
        )
        var bassSum = 0f
        for (index in 0 until 18) bassSum += motion.levels[index]
        val bass = (bassSum / 18f).coerceIn(0f, 1f)
        val baseRadius = 0.68f * density
        val floorY = size.height - 5f * density

        for (index in 0 until PARTICLE_COUNT) {
            val level = motion.levels[particles.band[index]].coerceIn(0f, 1f)
            val depth = particles.depth[index]
            val x = (
                particles.baseX[index] * size.width +
                    particles.offsetXDp[index] * density
                ).coerceIn(0f, size.width)
            val y = (
                floorY -
                    (particles.floorOffsetDp[index] + particles.heightDp[index]) * density
                ).coerceIn(0f, floorY)
            val shaped = sqrt(level)
            val airborne = (particles.heightDp[index] / 96f).coerceIn(0f, 1f)
            val radius = baseRadius *
                particles.radiusScale[index] *
                (0.88f + shaped * 1.10f + airborne * 0.10f)
            val alpha = (
                0.10f +
                    depth * 0.12f +
                    shaped * 0.38f +
                    bass * 0.045f -
                    airborne * 0.05f
                ).coerceIn(0.08f, 0.68f)
            val glowColor = if (particleColorMode == AudioVisualizerParticleColorMode.Rainbow) {
                Color(particles.rainbowArgb[index])
            } else {
                Color.White
            }
            val glowStrength = (
                shaped * 0.82f +
                    bass * 0.10f +
                    airborne * 0.08f
                ).coerceIn(0f, 1f)

            if (
                showTrails &&
                particles.heightDp[index] > 4f &&
                (glowStrength > 0.12f || abs(particles.velocityYDp[index]) > 42f)
            ) {
                for (framesAgo in PARTICLE_TRAIL_HISTORY - 1 downTo 1) {
                    val trailAlpha =
                        alpha * (0.018f + (PARTICLE_TRAIL_HISTORY - framesAgo) * 0.020f)
                    val trailX = (
                        particles.baseX[index] * size.width +
                            particles.trailOffsetX(index, framesAgo) * density
                        ).coerceIn(0f, size.width)
                    val trailY = (
                        floorY -
                            (
                                particles.floorOffsetDp[index] +
                                    particles.trailHeight(index, framesAgo)
                                ) * density
                        ).coerceIn(0f, floorY)
                    drawCircle(
                        color = glowColor.copy(alpha = trailAlpha),
                        radius = radius * (0.66f + framesAgo * 0.07f),
                        center = Offset(trailX, trailY),
                        blendMode = BlendMode.Plus,
                    )
                }
            }

            if (glowStrength > 0.52f) {
                drawCircle(
                    color = glowColor.copy(alpha = alpha * 0.018f * glowStrength),
                    radius = radius * 5.20f,
                    center = Offset(x, y),
                    blendMode = BlendMode.Plus,
                )
            }
            if (glowStrength > 0.30f) {
                drawCircle(
                    color = glowColor.copy(alpha = alpha * 0.050f * glowStrength),
                    radius = radius * 3.35f,
                    center = Offset(x, y),
                    blendMode = BlendMode.Plus,
                )
            }
            if (glowStrength > 0.13f) {
                drawCircle(
                    color = glowColor.copy(alpha = alpha * 0.115f * glowStrength),
                    radius = radius * 1.90f,
                    center = Offset(x, y),
                    blendMode = BlendMode.Plus,
                )
            }
            val coreColor = if (particleColorMode == AudioVisualizerParticleColorMode.Rainbow) {
                lerp(
                    glowColor,
                    Color.White,
                    ((glowStrength - 0.34f) / 0.54f).coerceIn(0f, 1f),
                )
            } else {
                Color.White
            }
            drawCircle(
                color = coreColor.copy(alpha = alpha),
                radius = radius,
                center = Offset(x, y),
                blendMode = BlendMode.SrcOver,
            )
        }
    }
}

@Composable
fun PulseRingSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 180 else 150),
        label = "pulse-ring-visualizer",
    )
    val motion = remember { DisplaySpectrumMotion() }
    val rings = remember { PulseRingState() }
    val colorMode = LocalAudioVisualizerParticleColorMode.current

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy() && !rings.hasMotion()) return

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .audioVisualizerFrame(
                motion = motion,
                spectrum = spectrum,
                spectrumState = spectrumState,
                visible = visible,
                playing = isPlaying,
                extraAnimationActive = rings::hasMotion,
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        rings.advance(motion.animationTimeSeconds, motion.levels, isPlaying)
        val centerY = size.height - 7f * density
        for (index in 0 until PULSE_RING_COUNT) {
            val age = rings.ageSeconds[index]
            if (age < 0f) continue
            val progress = (age / 1.05f).coerceIn(0f, 1f)
            val eased = 1f - (1f - progress) * (1f - progress)
            val fade = (1f - progress).pow(1.7f)
            val strength = rings.strength[index]
            val radius = 10f * density + size.width * (0.16f + 0.60f * eased)
            val center = Offset(rings.originX[index] * size.width, centerY)
            val ringColor = if (colorMode == AudioVisualizerParticleColorMode.Rainbow) {
                Color(rings.colorArgb[index])
            } else {
                Color.White
            }
            drawCircle(
                color = ringColor.copy(alpha = 0.035f * fade * strength),
                radius = radius,
                center = center,
                style = Stroke(width = (7.0f - progress * 3.0f) * density),
                blendMode = BlendMode.Plus,
            )
            drawCircle(
                color = lerp(
                    ringColor,
                    Color.White,
                    (strength * 0.42f).coerceIn(0f, 0.42f),
                ).copy(alpha = 0.26f * fade * strength),
                radius = radius,
                center = center,
                style = Stroke(width = (1.15f + strength * 0.8f) * density),
            )
        }
    }
}

@Composable
fun EnergyRibbonSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 220 else 160),
        label = "energy-ribbon-visualizer",
    )
    val motion = remember { DisplaySpectrumMotion() }
    val path = remember { Path() }
    val colorMode = LocalAudioVisualizerParticleColorMode.current
    val rainbowBrush = remember {
        Brush.horizontalGradient(
            listOf(
                Color.hsv(8f, 0.86f, 1f),
                Color.hsv(55f, 0.84f, 1f),
                Color.hsv(126f, 0.82f, 1f),
                Color.hsv(190f, 0.84f, 1f),
                Color.hsv(252f, 0.82f, 1f),
                Color.hsv(320f, 0.84f, 1f),
            )
        )
    }

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy()) return

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .audioVisualizerFrame(
                motion = motion,
                spectrum = spectrum,
                spectrumState = spectrumState,
                visible = visible,
                playing = isPlaying,
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        val samples = 64
        val baseline = size.height * 0.79f
        val amplitude = size.height * 0.56f
        val time = motion.animationTimeSeconds
        path.reset()
        for (sample in 0 until samples) {
            val fraction = sample / (samples - 1f)
            val band = (fraction * (MONO_BAND_COUNT - 1)).roundToInt()
                .coerceIn(0, MONO_BAND_COUNT - 1)
            val level = motion.levels[band].coerceIn(0f, 1f)
            val shaped = level.pow(0.72f)
            val microMotion = sin(time * 2.0f + sample * 0.31f) *
                2.0f * density * shaped
            val x = fraction * size.width
            val y = baseline - shaped * amplitude - microMotion
            if (sample == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }

        if (colorMode == AudioVisualizerParticleColorMode.Rainbow) {
            drawPath(
                path = path,
                brush = rainbowBrush,
                alpha = 0.055f,
                style = Stroke(width = 10f * density, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
            drawPath(
                path = path,
                brush = rainbowBrush,
                alpha = 0.32f,
                style = Stroke(width = 3.8f * density, cap = StrokeCap.Round),
            )
        } else {
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.045f),
                style = Stroke(width = 10f * density, cap = StrokeCap.Round),
                blendMode = BlendMode.Plus,
            )
            drawPath(
                path = path,
                color = Color.White.copy(alpha = 0.42f),
                style = Stroke(width = 3.2f * density, cap = StrokeCap.Round),
            )
        }
        drawPath(
            path = path,
            color = Color.White.copy(alpha = 0.60f),
            style = Stroke(width = 0.95f * density, cap = StrokeCap.Round),
        )
    }
}

@Composable
fun RippleSpectrumOverlay(
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    val reveal by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(if (visible) 180 else 150),
        label = "ripple-visualizer",
    )
    val motion = remember { DisplaySpectrumMotion() }
    val ripples = remember { RippleFieldState() }
    val colorMode = LocalAudioVisualizerParticleColorMode.current

    if (reveal <= 0.001f && !visible && !motion.hasVisibleEnergy() && !ripples.hasMotion()) return

    Canvas(
        modifier = modifier
            .graphicsLayer { alpha = reveal }
            .audioVisualizerFrame(
                motion = motion,
                spectrum = spectrum,
                spectrumState = spectrumState,
                visible = visible,
                playing = isPlaying,
                extraAnimationActive = ripples::hasMotion,
            ),
    ) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        ripples.advance(motion.animationTimeSeconds, motion.levels, isPlaying)
        val floorY = size.height - 8f * density
        for (index in 0 until RIPPLE_EVENT_COUNT) {
            val age = ripples.ageSeconds[index]
            if (age < 0f) continue
            val progress = (age / 0.82f).coerceIn(0f, 1f)
            val fade = (1f - progress).pow(1.55f)
            val strength = ripples.strength[index]
            val center = Offset(
                x = ripples.originX[index] * size.width,
                y = floorY - progress * 18f * density,
            )
            val radius = (8f + progress * 92f) * density * (0.76f + strength * 0.34f)
            val rippleColor = if (colorMode == AudioVisualizerParticleColorMode.Rainbow) {
                Color(ripples.colorArgb[index])
            } else {
                Color.White
            }
            drawCircle(
                color = rippleColor.copy(alpha = 0.045f * fade * strength),
                radius = radius * 1.04f,
                center = center,
                style = Stroke(width = 5.2f * density),
                blendMode = BlendMode.Plus,
            )
            drawCircle(
                color = rippleColor.copy(alpha = 0.31f * fade * strength),
                radius = radius,
                center = center,
                style = Stroke(width = 1.1f * density),
            )
        }
    }
}

@Composable
fun SharedRealtimeVisualizerOverlay(
    style: AudioVisualizerStyle,
    spectrum: FloatArray,
    spectrumState: State<FloatArray>? = null,
    visible: Boolean,
    isPlaying: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier) {
        BottomParticleSpectrumOverlay(
            spectrum = spectrum,
            spectrumState = spectrumState,
            visible = visible &&
                (style == AudioVisualizerStyle.Particle ||
                    style == AudioVisualizerStyle.ParticleTrails),
            isPlaying = isPlaying,
            showTrails = style == AudioVisualizerStyle.ParticleTrails,
            modifier = Modifier.matchParentSize(),
        )
        PulseRingSpectrumOverlay(
            spectrum = spectrum,
            spectrumState = spectrumState,
            visible = visible && style == AudioVisualizerStyle.PulseRings,
            isPlaying = isPlaying,
            modifier = Modifier.matchParentSize(),
        )
        EnergyRibbonSpectrumOverlay(
            spectrum = spectrum,
            spectrumState = spectrumState,
            visible = visible && style == AudioVisualizerStyle.EnergyRibbon,
            isPlaying = isPlaying,
            modifier = Modifier.matchParentSize(),
        )
        RippleSpectrumOverlay(
            spectrum = spectrum,
            spectrumState = spectrumState,
            visible = visible && style == AudioVisualizerStyle.Ripple,
            isPlaying = isPlaying,
            modifier = Modifier.matchParentSize(),
        )
    }
}
