package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class CrossfadeOutgoingEofPolicyTest {
    @Test fun drainedAutomaticOutgoingKeepsItsEnvelopeAlive() {
        assertTrue(CrossfadeOutgoingEofPolicy.shouldRenderSilence(0, true, true, 0))
        assertTrue(CrossfadeOutgoingEofPolicy.shouldRenderSilence(-1, true, true, 0))
    }
    @Test fun transientTimeoutAndBufferedOldAudioAreNeverReplaced() {
        assertFalse(CrossfadeOutgoingEofPolicy.shouldRenderSilence(-1, true, false, 0))
        assertFalse(CrossfadeOutgoingEofPolicy.shouldRenderSilence(-1, true, true, 4096))
        assertFalse(CrossfadeOutgoingEofPolicy.shouldRenderSilence(200, true, true, 0))
    }
    @Test fun manualTransitionsRetainTheirExistingEofBehavior() {
        assertFalse(CrossfadeOutgoingEofPolicy.shouldRenderSilence(0, false, true, 0))
    }
}
