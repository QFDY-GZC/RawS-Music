package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class CrossfadeOutgoingFailureTest {
    @Test fun outgoingFailureAfterIncomingUiCommitKeepsIncomingInsteadOfAdvancingAgain() {
        assertTrue(CrossfadeOutgoingEofPolicy.keepPresentedIncomingAfterFailure(
            automatic = true, outgoingHandle = 26, pendingHandle = 30,
            exactPendingTarget = true, incomingPresented = true))
        assertTrue(CrossfadeOutgoingEofPolicy.shouldRenderSilence(-1, true, true, 0))
        assertFalse(CrossfadeOutgoingEofPolicy.shouldRenderSilence(-1, true, true, 8192))
    }

    @Test fun failingIncomingOrUnconfirmedTargetStillUsesNormalErrorHandling() {
        assertFalse(CrossfadeOutgoingEofPolicy.keepPresentedIncomingAfterFailure(true, 30, 30, true, true))
        assertFalse(CrossfadeOutgoingEofPolicy.keepPresentedIncomingAfterFailure(true, 26, 0, true, true))
        assertFalse(CrossfadeOutgoingEofPolicy.keepPresentedIncomingAfterFailure(true, 26, 30, false, true))
        assertFalse(CrossfadeOutgoingEofPolicy.keepPresentedIncomingAfterFailure(true, 26, 30, true, false))
        assertFalse(CrossfadeOutgoingEofPolicy.keepPresentedIncomingAfterFailure(false, 26, 30, true, true))
    }

    @Test fun lateOutgoingCompletionCannotMarkReplacementComplete() {
        val outgoing = DecoderStopToken("A")
        val incoming = DecoderStopToken("B")
        // Old loop observed itself as owner, then handoff installed B before its EOF publication.
        outgoing.markDecodeComplete()
        assertFalse(incoming.isDecodeComplete)
        incoming.markDecodeComplete()
        outgoing.resetDecodeCompletion()
        assertTrue(incoming.isDecodeComplete)
    }
}
