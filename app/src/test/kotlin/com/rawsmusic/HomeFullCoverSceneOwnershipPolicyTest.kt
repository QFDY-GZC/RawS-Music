package com.rawsmusic

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFullCoverSceneOwnershipPolicyTest {
    @Test
    fun activeFullCoverKeepsPlayerOverlayComposedBelowTheHost() {
        assertEquals(
            HomeFullCoverOverlayLayer(zIndex = -1f, alpha = 0f),
            resolveHomeFullCoverOverlayLayer(active = true),
        )
    }

    @Test
    fun closedFullCoverRestoresTheExistingPlayerOverlayAboveHome() {
        assertEquals(
            HomeFullCoverOverlayLayer(zIndex = 1f, alpha = 1f),
            resolveHomeFullCoverOverlayLayer(active = false),
        )
    }

    @Test
    fun staleControllerPlayerSceneMustBeResetBeforeRootBackRegistration() {
        val decision = resolveHomeFullCoverSceneOwnershipDecision(
            controllerSceneIsMain = false,
            controllerComposeSceneIsMain = true,
            controllerTransitioning = false,
            composeSceneIsMain = true,
        )
        assertTrue(decision.resetControllerToMain)
        assertFalse(decision.resetComposeToMain)
    }

    @Test
    fun stableMainSceneNeedsNoReset() {
        assertEquals(
            HomeFullCoverSceneOwnershipDecision(
                resetControllerToMain = false,
                resetComposeToMain = false,
            ),
            resolveHomeFullCoverSceneOwnershipDecision(
                controllerSceneIsMain = true,
                controllerComposeSceneIsMain = true,
                controllerTransitioning = false,
                composeSceneIsMain = true,
            ),
        )
    }
}
