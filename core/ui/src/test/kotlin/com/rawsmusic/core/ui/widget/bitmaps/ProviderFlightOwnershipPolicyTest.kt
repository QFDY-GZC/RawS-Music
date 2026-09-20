package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.assertEquals
import org.junit.Test

class ProviderFlightOwnershipPolicyTest {
    @Test
    fun sameBucketMayJoinOnlyALiveProviderOwner() {
        assertEquals(false, shouldReclaimOrphanProviderFlight(false, false, false, false))
        assertEquals(false, shouldReclaimOrphanProviderFlight(true, true, false, false))
        assertEquals(true, shouldReclaimOrphanProviderFlight(true, false, false, false))
        assertEquals(true, shouldReclaimOrphanProviderFlight(true, true, true, false))
        // A cancelled owner that already started source work may still complete for joined waiters.
        assertEquals(false, shouldReclaimOrphanProviderFlight(true, true, true, true))
    }
}
