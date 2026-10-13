package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomChromeLayoutAnchorTest {
    @Test fun movingChromeDoesNotInvalidateRestingLayoutAnchor() {
        val state = BottomChromeScrollState()
        state.updateTopInRootPx(800f)
        Snapshot.sendApplyNotifications()
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { invalidations++ }) { state.restingTopInRootPx }
            repeat(60) { state.updateTopInRootPx(800f + it) }
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            assertEquals(800f, state.restingTopInRootPx, 0f)
            assertEquals(859f, state.topInRootPx, 0f)
            state.resetGeometryAnchor()
            state.updateTopInRootPx(700f)
            Snapshot.sendApplyNotifications()
            assertEquals(1, invalidations)
            assertEquals(700f, state.restingTopInRootPx, 0f)
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
