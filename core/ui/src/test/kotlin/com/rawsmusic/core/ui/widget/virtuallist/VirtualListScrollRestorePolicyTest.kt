package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListScrollRestorePolicyTest {
    @Test
    fun retainedScrollIsNotCollapsedByTransientZeroGeometry() {
        assertEquals(
            742,
            resolveSettledScrollForGeometry(
                rawScrollY = 742,
                maxScrollY = 0,
                preserveRetainedScroll = true,
            ),
        )
    }

    @Test
    fun authoritativeGeometryStillClampsNormally() {
        assertEquals(
            0,
            resolveSettledScrollForGeometry(
                rawScrollY = 742,
                maxScrollY = 0,
                preserveRetainedScroll = false,
            ),
        )
        assertEquals(
            500,
            resolveSettledScrollForGeometry(
                rawScrollY = 742,
                maxScrollY = 500,
                preserveRetainedScroll = false,
            ),
        )
    }

    @Test
    fun negativeScrollNeverEscapesTheViewportContract() {
        assertEquals(
            0,
            resolveSettledScrollForGeometry(
                rawScrollY = -20,
                maxScrollY = 500,
                preserveRetainedScroll = true,
            ),
        )
    }

    @Test
    fun sameAuthoritativeGeometryPreservesRetainedScrollAcrossZeroExtentFrames() {
        val state = ComposeVirtualListState(
            initialLevel = ListZoomIndex.NORMAL,
            initialColumns = 1,
            persistZoomLevel = {},
            persistColumns = {},
        )
        state.recordSettledGeometry(
            geometrySignature = 91L,
            viewportHeightPx = 2200,
            maxScrollY = 4800,
        )

        assertTrue(
            state.shouldPreserveRetainedScrollForZeroGeometry(
                rawScrollY = 1700,
                maxScrollY = 0,
                geometrySignature = 91L,
                viewportHeightPx = 2200,
            )
        )
    }

    @Test
    fun realGeometryOrViewportChangeMayClampToTop() {
        val state = ComposeVirtualListState(
            initialLevel = ListZoomIndex.NORMAL,
            initialColumns = 1,
            persistZoomLevel = {},
            persistColumns = {},
        )
        state.recordSettledGeometry(
            geometrySignature = 91L,
            viewportHeightPx = 2200,
            maxScrollY = 4800,
        )

        assertFalse(
            state.shouldPreserveRetainedScrollForZeroGeometry(
                rawScrollY = 1700,
                maxScrollY = 0,
                geometrySignature = 92L,
                viewportHeightPx = 2200,
            )
        )
        assertFalse(
            state.shouldPreserveRetainedScrollForZeroGeometry(
                rawScrollY = 1700,
                maxScrollY = 0,
                geometrySignature = 91L,
                viewportHeightPx = 2400,
            )
        )
    }
}
