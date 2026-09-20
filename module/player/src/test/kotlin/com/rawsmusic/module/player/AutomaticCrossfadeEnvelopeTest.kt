package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomaticCrossfadeEnvelopeTest {
    @Test
    fun `smart pivot keeps independent ramps complementary and reaches exact endpoints`() {
        val sampleRate = 1_000
        val config = AutomaticCrossfadeEnvelope.Config(
            totalFrames = 8_000,
            sampleRate = sampleRate,
        )
        val start = AutomaticCrossfadeEnvelope.gains(0, config, 1f, 0f)
        val pivot = AutomaticCrossfadeEnvelope.gains(4_400, config, 1f, 0f)
        val end = AutomaticCrossfadeEnvelope.gains(8_000, config, 1f, 0f)

        assertEquals(1f, start.first, 0.0001f)
        assertEquals(0f, start.second, 0.0001f)
        assertEquals(0.5f, pivot.first, 0.0001f)
        assertEquals(0.5f, pivot.second, 0.0001f)
        assertEquals(1f, pivot.first + pivot.second, 0.0001f)
        assertEquals(1f, end.second, 0.0001f)
        assertEquals(0f, end.first, 0.0001f)
    }

    @Test
    fun `retimed envelope starts from current gains instead of unity`() {
        val config = AutomaticCrossfadeEnvelope.Config(totalFrames = 8_000, sampleRate = 1_000)
        val start = AutomaticCrossfadeEnvelope.gains(
            localFrame = 0,
            config = config,
            startOutgoingGain = 0.42f,
            startIncomingGain = 0.31f,
        )
        assertEquals(0.42f, start.first, 0.0001f)
        assertEquals(0.31f, start.second, 0.0001f)
    }

    @Test
    fun `louder follow delays pivot and quieter follow advances it`() {
        val base = 0.55f
        val louder = AutomaticCrossfadeEnvelope.loudnessAdaptivePivotFraction(base, -18f, -6f)
        val quieter = AutomaticCrossfadeEnvelope.loudnessAdaptivePivotFraction(base, -12f, -24f)
        assertTrue(louder > base)
        assertTrue(quieter < base)
        assertEquals(0.70f, louder, 0.0001f)
        assertEquals(0.40f, quieter, 0.0001f)
    }
}
