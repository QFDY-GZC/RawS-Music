package com.rawsmusic.module.player.transition

/**
 * Stable reason codes for the playback handoff timeline.
 *
 * These values are intentionally independent from UI actions and renderer backends so the same
 * token can follow a request from Kotlin policy code into the future native two-slot engine.
 */
internal enum class PlaybackTransitionReason {
    PLAY,
    MANUAL_TRACK_CHANGE,
    NATURAL_GAPLESS,
    MANUAL_CROSSFADE,
    AUTO_CROSSFADE,
    SEEK,
    PAUSE,
    RESUME,
    STOP,
    OUTPUT_REBUILD,
    RECOVERY,
    RELEASE,
}

/** Ordered milestones emitted by the current player and, later, the native transition engine. */
internal enum class PlaybackTransitionPhase {
    SESSION_BEGIN,
    REQUESTED,
    DECODER_OPEN_BEGIN,
    DECODER_OPENED,
    DECODER_READY,
    DECODER_FAILED,
    OUTPUT_GENERATION,
    OUTPUT_START,
    OUTPUT_FLUSH,
    OUTPUT_STOP,
    FIRST_PCM_SUBMITTED,
    FIRST_MIXED_FRAME,
    // Reserved for a future hardware-presentation timestamp. Phase 0 does not fake this from write().
    FIRST_AUDIBLE_FRAME,
    FADE_BEGIN,
    FADE_END,
    SEEK_BARRIER_ARMED,
    SEEK_DECODER_COMMITTED,
    SEEK_OUTPUT_COMMITTED,
    HANDOFF_BEGIN,
    TRACK_STARTED,
    HANDOFF_COMMITTED,
    TRACK_RETIRED,
    STATE_CHANGED,
    UNDERRUN,
    COMPLETED,
    CANCELLED,
    FAILED,
}

internal data class PlaybackTransitionToken(
    val sessionId: Long,
    val transitionId: Long,
    val generation: Int,
    val reason: PlaybackTransitionReason,
    val sourcePath: String?,
    val targetPath: String?,
    val startedAtNanos: Long,
)

internal data class PlaybackTransitionTraceEvent(
    val sequence: Long,
    val elapsedNanos: Long,
    val token: PlaybackTransitionToken?,
    val phase: PlaybackTransitionPhase,
    val outputGeneration: Long,
    val sourceFrame: Long,
    val renderedFrame: Long,
    val readyFrames: Long,
    val underrunFrames: Long,
    val detail: String,
)

internal data class PlaybackTransitionTraceSnapshot(
    val sessionId: Long,
    val generation: Int,
    val outputGeneration: Long,
    val activeTransitionIds: Set<Long>,
    val events: List<PlaybackTransitionTraceEvent>,
)
