package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class ArtworkDispatchTest {
    @Test fun urgentRetryHonorsBothBackoffIntervals() {
        assertEquals(ArtworkDispatch.Delayed(90L), resolveArtworkDispatch(true, false, 90L))
        assertEquals(ArtworkDispatch.Delayed(260L), resolveArtworkDispatch(true, false, 260L))
    }

    @Test fun firstUrgentRequestStillGoesToFront() {
        assertEquals(ArtworkDispatch.Front, resolveArtworkDispatch(true, false, 0L))
    }

    @Test fun ordinaryRequestStaysFifo() {
        assertEquals(ArtworkDispatch.Immediate, resolveArtworkDispatch(false, false, 0L))
    }

    @Test fun prefetchUsesItsDelayUnlessExplicitRetryDelayIsPresent() {
        assertEquals(ArtworkDispatch.Delayed(24L), resolveArtworkDispatch(false, true, 0L))
        assertEquals(ArtworkDispatch.Delayed(260L), resolveArtworkDispatch(false, true, 260L))
        assertEquals(ArtworkDispatch.Front, resolveArtworkDispatch(true, true, 0L))
    }
}
