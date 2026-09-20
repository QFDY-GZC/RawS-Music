package com.rawsmusic.core.ui.scene

import androidx.compose.ui.geometry.Rect
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class CollectionTransitionProtocolTest {
    private fun cover(top: Float) = SharedCoverSnapshot("scene", "cover:1", Rect(0f, top, 100f, top + 100f), "art", 16f)

    @Test fun waitsForStableTargetInsteadOfFirstMeasurement() = runBlocking {
        var frame = 0
        val result = awaitCollectionSharedPair(
            candidate = { cover(0f) to cover(if (frame == 1) 10f else 30f) },
            nextFrame = { frame++ },
        )
        assertEquals(3, frame)
        assertEquals(30f, result!!.second.boundsInWindow.top, 0f)
    }

    @Test fun absentOrOffscreenSourceDoesNotClaimSharedOwnership() = runBlocking {
        assertNull(awaitCollectionSharedPair(candidate = { null }, nextFrame = {}))
        assertNull(awaitCollectionSharedPair(
            candidate = { cover(0f).copy(sharedEligible = false) to cover(20f) }, nextFrame = {},
        ))
    }

    @Test fun lateTargetStillUsesLatestLayout() = runBlocking {
        var frame = 0
        val pair = awaitCollectionSharedPair(
            candidate = { if (frame < 6) null else cover(0f) to cover(50f) },
            nextFrame = { frame++ },
        )
        assertEquals(50f, pair!!.second.boundsInWindow.top, 0f)
    }

    @Test fun nonFlingReleaseUsesSceneDurationFloors() {
        assertEquals(250, collectionReleaseDuration(0f, true))
        assertEquals(100, collectionReleaseDuration(0.99f, true))
        assertEquals(166, collectionReleaseDuration(0.01f, false))
        assertEquals(500, collectionReleaseDuration(1f, false))
    }
}
