package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.statemachine.PlaybackState
import com.rawsmusic.module.player.statemachine.PlaybackStateMachine

/**
 * Owns legal/forced public playback-state transitions.
 *
 * PlayerController supplies the observable state sink and the system-audio identity callback; the
 * state machine and model mapping no longer live in the already oversized controller.
 */
internal class PlayerPlayStateControlCoordinator(
    private val applyState: (PlayState, String, Boolean) -> Unit,
    private val stateMachine: PlaybackStateMachine = PlaybackStateMachine(),
) {
    fun transition(target: PlayState, reason: String = ""): Boolean {
        val accepted = stateMachine.transition(target.toPlaybackState(), reason)
        if (accepted) applyState(target, reason, false)
        return accepted
    }

    fun forceTransition(target: PlayState, reason: String = "") {
        stateMachine.forceTransition(target.toPlaybackState(), reason)
        applyState(target, reason, true)
    }

    internal fun currentStateForTest(): PlaybackState = stateMachine.currentState

    private fun PlayState.toPlaybackState(): PlaybackState = when (this) {
        PlayState.IDLE -> PlaybackState.IDLE
        PlayState.PREPARING -> PlaybackState.PREPARING
        PlayState.PLAYING -> PlaybackState.PLAYING
        PlayState.PAUSED -> PlaybackState.PAUSED
        PlayState.STOPPED -> PlaybackState.STOPPED
        PlayState.ERROR -> PlaybackState.ERROR
    }
}
