package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListOuterTransitionNodePolicyTest {
    @Test
    fun oneSidedHoldersUseNode() {
        assertTrue(VirtualListOuterTransitionNodePolicy.eligible(VirtualListOuterDualLayoutKind.CURRENT_ONLY, false, false, false))
        assertTrue(VirtualListOuterTransitionNodePolicy.eligible(VirtualListOuterDualLayoutKind.RETAINED_ONLY, false, false, false))
    }

    @Test
    fun sharedNeedsSameTopology() {
        assertTrue(VirtualListOuterTransitionNodePolicy.eligible(VirtualListOuterDualLayoutKind.SHARED, true, false, false))
        assertFalse(VirtualListOuterTransitionNodePolicy.eligible(VirtualListOuterDualLayoutKind.SHARED, false, false, false))
    }

    @Test
    fun selectionKeepsCompatibilityRenderer() {
        assertFalse(VirtualListOuterTransitionNodePolicy.eligible(VirtualListOuterDualLayoutKind.CURRENT_ONLY, true, true, false))
    }

    @Test
    fun customHolderStaysOnPhysicalComposeHolder() {
        assertFalse(VirtualListOuterTransitionNodePolicy.eligible(VirtualListOuterDualLayoutKind.CURRENT_ONLY, true, false, true))
        assertFalse(VirtualListOuterTransitionNodePolicy.eligible(VirtualListOuterDualLayoutKind.RETAINED_ONLY, true, false, true))
    }

    @Test
    fun onlySharedOuterHolderAdvancesItsInternalScene() {
        assertTrue(
            VirtualListOuterTransitionNodePolicy.shouldUpdateInternalScene(
                VirtualListOuterTransitionKind.SHARED,
            )
        )
        assertFalse(
            VirtualListOuterTransitionNodePolicy.shouldUpdateInternalScene(
                VirtualListOuterTransitionKind.CURRENT_ONLY,
            )
        )
        assertFalse(
            VirtualListOuterTransitionNodePolicy.shouldUpdateInternalScene(
                VirtualListOuterTransitionKind.RETAINED_ONLY,
            )
        )
        assertFalse(VirtualListOuterTransitionNodePolicy.shouldUpdateInternalScene(null))
    }
}
