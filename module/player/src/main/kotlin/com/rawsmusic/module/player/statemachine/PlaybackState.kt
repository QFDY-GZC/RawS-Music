package com.rawsmusic.module.player.statemachine

/**
 * Playback states.
 *
 * State flow:
 * - IDLE      → initial state
 * - PREPARING → decoding/buffering
 * - PLAYING   → actively playing
 * - PAUSED    → paused, decoder preserved
 * - STOPPED   → stopped, decoder released
 * - ERROR     → needs recovery
 */
enum class PlaybackState {
    /** Initial state before any playback. */
    IDLE,

    /** Decoding/buffering, not yet audible. */
    PREPARING,

    /** Actively playing audio. */
    PLAYING,

    /** Paused, decoder state preserved. */
    PAUSED,

    /** Stopped, decoder released. */
    STOPPED,

    /** Error state, needs recovery. */
    ERROR
}
