package com.rawsmusic.module.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoderCompletionOwnershipTest {
    @Test
    fun activeDecoderMayPublishEof() {
        assertTrue(
            DecoderCompletionOwnership.ownsActiveDecoder(
                activeHandle = 2L,
                loopHandle = 2L,
                sameRingBuffer = true,
                sameStopToken = true,
                stopRequested = false
            )
        )
    }

    @Test
    fun retiredCrossfadeDecoderCannotPublishEof() {
        assertFalse(
            DecoderCompletionOwnership.ownsActiveDecoder(
                activeHandle = 2L,
                loopHandle = 1L,
                sameRingBuffer = false,
                sameStopToken = false,
                stopRequested = true
            )
        )
    }

    @Test
    fun anyIdentityMismatchRejectsCompletion() {
        assertFalse(DecoderCompletionOwnership.ownsActiveDecoder(2L, 2L, false, true, false))
        assertFalse(DecoderCompletionOwnership.ownsActiveDecoder(2L, 2L, true, false, false))
        assertFalse(DecoderCompletionOwnership.ownsActiveDecoder(2L, 2L, true, true, true))
    }
}
