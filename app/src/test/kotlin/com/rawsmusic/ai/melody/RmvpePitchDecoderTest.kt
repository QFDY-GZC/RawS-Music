package com.rawsmusic.ai.melody

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class RmvpePitchDecoderTest {
    @Test
    fun localWeightedAverageProducesContinuousPitch() {
        val salience = FloatArray(RmvpePitchDecoder.CLASS_COUNT)
        // Around A4: RMVPE bin mapping is 20 cents/bin, so blend adjacent bins rather than
        // forcing a single discrete class.
        salience[227] = 0.45f
        salience[228] = 0.90f
        salience[229] = 0.55f
        val pitch = RmvpePitchDecoder.decodeFrame(salience, 0, 0.03f)
        assertTrue(pitch.f0Hz in 430f..455f)
        assertEquals(0.90f, pitch.confidence, 1.0e-6f)
    }

    @Test
    fun weakFrameIsUnvoiced() {
        val salience = FloatArray(RmvpePitchDecoder.CLASS_COUNT)
        salience[100] = 0.02f
        val pitch = RmvpePitchDecoder.decodeFrame(salience, 0, 0.03f)
        assertEquals(0f, pitch.f0Hz, 0f)
        assertTrue(abs(pitch.confidence - 0.02f) < 1.0e-6f)
    }

    @Test
    fun contourRefinerOnlyRepairsSinglePitchConsistentHole() {
        val refined = AiPitchContourRefiner.refine(
            f0Hz = floatArrayOf(440f, 0f, 442f, 0f, 0f, 660f),
            confidence = floatArrayOf(0.9f, 0f, 0.9f, 0f, 0f, 0.9f),
            minimumConfidence = 0.03f,
        )
        assertTrue(refined[1] in 440f..442f)
        assertEquals(0f, refined[3], 0f)
        assertEquals(0f, refined[4], 0f)
    }
}
