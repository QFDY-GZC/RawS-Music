package com.rawsmusic.core.ui.scene

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeFullCoverActivityHostPolicyTest {
    @Test
    fun activeSceneKeepsOverlayComposedButMovesItBelowTheHost() {
        val policy = resolveHomeFullCoverActivityHostPolicy(active = true)

        assertEquals(-1f, policy.playerOverlayZIndex)
        assertEquals(0f, policy.playerOverlayAlpha)
        assertTrue(policy.blockRootSceneGesture)
        assertFalse(policy.landscapeLaunchArmed)
        assertTrue(policy.clearPendingLandscapeLaunch)
        assertFalse(policy.refreshOrientationPolicy)
    }

    @Test
    fun settledHomeRestoresOverlayWithoutRefreshingOrientation() {
        val policy = resolveHomeFullCoverActivityHostPolicy(active = false)

        assertEquals(1f, policy.playerOverlayZIndex)
        assertEquals(1f, policy.playerOverlayAlpha)
        assertFalse(policy.blockRootSceneGesture)
        assertTrue(policy.landscapeLaunchArmed)
        assertFalse(policy.clearPendingLandscapeLaunch)
        assertFalse(policy.refreshOrientationPolicy)
    }
}
