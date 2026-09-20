package com.rawsmusic.core.ui.scene.pages

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CollectionSharedCoverVisibilityTest {
    @Test
    fun visibleCoverIsEligible() {
        assertTrue(isCollectionSharedCoverVisibleInWindow(0f, 24f, 300f, 324f, 1080f, 2400f))
    }

    @Test
    fun coverScrolledFullyAboveViewportIsNotEligible() {
        assertFalse(isCollectionSharedCoverVisibleInWindow(0f, -340f, 300f, -40f, 1080f, 2400f))
    }

    @Test
    fun partiallyVisibleCoverRemainsEligible() {
        assertTrue(isCollectionSharedCoverVisibleInWindow(0f, -240f, 300f, 60f, 1080f, 2400f))
    }
}
