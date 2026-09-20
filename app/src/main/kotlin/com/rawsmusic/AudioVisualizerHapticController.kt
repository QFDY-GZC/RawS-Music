package com.rawsmusic

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Conservative first-pass music-reactive haptics.
 *
 * The spectrum callback already runs only while the player visualizer owns realtime PCM analysis.
 * This controller adds adaptive energy/onset gating plus a hard debounce so the vibrator never
 * follows the 90/120 Hz UI clock directly.
 */
internal class AudioVisualizerHapticController(
    context: Context,
) {
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private var baselineEnergy = 0f
    private var previousEnergy = 0f
    private var previousBass = 0f
    private var lastPulseElapsedMs = 0L

    fun onSpectrum(
        spectrum: FloatArray,
        enabled: Boolean,
    ) {
        if (!enabled || spectrum.isEmpty()) {
            resetEnvelopeOnly()
            return
        }
        val targetVibrator = vibrator ?: return
        if (!targetVibrator.hasVibrator()) return

        var squareSum = 0f
        var bassSum = 0f
        val count = spectrum.size
        val bassCount = minOf(20, count)
        for (index in 0 until count) {
            val value = spectrum[index].coerceIn(0f, 1f)
            squareSum += value * value
            if (index < bassCount) bassSum += value
        }
        val overallRms = sqrt(squareSum / count.coerceAtLeast(1))
        val bass = if (bassCount > 0) bassSum / bassCount else 0f
        val energy = (bass * 0.70f + overallRms * 0.30f).coerceIn(0f, 1f)
        val onset = (energy - previousEnergy).coerceAtLeast(0f)
        val bassOnset = (bass - previousBass).coerceAtLeast(0f)
        previousEnergy = energy
        previousBass = bass

        val baselineRate = if (energy < baselineEnergy) 0.08f else 0.012f
        baselineEnergy += (energy - baselineEnergy) * baselineRate

        val adaptiveThreshold = max(0.145f, baselineEnergy * 1.48f + 0.018f)
        val pronouncedTransient = onset > 0.018f || bassOnset > 0.024f
        val veryStrongEnergy = energy > max(0.34f, baselineEnergy * 2.05f)
        if (energy < adaptiveThreshold || (!pronouncedTransient && !veryStrongEnergy)) return

        val now = SystemClock.elapsedRealtime()
        val minimumGapMs = if (veryStrongEnergy) 82L else 118L
        if (now - lastPulseElapsedMs < minimumGapMs) return
        lastPulseElapsedMs = now

        val normalized = (
            (energy - adaptiveThreshold) /
                (1f - adaptiveThreshold).coerceAtLeast(0.05f)
            ).coerceIn(0f, 1f)
        val transientBoost = (onset * 2.8f + bassOnset * 2.2f).coerceIn(0f, 0.48f)
        val strength = (normalized * 0.72f + transientBoost).coerceIn(0f, 1f)
        val durationMs = (7L + (strength * 10f).toLong()).coerceIn(7L, 17L)
        val amplitude = (42 + (strength * 142f).toInt()).coerceIn(42, 184)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            targetVibrator.vibrate(
                VibrationEffect.createOneShot(
                    durationMs,
                    amplitude,
                )
            )
        } else {
            @Suppress("DEPRECATION")
            targetVibrator.vibrate(durationMs)
        }
    }

    fun reset() {
        baselineEnergy = 0f
        previousEnergy = 0f
        previousBass = 0f
        lastPulseElapsedMs = 0L
    }

    private fun resetEnvelopeOnly() {
        previousEnergy = 0f
        previousBass = 0f
        baselineEnergy *= 0.92f
    }
}
