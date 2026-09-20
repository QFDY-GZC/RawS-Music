package com.rawsmusic.ai.melody

import kotlin.math.ln
import kotlin.math.pow

/** Conservative cleanup that keeps expressive pitch movement while removing one-frame glitches. */
internal object AiPitchContourRefiner {
    fun refine(
        f0Hz: FloatArray,
        confidence: FloatArray,
        minimumConfidence: Float,
    ): FloatArray {
        require(f0Hz.size == confidence.size)
        if (f0Hz.size < 3) return f0Hz.copyOf()
        val repaired = f0Hz.copyOf()

        // Repair only a single unvoiced frame surrounded by confident, pitch-consistent voiced frames.
        for (i in 1 until f0Hz.lastIndex) {
            if (f0Hz[i] > 0f || confidence[i] >= minimumConfidence) continue
            val left = f0Hz[i - 1]
            val right = f0Hz[i + 1]
            if (left <= 0f || right <= 0f) continue
            if (kotlin.math.abs(hzToCents(left) - hzToCents(right)) <= 80.0) {
                repaired[i] = geometricMean(left, right)
            }
        }

        // Three-frame median in cents only where every frame is voiced. This suppresses isolated
        // salience-bin spikes but preserves normal 5-8 Hz vibrato and portamento trajectories.
        val output = repaired.copyOf()
        for (i in 1 until repaired.lastIndex) {
            if (repaired[i - 1] <= 0f || repaired[i] <= 0f || repaired[i + 1] <= 0f) continue
            val values = doubleArrayOf(
                hzToCents(repaired[i - 1]),
                hzToCents(repaired[i]),
                hzToCents(repaired[i + 1]),
            ).apply { sort() }
            output[i] = centsToHz(values[1])
        }
        return output
    }

    private fun hzToCents(hz: Float): Double = 1200.0 * ln(hz / 10.0) / LN_2
    private fun centsToHz(cents: Double): Float = (10.0 * 2.0.pow(cents / 1200.0)).toFloat()
    private fun geometricMean(a: Float, b: Float): Float = kotlin.math.sqrt(a * b)
    private const val LN_2 = 0.6931471805599453
}
