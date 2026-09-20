package com.rawsmusic.module.player.dsp

import org.junit.Assert.assertEquals
import org.junit.Test

class PeqHeadroomPolicyTest {
    @Test
    fun `autoeq preamp is not subtracted twice when it already covers filter peak`() {
        val decision = resolvePeqHeadroom(
            requestedPreampDb = -6.6f,
            filterPeakDb = 6.6f,
        )
        assertEquals(-6.6f, decision.effectivePreampDb, 0.0001f)
        assertEquals(0f, decision.reductionDb, 0.0001f)
    }

    @Test
    fun `headroom only adds attenuation for remaining positive output peak`() {
        val decision = resolvePeqHeadroom(
            requestedPreampDb = -3f,
            filterPeakDb = 6f,
        )
        assertEquals(3f, decision.requestedOutputPeakDb, 0.0001f)
        assertEquals(4f, decision.reductionDb, 0.0001f)
        assertEquals(-7f, decision.effectivePreampDb, 0.0001f)
    }

    @Test
    fun `requested preamp supports native negative range`() {
        assertEquals(-24f, sanitizePeqPreamp(-24f), 0.0001f)
        assertEquals(-96f, sanitizePeqPreamp(-120f), 0.0001f)
        assertEquals(12f, sanitizePeqPreamp(20f), 0.0001f)
    }
}
