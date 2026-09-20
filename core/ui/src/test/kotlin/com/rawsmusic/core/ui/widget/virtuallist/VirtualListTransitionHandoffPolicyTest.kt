package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Test

class VirtualListTransitionHandoffPolicyTest {
    @Test
    fun outerSceneRetainsThePhysicalRecyclingRowsForIdentityContinuity() {
        assertEquals(1, virtualListRetainedRowsEachSide(sceneMotionActive = false))
        assertEquals(1, virtualListRetainedRowsEachSide(sceneMotionActive = true))
    }

    @Test
    fun onlyInternalTransitionOverlayReleasesTheIntermediateClip() {
        assertEquals(true, shouldClipVirtualListViewport(false, false))
        assertEquals(false, shouldClipVirtualListViewport(true, false))
        assertEquals(true, shouldClipVirtualListViewport(false, true))
        assertEquals(false, shouldClipVirtualListViewport(true, true))
    }

    @Test
    fun elasticPivotTracksLiveFrameInsideLargerStableEnvelope() {
        assertEquals(0.5f, virtualListTransitionElasticOriginFraction(200, 200), 0.0001f)
        assertEquals(0.25f, virtualListTransitionElasticOriginFraction(100, 200), 0.0001f)
        assertEquals(0.125f, virtualListTransitionElasticOriginFraction(50, 200), 0.0001f)
    }

    @Test
    fun positiveEndpointElasticityExpandsLocalClipSymmetrically() {
        assertEquals(0f, virtualListTransitionElasticClipOverflowPx(200, 1f), 0.0001f)
        assertEquals(10f, virtualListTransitionElasticClipOverflowPx(200, 1.10f), 0.0001f)
        // Shrinking never needs extra draw allowance.
        assertEquals(0f, virtualListTransitionElasticClipOverflowPx(200, 0.92f), 0.0001f)
    }

    @Test
    fun committedAndCancelledSessionsUseOppositeFrozenEndpoints() {
        assertEquals(
            640,
            resolveVirtualListTransitionEndpointScroll(
                committedToTarget = true,
                sourceScrollYPx = 220,
                targetScrollYPx = 640,
            ),
        )
        assertEquals(
            220,
            resolveVirtualListTransitionEndpointScroll(
                committedToTarget = false,
                sourceScrollYPx = 220,
                targetScrollYPx = 640,
            ),
        )
    }
    @Test
    fun persistentLibraryHandoffNeverCreatesAnEmptyPresentationGap() {
        assertEquals(
            true,
            shouldPreserveVirtualListPresentationForLibraryHandoff(
                hasPersistentLibraryRuntime = true,
                libraryPreflightActive = true,
                libraryMotionActive = false,
            ),
        )
        assertEquals(
            true,
            shouldPreserveVirtualListPresentationForLibraryHandoff(
                hasPersistentLibraryRuntime = true,
                libraryPreflightActive = false,
                libraryMotionActive = true,
            ),
        )
        assertEquals(
            false,
            shouldPreserveVirtualListPresentationForLibraryHandoff(
                hasPersistentLibraryRuntime = true,
                libraryPreflightActive = false,
                libraryMotionActive = false,
            ),
        )
        assertEquals(
            false,
            shouldPreserveVirtualListPresentationForLibraryHandoff(
                hasPersistentLibraryRuntime = false,
                libraryPreflightActive = true,
                libraryMotionActive = true,
            ),
        )
    }

}
