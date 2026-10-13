package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.Assert.*
import org.junit.Test

class ProviderLaneEvictionIndexTest {
    @Test fun highEvictionNeverVisitsThousandsOfLowWrappers() {
        val index = ProviderLaneEvictionIndex<Int>()
        repeat(10000) { index.put("low-$it", it, false) }
        index.put("high", 20000, true)
        var probes = 0
        assertEquals(20000, index.removeOldest(true) { probes++; true })
        assertEquals(1, probes)
        assertNull(index.removeOldest(true) { error("Low wrappers must not be scanned") })
    }
    @Test fun preservesInsertionOrderAndPinnedWrappersWithoutPromotingHits() {
        val index = ProviderLaneEvictionIndex<Int>()
        index.put("pinned", 1, false)
        index.put("old", 2, false)
        index.put("new", 3, false)
        assertEquals(2, index.removeOldest(false) { it != 1 })
        index.remove("new", false)
        assertNull(index.removeOldest(false) { it != 1 })
        assertEquals(1, index.removeOldest(false) { true })
        index.put("next", 4, true)
        index.clear()
        assertNull(index.removeOldest(true) { true })
    }
}
