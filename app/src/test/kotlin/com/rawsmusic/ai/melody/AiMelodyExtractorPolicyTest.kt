package com.rawsmusic.ai.melody

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class AiMelodyExtractorPolicyTest {
    @Test
    fun networkPaddingUsesRmvpeConstantZeroTail() {
        val source = floatArrayOf(
            1f, 2f, 3f,
            4f, 5f, 6f,
        )
        val padded = AiMelodyExtractor.padMelConstant(
            source = source,
            melBins = 2,
            sourceFrames = 3,
            targetFrames = 32,
        )
        assertEquals(64, padded.size)
        assertArrayEquals(floatArrayOf(1f, 2f, 3f), padded.copyOfRange(0, 3), 0f)
        assertArrayEquals(FloatArray(29), padded.copyOfRange(3, 32), 0f)
        assertArrayEquals(floatArrayOf(4f, 5f, 6f), padded.copyOfRange(32, 35), 0f)
        assertArrayEquals(FloatArray(29), padded.copyOfRange(35, 64), 0f)
    }

    @Test
    fun networkFrameCountRoundsToUnetMultiple() {
        assertEquals(32, AiMelodyExtractor.roundUp32(1))
        assertEquals(32, AiMelodyExtractor.roundUp32(32))
        assertEquals(64, AiMelodyExtractor.roundUp32(33))
    }
}
