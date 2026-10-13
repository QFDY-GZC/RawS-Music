package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class AutoTransitionUiPolicyTest {
    @Test fun audibleMainHandoverPublishesBeforeTheLongAudioEnvelopeEnds() {
        assertFalse(AutoTransitionUiPolicy.ready(9999, 21000, 1000, 1000, false,
            gentleFrames = 6000, outgoingGain = .79f))
        assertTrue(AutoTransitionUiPolicy.ready(18000, 21000, 1000, 1000, false,
            gentleFrames = 6000, outgoingGain = .1f, dominantFrames = 500))
    }

    @Test fun actuallyDominantIncomingMayOwnUiDuringGentleOverlap() {
        assertTrue(AutoTransitionUiPolicy.incomingDominates(.85f, -12f, -45f))
        assertFalse(AutoTransitionUiPolicy.incomingDominates(.85f, -12f, -12f))
        assertFalse(AutoTransitionUiPolicy.ready(2000, 21000, 1000, 1000, false,
            gentleFrames = 6000, outgoingGain = .85f, dominantFrames = 499))
        assertTrue(AutoTransitionUiPolicy.ready(2000, 21000, 1000, 1000, false,
            gentleFrames = 6000, outgoingGain = .85f, dominantFrames = 500, outgoingDb = -45f))
    }

    @Test fun silentOrBarelyMixedIncomingCannotTakeUiFromTheOldTrack() {
        assertFalse(AutoTransitionUiPolicy.ready(10000, 15000, 0, 1000, false,
            outgoingGain = .5f, dominantFrames = 1000))
        assertFalse(AutoTransitionUiPolicy.ready(10000, 15000, 1000, 1000, false,
            outgoingGain = .99f, dominantFrames = 1000))
    }

    @Test fun earlyUiSwitchUsesRealIncomingProgressAndKeepsItAdvancing() {
        val clock = TrackStartedPresentationCoordinator("test")
        clock.arm("next", 2, 1, 1000, 100000, sourceStartMs = 8000)
        assertNull(clock.onPendingFramesRendered(2, 1, 8000, allowPresentation =
            AutoTransitionUiPolicy.ready(8000, 21000, 2000, 1000, false,
                gentleFrames = 6000, outgoingGain = .83f)))
        val first = clock.onPendingFramesRendered(2, 1, 2000, allowPresentation =
            AutoTransitionUiPolicy.ready(10000, 21000, 4000, 1000, false,
                gentleFrames = 6000, outgoingGain = .1f, dominantFrames = 500))!!
        assertTrue(first.startedNow)
        assertEquals(18000, first.positionMs)
        val next = clock.onPendingFramesRendered(2, 1, 500, allowPresentation = true)!!
        assertFalse(next.startedNow)
        assertEquals(18500, next.positionMs)
    }
    @Test fun thresholdStartsMixButKeepsOldUi() {
        assertFalse(AutoTransitionUiPolicy.ready(1, 12000, 1, 1000, false))
        assertFalse(AutoTransitionUiPolicy.ready(9000, 12000, 1000, 1000, false))
    }
    @Test fun shortAudibleEvidenceKeepsOldUiUntilCompletion() {
        assertFalse(AutoTransitionUiPolicy.ready(10200, 12000, 300, 1000, false, outgoingGain = .5f))
        assertTrue(AutoTransitionUiPolicy.ready(12000, 12000, 300, 1000, true))
    }
    @Test fun silentIncomingWaitsAndCompletedTransitionAlwaysPublishes() {
        assertFalse(AutoTransitionUiPolicy.ready(11000, 12000, 0, 1000, false))
        assertTrue(AutoTransitionUiPolicy.ready(12000, 12000, 0, 1000, true))
    }
    @Test fun retimedTransitionUsesActualShortenedDuration() {
        assertFalse(AutoTransitionUiPolicy.ready(5100, 6000, 300, 1000, false))
        assertTrue(AutoTransitionUiPolicy.ready(6000, 6000, 300, 1000, true))
    }
}
