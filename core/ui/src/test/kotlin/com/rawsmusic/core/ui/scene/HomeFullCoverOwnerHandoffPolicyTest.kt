package com.rawsmusic.core.ui.scene

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HomeFullCoverOwnerHandoffPolicyTest {
    @Test
    fun closingHandoffKeepsRealHomeHolderHidden() {
        val state = resolveHomeFullCoverOwnerVisibility(
            hostActive = true,
            transitionRunning = false,
            predictiveBackActive = false,
            closingHandoffPending = true,
        )

        assertTrue(state.hideHomeCenter)
        assertTrue(state.showSharedOwner)
    }

    @Test
    fun completedHandoffSwitchesOwnersWithoutOverlap() {
        val state = resolveHomeFullCoverOwnerVisibility(
            hostActive = false,
            transitionRunning = false,
            predictiveBackActive = false,
            closingHandoffPending = false,
        )

        assertFalse(state.hideHomeCenter)
        assertFalse(state.showSharedOwner)
    }

    @Test
    fun normalTransitionUsesSharedOwnerWhileHomeHolderIsHidden() {
        val state = resolveHomeFullCoverOwnerVisibility(
            hostActive = true,
            transitionRunning = true,
            predictiveBackActive = false,
            closingHandoffPending = false,
        )

        assertTrue(state.hideHomeCenter)
        assertTrue(state.showSharedOwner)
    }
}
