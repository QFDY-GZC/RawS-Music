package com.rawsmusic.ai.melody

import kotlin.math.pow

/** Decodes the 360-bin RMVPE salience output without quantising away vibrato or slides. */
internal object RmvpePitchDecoder {
    const val CLASS_COUNT = 360
    private const val CENTS_BASE = 1997.3794084376191
    private const val CENTS_STEP = 20.0
    private const val LOCAL_RADIUS = 4

    data class Pitch(
        val f0Hz: Float,
        val confidence: Float,
    )

    fun decodeFrame(
        salience: FloatArray,
        offset: Int,
        threshold: Float,
    ): Pitch {
        require(offset >= 0 && offset + CLASS_COUNT <= salience.size)
        var peakIndex = 0
        var peak = Float.NEGATIVE_INFINITY
        for (bin in 0 until CLASS_COUNT) {
            val value = salience[offset + bin]
            if (value > peak) {
                peak = value
                peakIndex = bin
            }
        }
        val confidence = peak.takeIf(Float::isFinite)?.coerceIn(0f, 1f) ?: 0f
        if (confidence <= threshold) return Pitch(0f, confidence)

        val start = (peakIndex - LOCAL_RADIUS).coerceAtLeast(0)
        val end = (peakIndex + LOCAL_RADIUS).coerceAtMost(CLASS_COUNT - 1)
        var weightedCents = 0.0
        var weight = 0.0
        for (bin in start..end) {
            val salienceValue = salience[offset + bin].toDouble().coerceAtLeast(0.0)
            weightedCents += salienceValue * (CENTS_BASE + CENTS_STEP * bin)
            weight += salienceValue
        }
        if (weight <= 1.0e-12) return Pitch(0f, confidence)
        val cents = weightedCents / weight
        val hz = 10.0 * 2.0.pow(cents / 1200.0)
        return Pitch(hz.toFloat().takeIf { it.isFinite() } ?: 0f, confidence)
    }
}
