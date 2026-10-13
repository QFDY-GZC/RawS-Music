package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs

/**
 * Render-only lyric clock reconciliation.
 *
 * The audio engine may publish source position at block cadence. A display-frame clock must not
 * change speed every time one of those callbacks arrives: doing so turns harmless callback phase
 * jitter into visible "fast/slow" cadence. While playback is continuous the visual clock therefore
 * follows the committed decoder rate from Choreographer frameTimeNanos. Source callbacks detect a
 * genuine discontinuity (seek/track jump) and otherwise update the authority anchor silently.
 */
internal object LyricRenderClockPolicy {
    /** A render/source phase error this large is a real discontinuity, not callback jitter. */
    const val HARD_PHASE_REANCHOR_MS = 700f

    /**
     * The draw clock may extrapolate between 20 Hz player samples, but it must not retain a
     * meaningful lead once a newer authoritative sample arrives. A larger retained lead makes the
     * next karaoke syllable enter with part (or all) of its highlight already painted.
     */
    const val MAX_FORWARD_RENDER_LEAD_MS = 32f

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
        } else if (renderedPositionMs - sourcePositionMs > MAX_FORWARD_RENDER_LEAD_MS) {
            // Keep only a sub-frame/small scheduling budget instead of carrying stale prediction
            // into the next word boundary. Mark this as a re-anchor so attached draw nodes are
            // invalidated immediately rather than showing one stale highlighted frame.
            SourceUpdate(
                (sourcePositionMs + MAX_FORWARD_RENDER_LEAD_MS).coerceAtLeast(0f),
                true,
            )
        } else if (phaseError > MAX_FORWARD_RENDER_LEAD_MS) {
            // Correct persistent lag too, without restarting the display-frame clock. Keeping
            // hundreds of milliseconds of debt until the seek threshold is visibly out of sync.
            SourceUpdate((sourcePositionMs - MAX_FORWARD_RENDER_LEAD_MS).coerceAtLeast(0f), false)
        } else {
            SourceUpdate(renderedPositionMs, false)
        }
    }

    /** Use the decoder rate, never infer speed from jittery source callbacks. */
    fun advance(renderedPositionMs: Float, frameDeltaMs: Float, playbackSpeed: Float = 1f): Float {
        if (!renderedPositionMs.isFinite()) return 0f
        // This is elapsed media time, not a physics step. Capping a delayed display frame loses
        // time permanently and eventually forces a visible jump back to the source clock.
        val dt = frameDeltaMs.takeIf { it.isFinite() }?.coerceAtLeast(0f) ?: 0f
        val rate = playbackSpeed.takeIf { it.isFinite() && it > 0f } ?: 1f
        return renderedPositionMs + dt * rate
    }
}
