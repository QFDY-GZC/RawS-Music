package com.rawsmusic.core.ui.widget.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PortraitDialArtworkPolicyTest {
    @Test
    fun centreOwnsTheOnlyFullscreenSizedRequest() {
        val centre = resolvePortraitDialArtworkTier(0f)
        val near = resolvePortraitDialArtworkTier(1f)

        assertEquals(PortraitDialArtworkTier.Center, centre)
        assertEquals(1440, centre.targetSidePx)
        assertEquals(PortraitDialArtworkTier.Near, near)
        assertTrue(near.targetSidePx < centre.targetSidePx)
    }

    @Test
    fun visibleNineLanesAndOneHiddenLanePerSideAreAdmitted() {
        assertEquals(PortraitDialArtworkTier.Outer, resolvePortraitDialArtworkTier(4f))
        assertEquals(PortraitDialArtworkTier.Preload, resolvePortraitDialArtworkTier(5f))
        assertTrue(resolvePortraitDialArtworkTier(5f).shouldLoad)
        assertEquals(PortraitDialArtworkTier.Dormant, resolvePortraitDialArtworkTier(6f))
        assertFalse(resolvePortraitDialArtworkTier(6f).shouldLoad)
    }
}
