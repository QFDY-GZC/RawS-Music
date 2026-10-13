package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class AutomaticTransitionHandoffTest {
    @Test fun hiddenIncomingAccumulatesSourceTimeAndPublishesOnlyOnce() {
        val clock = TrackStartedPresentationCoordinator("test")
        clock.arm("next", 2, 1, 1000, 100_000, sourceStartMs = 8000)
        assertNull(clock.onPendingFramesRendered(2, 1, 9000, allowPresentation = false))
        assertNull(clock.activePositionMsOrNull())
        val shown = clock.onPendingFramesRendered(2, 1, 1000, allowPresentation = true)!!
        assertEquals(18000L, shown.positionMs)
        assertTrue(shown.startedNow)
        assertFalse(clock.onPendingFramesRendered(2, 1, 1000, true)!!.startedNow)
    }
    @Test fun cancelledOverlapNeverPublishesItsTarget() {
        val clock = TrackStartedPresentationCoordinator("test")
        clock.arm("next", 2, 1, 1000, 100_000)
        clock.onPendingFramesRendered(2, 1, 9000, false)
        clock.cancel("seek_old_track")
        assertNull(clock.onPendingFramesRendered(2, 1, 1000, true))
    }
}
