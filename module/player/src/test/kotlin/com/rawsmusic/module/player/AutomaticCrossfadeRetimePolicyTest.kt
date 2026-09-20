package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AutomaticCrossfadeRetimePolicyTest {
    @Test
    fun `lyrics recipe is never retimed by a quiet phrase`() {
        val policy = AutomaticCrossfadeRetimePolicy()
        repeat(20) {
            assertNull(
                policy.evaluate(
                    source = AutoTransitionPolicy.Source.LYRICS,
                    outgoingDb = -60f,
                    bufferFrames = 100,
                    sampleRate = 1_000,
                    scheduledFramesLeft = 20_000,
                ),
            )
        }
    }

    @Test
    fun `envelope quiet tail requests exactly one full fade window`() {
        val policy = AutomaticCrossfadeRetimePolicy()
        var result: Int? = null
        repeat(4) {
            result = result ?: policy.evaluate(
                source = AutoTransitionPolicy.Source.ENVELOPE,
                outgoingDb = -52f,
                bufferFrames = 100,
                sampleRate = 1_000,
                scheduledFramesLeft = 20_000,
            )
        }
        assertEquals(AutoTransitionPolicy.LEAD_FADE_WINDOW_MS, result)
        assertNull(
            policy.evaluate(
                source = AutoTransitionPolicy.Source.ENVELOPE,
                outgoingDb = -60f,
                bufferFrames = 100,
                sampleRate = 1_000,
                scheduledFramesLeft = 20_000,
            ),
        )
    }
}
