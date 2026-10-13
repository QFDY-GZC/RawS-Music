package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerArtworkEndpointSnapshotTest {
    @Test fun firstLocalEndpointCanBeCapturedWhileEntryIsWaitingForLayout() {
        val snapshot = PlayerArtworkEndpointSnapshot()
        snapshot.update(PlayerArtworkHandoffRect(120f, 180f, 600f, 600f), true)
        assertEquals(PlayerArtworkHandoffRect(120f, 180f, 600f, 600f), snapshot.rect)
    }
    @Test fun movingPopulationCannotReplaceTheSettledTabletEndpoint() {
        val snapshot = PlayerArtworkEndpointSnapshot()
        snapshot.update(PlayerArtworkHandoffRect(200f, 180f, 800f, 800f), false)
        snapshot.update(PlayerArtworkHandoffRect(80f, 100f, 1000f, 1000f), true)
        assertEquals(PlayerArtworkHandoffRect(200f, 180f, 800f, 800f), snapshot.rect)
    }

    @Test fun missingMeasurementDoesNotEraseTheEndpointDuringHandoff() {
        val snapshot = PlayerArtworkEndpointSnapshot()
        snapshot.update(PlayerArtworkHandoffRect(200f, 180f, 800f, 800f), false)
        snapshot.update(null, true)
        assertEquals(PlayerArtworkHandoffRect(200f, 180f, 800f, 800f), snapshot.rect)
    }

    @Test fun settledRelayoutRefreshesTheNextTransitionEndpoint() {
        val snapshot = PlayerArtworkEndpointSnapshot()
        snapshot.update(PlayerArtworkHandoffRect(200f, 180f, 800f, 800f), false)
        snapshot.update(PlayerArtworkHandoffRect(200f, 240f, 700f, 700f), false)
        assertEquals(PlayerArtworkHandoffRect(200f, 240f, 700f, 700f), snapshot.rect)
    }

    @Test fun unmeasuredOrInvalidArtworkCannotMarkTheLayoutReady() {
        val snapshot = PlayerArtworkEndpointSnapshot()
        snapshot.update(PlayerArtworkHandoffRect(0f, 0f, 0f, 800f), false)
        snapshot.update(PlayerArtworkHandoffRect(Float.NaN, 0f, 800f, 800f), false)
        assertNull(snapshot.rect)
    }
}
