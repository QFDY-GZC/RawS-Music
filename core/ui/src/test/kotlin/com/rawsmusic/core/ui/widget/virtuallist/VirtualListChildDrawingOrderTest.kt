package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class VirtualListChildDrawingOrderTest {
    @Test fun retainedPopulationUsesPublishedOrder() {
        assertArrayEquals(intArrayOf(1, 3, 2, 0), resolveVirtualListChildDrawingOrder(4, listOf(2, 0)))
    }

    @Test fun sharedActorIsLastWithoutDuplicatingAChild() {
        assertArrayEquals(intArrayOf(1, 2, 0, 3), resolveVirtualListChildDrawingOrder(4, listOf(2, 0, 3)))
        assertArrayEquals(intArrayOf(1, 0), resolveVirtualListChildDrawingOrder(2, listOf(-1, 0, 0, 2)))
    }

    @Test fun emptyPopulationRetainsPhysicalOrder() {
        assertArrayEquals(intArrayOf(0, 1), resolveVirtualListChildDrawingOrder(2, emptyList()))
        assertArrayEquals(intArrayOf(), resolveVirtualListChildDrawingOrder(0, emptyList()))
    }
}
