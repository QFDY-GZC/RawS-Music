package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Test

class HomeFullCoverReturnGeometryTest {
    @Test fun scaledHiddenHomeReturnsToItsUnscaledHolder() {
        // Host origin 20, pivot 200, real holder edge 100, rendered at scale .84.
        assertEquals(100f, homeFullCoverUnscaledCoordinate(136f, 20f, 200f, .84f), .001f)
    }

    @Test fun scrollMovedHolderUsesItsNewPositionRatherThanEntryPosition() {
        // Real edge is now 40 after a 60px scroll; measurement is 85.6 in root.
        assertEquals(40f, homeFullCoverUnscaledCoordinate(85.6f, 20f, 200f, .84f), .001f)
    }

    @Test fun settledHomeOnlyRemovesHostOrigin() {
        assertEquals(100f, homeFullCoverUnscaledCoordinate(120f, 20f, 200f, 1f), .001f)
    }
}
