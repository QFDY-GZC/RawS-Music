package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.statemachine.PlaybackState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerPlayStateControlCoordinatorTest {
    @Test
    fun legalTransitionsUpdateThePublicStateSink() {
        val applied = mutableListOf<Pair<PlayState, String>>()
        val coordinator = PlayerPlayStateControlCoordinator(
            applyState = { state, reason, _ -> applied += state to reason },
        )

        assertTrue(coordinator.transition(PlayState.PREPARING, "play"))
        assertTrue(coordinator.transition(PlayState.PLAYING, "prepared"))
        assertEquals(PlaybackState.PLAYING, coordinator.currentStateForTest())
        assertEquals(PlayState.PLAYING to "prepared", applied.last())
    }

    @Test
    fun rejectedTransitionDoesNotPublishAFalseState() {
        val applied = mutableListOf<PlayState>()
        val coordinator = PlayerPlayStateControlCoordinator(
            applyState = { state, _, _ -> applied += state },
        )

        assertFalse(coordinator.transition(PlayState.PLAYING, "illegal_idle_to_playing"))
        assertTrue(applied.isEmpty())
        assertEquals(PlaybackState.IDLE, coordinator.currentStateForTest())
    }

    @Test
    fun recoveryCanForceTheObservedAndInternalStateTogether() {
        var observed = PlayState.IDLE
        val coordinator = PlayerPlayStateControlCoordinator(
            applyState = { state, _, _ -> observed = state },
        )

        coordinator.forceTransition(PlayState.PAUSED, "usb_recovery")

        assertEquals(PlayState.PAUSED, observed)
        assertEquals(PlaybackState.PAUSED, coordinator.currentStateForTest())
    }
}
