package com.rawsmusic.core.ui.widget.virtuallist

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VirtualListProviderNavigationFrameTest {
    @Test fun localNavigationRequiresBothProviders() {
        assertFalse(usesLocalProviderNavigation(true, false, false))
        assertFalse(usesLocalProviderNavigation(false, true, false))
        assertTrue(usesLocalProviderNavigation(true, true, false))
    }

    @Test fun outerSceneTransitionAlwaysOwnsThePopulationTransform() {
        assertFalse(usesLocalProviderNavigation(true, true, true))
    }
}
