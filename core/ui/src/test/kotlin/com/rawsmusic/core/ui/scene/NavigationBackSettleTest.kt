package com.rawsmusic.core.ui.scene

import org.junit.Assert.*
import org.junit.Test

class NavigationBackSettleTest {
    @Test fun forwardPreparationCannotBeSupersededByAnotherGesture() {
        val state = NavigationState()
        state.navigateTo(NavScene.SONGS)
        val request = state.transitionRequestId
        assertTrue(state.isTransitioning)
        assertFalse(state.startBackDrag())
        assertFalse(state.startSiblingDrag(NavScene.ARTISTS, 1f))
        assertEquals(request, state.transitionRequestId)
        assertEquals(NavScene.SONGS, state.currentScene)
        state.completeTransitionAt(NavScene.SONGS)
        assertTrue(state.startBackDrag())
    }

    @Test fun releaseKeepsOwnershipUntilCancellationFinishes() {
        val state = NavigationState()
        assertTrue(state.startSiblingDrag(NavScene.SONGS, 1f))
        state.releaseBackDrag(false)
        assertTrue(state.isSettlingBack)
        assertFalse(state.startSiblingDrag(NavScene.ARTISTS, 1f))
        val token = state.dragBackReleaseToken
        state.releaseBackDrag(true)
        assertEquals(token, state.dragBackReleaseToken)
        state.completeBackDrag(false)
        assertFalse(state.isSettlingBack)
        assertTrue(state.startSiblingDrag(NavScene.SONGS, 1f))
    }

    @Test fun lifecycleResetReleasesTheSettleLock() {
        val state = NavigationState()
        assertTrue(state.startSiblingDrag(NavScene.SONGS, 1f))
        state.releaseBackDrag(true)
        state.resetTransientBackState()
        assertFalse(state.isSettlingBack)
        assertTrue(state.startSiblingDrag(NavScene.SONGS, 1f))
    }
}
