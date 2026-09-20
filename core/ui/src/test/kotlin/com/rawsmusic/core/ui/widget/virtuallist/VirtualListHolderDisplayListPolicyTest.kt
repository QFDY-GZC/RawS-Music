package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListHolderDisplayListPolicyTest {
    @Test
    fun api29HardwareUsesRetainedDisplayList() {
        assertTrue(VirtualListHolderDisplayListPolicy.shouldUseRenderNode(29, true))
        assertTrue(VirtualListHolderDisplayListPolicy.shouldUseRenderNode(37, true))
    }

    @Test
    fun pre29OrSoftwareKeepsDirectCanvasFallback() {
        assertFalse(VirtualListHolderDisplayListPolicy.shouldUseRenderNode(28, true))
        assertFalse(VirtualListHolderDisplayListPolicy.shouldUseRenderNode(29, false))
    }

    @Test
    fun dualLayoutMotionCommitsBoundsDirectlyInsteadOfRunningViewGroupLayout() {
        assertEquals(
            VirtualListHolderFrameCommit.DIRECT_BOUNDS,
            resolveVirtualListHolderFrameCommit(dualLayout = true),
        )
        assertEquals(
            VirtualListHolderFrameCommit.MEASURE_AND_LAYOUT,
            resolveVirtualListHolderFrameCommit(dualLayout = false),
        )
    }
}
