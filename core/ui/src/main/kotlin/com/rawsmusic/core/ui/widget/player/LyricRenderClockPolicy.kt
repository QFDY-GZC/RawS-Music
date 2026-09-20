package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs

/**
 * Render-only lyric clock reconciliation.
 *
 * The audio engine may publish source position at block cadence. A display-frame clock must not
 * change speed every time one of those callbacks arrives: doing so turns harmless callback phase
 * jitter into visible "fast/slow" cadence. While playback is continuous the visual clock therefore
 * runs at exactly 1x from Choreographer frameTimeNanos. Source callbacks are used only to detect a
 * genuine discontinuity (seek/track jump) and otherwise update the authority anchor silently.
 */
internal object LyricRenderClockPolicy {
    /** A render/source phase error this large is a real discontinuity, not callback jitter. */
    const val HARD_PHASE_REANCHOR_MS = 700f

    /** Backward source movement during PLAYING is a seek unless it is tiny timestamp quantisation. */
    const val BACKWARD_SOURCE_JUMP_MS = 120f

    data class SourceUpdate(
        val positionMs: Float,
        val hardReanchored: Boolean,
    )

    fun onSourceUpdate(
        renderedPositionMs: Float,
        previousSourcePositionMs: Float,
        sourcePositionMs: Float,
    ): SourceUpdate {
        if (!renderedPositionMs.isFinite() || !sourcePositionMs.isFinite()) {
            return SourceUpdate(sourcePositionMs.coerceAtLeast(0f), true)
        }
        val phaseError = sourcePositionMs - renderedPositionMs
        val sourceDelta = sourcePositionMs - previousSourcePositionMs
        val discontinuity =
            abs(phaseError) >= HARD_PHASE_REANCHOR_MS ||
                sourceDelta <= -BACKWARD_SOURCE_JUMP_MS
        return if (discontinuity) {
            SourceUpdate(sourcePositionMs.coerceAtLeast(0f), true)
        } else {
            SourceUpdate(renderedPositionMs, false)
        }
    }

    /** Continuous playback is exactly display-time 1x. No source-callback rate modulation. */
    fun advance(renderedPositionMs: Float, frameDeltaMs: Float): Float {
        if (!renderedPositionMs.isFinite()) return 0f
        // This is elapsed media time, not a physics step. Capping a delayed display frame loses
        // time permanently and eventually forces a visible jump back to the source clock.
        val dt = frameDeltaMs.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        return renderedPositionMs + dt
    }
}
