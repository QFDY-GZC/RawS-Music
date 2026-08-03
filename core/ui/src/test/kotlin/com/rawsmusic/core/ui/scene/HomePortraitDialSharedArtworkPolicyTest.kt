package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomePortraitDialSharedArtworkPolicyTest {
    @Test
    fun movingLaneReusesHighTierAndNeverFreezesPlaceholder() {
        val policy = homePortraitDialSharedArtworkPolicy

        assertEquals(1024, policy.movingTargetSidePx)
        assertFalse(policy.freezeBitmapUpdatesDuringMotion)
        assertTrue(policy.prewarmFullCover)
    }

    @Test
    fun returnArtworkPrefersActualFullscreenCenterOverLaggingPlayerState() {
        assertEquals(
            "new-centre",
            resolveHomePortraitDialReturnArtworkKey(
                fullscreenCenterArtworkKey = "new-centre",
                currentArtworkKey = "old-player",
            ),
        )
        assertEquals(
            "player-fallback",
            resolveHomePortraitDialReturnArtworkKey(
                fullscreenCenterArtworkKey = "",
                currentArtworkKey = "player-fallback",
            ),
        )
    }
}
