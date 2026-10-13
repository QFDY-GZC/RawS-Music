package com.rawsmusic.core.ui.widget.bottombar

import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.runtime.snapshots.SnapshotStateObserver
import androidx.compose.ui.geometry.Rect
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomChromeGestureGeometryTest {
    @Test fun retainedPointerProviderUsesLatestBoundsAndClearsWithoutRecreation() {
        val geometry = BottomChromeGestureGeometry()
        val provider = { geometry.bounds }
        geometry.bounds = Rect(0f, 100f, 300f, 160f)
        assertEquals(100f, provider()!!.top, 0f)
        geometry.bounds = Rect(40f, 140f, 260f, 190f)
        assertEquals(140f, provider()!!.top, 0f)
        geometry.bounds = null
        assertEquals(null, provider())
    }
    @Test fun measuredGestureBoundsDoNotScheduleAnotherComposition() {
        val geometry = BottomChromeGestureGeometry()
        var invalidations = 0
        val observer = SnapshotStateObserver { it() }
        observer.start()
        try {
            observer.observeReads(Any(), { invalidations++ }) { geometry.bounds }
            geometry.bounds = Rect(0f, 100f, 300f, 160f)
            Snapshot.sendApplyNotifications()
            assertEquals(0, invalidations)
            assertEquals(100f, geometry.bounds!!.top, 0f)
        } finally {
            observer.stop()
            observer.clear()
        }
    }
}
