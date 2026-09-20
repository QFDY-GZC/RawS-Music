package com.rawsmusic.core.ui.widget

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Broad energy + beat estimate for the MiniPlayer progress effect.
 *
 * Energy and tempo are deliberately separate signals. The normalized waveform decides whether a
 * moment is energetic; BPM is only used to phase-lock a small part of the visual pulse. That keeps
 * quiet fast songs from glowing continuously and still lets slow songs light up at a climax.
 */
internal data class MiniPlayerEnergyProfile(
    val energy: FloatArray,
    val bpm: Float,
    val beatPhaseSeconds: Float,
) {
    fun energyAt(progress: Float): Float {
        if (energy.isEmpty()) return 0f
        val position = progress.coerceIn(0f, 1f) * (energy.size - 1)
        val left = position.toInt().coerceIn(0, energy.lastIndex)
        val right = (left + 1).coerceAtMost(energy.lastIndex)
        val t = position - left
        return energy[left] + (energy[right] - energy[left]) * t
    }

    fun beatPulse(positionMs: Long): Float {
        if (bpm !in 40f..240f) return 0f
        val period = 60f / bpm
        if (period <= 0f) return 0f
        val seconds = positionMs.coerceAtLeast(0L) / 1000f
        var phase = ((seconds - beatPhaseSeconds) / period) % 1f
        if (phase < 0f) phase += 1f
        val distance = min(phase, 1f - phase)
        return exp((-distance * distance * 90f).toDouble()).toFloat().coerceIn(0f, 1f)
    }
}

internal fun analyzeMiniPlayerEnergy(
    peaks: FloatArray,
    durationMs: Long,
): MiniPlayerEnergyProfile? {
    if (peaks.size < 64 || durationMs <= 0L) return null
    val smoothed = FloatArray(peaks.size)
    for (i in peaks.indices) {
        var sum = 0f
        var count = 0
        for (j in (i - 2).coerceAtLeast(0)..(i + 2).coerceAtMost(peaks.lastIndex)) {
            sum += peaks[j].coerceIn(0f, 1f)
            count++
        }
        smoothed[i] = if (count > 0) sum / count else 0f
    }
    val sorted = smoothed.copyOf().also { it.sort() }
    fun percentile(fraction: Float): Float = sorted[
        (sorted.lastIndex * fraction.coerceIn(0f, 1f)).roundToInt().coerceIn(0, sorted.lastIndex)
    ]
    val floor = percentile(0.52f)
    val high = max(percentile(0.90f), floor + 0.04f)
    val energy = FloatArray(smoothed.size) { index ->
        val base = ((smoothed[index] - floor) / (high - floor)).coerceIn(0f, 1f)
        val rise = if (index > 0) (smoothed[index] - smoothed[index - 1]).coerceAtLeast(0f) else 0f
        (base * 0.82f + (rise * 2.2f).coerceIn(0f, 1f) * 0.18f).coerceIn(0f, 1f)
    }

    val durationSeconds = durationMs / 1000f
    val sampleHz = peaks.size / durationSeconds.coerceAtLeast(1f)
    val onset = FloatArray(smoothed.size)
    var onsetTotal = 0f
    for (i in 1 until peaks.size) {
        // Tempo needs sharper transients than the five-point energy envelope. Use the raw
        // normalized RMS rise here; smoothing is kept only for the broad high-energy decision.
        onset[i] = (peaks[i].coerceIn(0f, 1f) - peaks[i - 1].coerceIn(0f, 1f)).coerceAtLeast(0f)
        onsetTotal += onset[i]
    }
    if (sampleHz < 4f || onsetTotal < 0.02f) {
        return MiniPlayerEnergyProfile(energy, 0f, 0f)
    }

    val minLag = (sampleHz * 60f / 190f).roundToInt().coerceAtLeast(1)
    val maxLag = (sampleHz * 60f / 65f).roundToInt().coerceAtMost(onset.size / 2)
    var bestLag = 0
    var bestScore = 0f
    if (maxLag > minLag) {
        for (lag in minLag..maxLag) {
            var score = 0f
            var norm = 0f
            for (i in lag until onset.size) {
                score += onset[i] * onset[i - lag]
                norm += onset[i] * onset[i]
            }
            val normalized = if (norm > 1.0e-6f) score / norm else 0f
            if (normalized > bestScore) {
                bestScore = normalized
                bestLag = lag
            }
        }
    }
    if (bestLag <= 0 || bestScore < 0.055f) {
        return MiniPlayerEnergyProfile(energy, 0f, 0f)
    }
    val bpm = (sampleHz * 60f / bestLag).coerceIn(65f, 190f)
    var bestPhase = 0
    var bestPhaseScore = -1f
    for (phase in 0 until bestLag) {
        var score = 0f
        var index = phase
        while (index < onset.size) {
            score += onset[index]
            index += bestLag
        }
        if (score > bestPhaseScore) {
            bestPhaseScore = score
            bestPhase = phase
        }
    }
    return MiniPlayerEnergyProfile(
        energy = energy,
        bpm = bpm,
        beatPhaseSeconds = bestPhase / sampleHz,
    )
}
