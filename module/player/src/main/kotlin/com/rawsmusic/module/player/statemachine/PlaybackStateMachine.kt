package com.rawsmusic.module.player.statemachine

import android.util.Log
import java.util.concurrent.atomic.AtomicReference

/**
 * Playback state machine with transition legality checking.
 *
 * Design:
 * - States: IDLE → PREPARING → PLAYING ↔ PAUSED → STOPPED, plus ERROR
 * - Transitions are validated before execution; illegal transitions are rejected
 * - seek() does not change state
 *
 * Uses a direct Kotlin implementation for compile-time checking of state names.
 */
class PlaybackStateMachine {

    companion object {
        private const val TAG = "PlaybackStateMachine"

        /**
         * Legal transitions table.
         * Key = target state, Value = set of allowed source states.
         *
         * Transition rules:
         * - play(): from=*, to=PREPARING
         * - prepared(): from=PREPARING/STOPPED, to=PLAYING
         * - pause(): from=PLAYING, to=PAUSED
         * - resume(): from=PAUSED, to=PLAYING
         * - stop(): from=*, to=STOPPED
         * - error(): from=*, to=ERROR
         * - seek(): NotChange (does not appear in this table)
         */
        private val TRANSITIONS: Map<PlaybackState, Set<PlaybackState>> = mapOf(
            PlaybackState.PREPARING to setOf(
                PlaybackState.IDLE,
                PlaybackState.PREPARING,
                PlaybackState.PLAYING,
                PlaybackState.PAUSED,
                PlaybackState.STOPPED,
                PlaybackState.ERROR
            ),
            PlaybackState.PLAYING to setOf(
                PlaybackState.PREPARING,
                PlaybackState.STOPPED
            ),
            PlaybackState.PAUSED to setOf(
                PlaybackState.PLAYING
            ),
            PlaybackState.STOPPED to setOf(
                PlaybackState.PREPARING,
                PlaybackState.PLAYING,
                PlaybackState.PAUSED,
                PlaybackState.STOPPED,
                PlaybackState.ERROR
            ),
            PlaybackState.ERROR to setOf(
                PlaybackState.PREPARING,
                PlaybackState.PLAYING,
                PlaybackState.PAUSED,
                PlaybackState.STOPPED,
                PlaybackState.ERROR
            )
        )
    }

    private val stateRef = AtomicReference(PlaybackState.IDLE)

    /** Current playback state. Thread-safe via AtomicReference. */
    val currentState: PlaybackState
        get() = stateRef.get()

    /**
     * Check whether a transition from [currentState] to [target] is legal.
     */
    fun canTransition(target: PlaybackState): Boolean {
        val allowed = TRANSITIONS[target] ?: return false
        return allowed.contains(stateRef.get())
    }

    /**
     * Attempt a state transition.
     *
     * If the transition is legal, atomically updates the state and returns true.
     * If illegal, logs a warning and returns false.
     *
     * @param target the desired target state
     * @param tag optional caller tag for logging (e.g. "play", "pause")
     * @return true if the transition succeeded
     */
    fun transition(target: PlaybackState, tag: String = ""): Boolean {
        val from = stateRef.get()
        val allowed = TRANSITIONS[target]
        if (allowed == null || !allowed.contains(from)) {
            Log.w(TAG, "Illegal transition: $from → $target${if (tag.isNotEmpty()) " ($tag)" else ""}, rejected")
            return false
        }
        val ok = stateRef.compareAndSet(from, target)
        if (ok) {
            Log.d(TAG, "Transition: $from → $target${if (tag.isNotEmpty()) " ($tag)" else ""}")
        }
        return ok
    }

    /**
     * Force a state transition, bypassing legality check.
     *
     * Emergency use only — for recovery paths where the state machine may be
     * out of sync with reality (e.g. USB recovery after a crash).
     */
    fun forceTransition(target: PlaybackState, tag: String = "") {
        val from = stateRef.getAndSet(target)
        Log.w(TAG, "FORCED transition: $from → $target${if (tag.isNotEmpty()) " ($tag)" else ""}")
    }

    /** Reset to IDLE state. */
    fun reset() {
        stateRef.set(PlaybackState.IDLE)
    }

    /** True if currently in PLAYING state. */
    val isPlaying: Boolean
        get() = stateRef.get() == PlaybackState.PLAYING

    /** True if currently in PAUSED state. */
    val isPaused: Boolean
        get() = stateRef.get() == PlaybackState.PAUSED

    /** True if currently in PREPARING state. */
    val isPreparing: Boolean
        get() = stateRef.get() == PlaybackState.PREPARING

    /** True if currently in STOPPED or IDLE state. */
    val isStopped: Boolean
        get() = stateRef.get() == PlaybackState.STOPPED || stateRef.get() == PlaybackState.IDLE

    /** True if currently in ERROR state. */
    val isError: Boolean
        get() = stateRef.get() == PlaybackState.ERROR
}
