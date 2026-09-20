package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderCancelActionTest {
    @Test
    fun ownerDetachKeepsFlightWhenJoinerStillNeedsIt() {
        assertEquals(
            ProviderCancelAction.DETACH_OBSERVER_ONLY,
            resolveProviderCancelAction(requestIsFlightOwner = true, hasOtherLiveWaiters = true),
        )
    }

    @Test
    fun joinerDetachDoesNotCancelSharedFlight() {
        assertEquals(
            ProviderCancelAction.CANCEL_OBSERVER_ONLY,
            resolveProviderCancelAction(requestIsFlightOwner = false, hasOtherLiveWaiters = true),
        )
    }

    @Test
    fun finalObserverCancelsFlightRegardlessOfOwnership() {
        assertEquals(
            ProviderCancelAction.CANCEL_FLIGHT,
            resolveProviderCancelAction(requestIsFlightOwner = true, hasOtherLiveWaiters = false),
        )
        assertEquals(
            ProviderCancelAction.CANCEL_FLIGHT,
            resolveProviderCancelAction(requestIsFlightOwner = false, hasOtherLiveWaiters = false),
        )
    }
}
