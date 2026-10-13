package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.*
import org.junit.Test

class PlaybackArtworkLoadPlanTest {
    @Test fun parkedNeighboursPrepareTheSameTierAsTheSettledCurrent() {
        assertEquals(1536, playbackArtworkLoadPlan(1536, false, false, false).targetSide)
        assertFalse(playbackArtworkLoadPlan(1536, false, false, false).foreground)
    }
    @Test fun targetDoesNotDowngradeDuringButtonOrGestureMotion() {
        val plan = playbackArtworkLoadPlan(1024, false, true, true)
        assertEquals(1024, plan.targetSide)
        assertTrue(plan.foreground)
        assertFalse(plan.retainPreviousPixels)
    }
    @Test fun currentKeepsItsPlaybackTierWhenMotionStarts() {
        assertEquals(1536, playbackArtworkLoadPlan(1536, true, false, true).targetSide)
    }
    @Test fun onlyIdleCurrentMayRetainAnotherIdentityWhileResolving() {
        assertTrue(playbackArtworkLoadPlan(1024, true, false, false).retainPreviousPixels)
        assertFalse(playbackArtworkLoadPlan(1024, true, false, true).retainPreviousPixels)
        assertFalse(playbackArtworkLoadPlan(1024, false, true, false).retainPreviousPixels)
        assertFalse(playbackArtworkLoadPlan(1024, false, false, false).retainPreviousPixels)
    }
    @Test fun smallPlaybackTierIsNotArtificiallyIncreased() {
        assertEquals(512, playbackArtworkLoadPlan(512, false, false, false).targetSide)
    }
    @Test fun invalidTargetDoesNotReachTheProviderAsZero() {
        assertEquals(1, playbackArtworkLoadPlan(0, true, false, false).targetSide)
    }
}
