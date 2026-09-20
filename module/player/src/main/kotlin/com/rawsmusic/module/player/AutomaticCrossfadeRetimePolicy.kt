package com.rawsmusic.module.player

/**
 * Pure quiet-tail decision state. It never changes PCM or gains; it only asks
 * the renderer to compress the remaining automatic timeline to one full lead
 * fade window.
 */
internal class AutomaticCrossfadeRetimePolicy {
    private var quietEvidenceFrames: Long = 0L
    private var requested = false

    fun reset() {
        quietEvidenceFrames = 0L
        requested = false
    }

    fun evaluate(
        source: AutoTransitionPolicy.Source,
        outgoingDb: Float,
        bufferFrames: Long,
        sampleRate: Int,
        scheduledFramesLeft: Long,
    ): Int? {
        if (requested || source != AutoTransitionPolicy.Source.ENVELOPE) return null
        val frames = bufferFrames.coerceAtLeast(1L)
        quietEvidenceFrames = when {
            outgoingDb <= -52f -> quietEvidenceFrames + frames * 5L
            outgoingDb <= -48f -> quietEvidenceFrames + frames * 3L
            outgoingDb <= -40f -> quietEvidenceFrames + frames
            outgoingDb >= -33f -> 0L
            else -> (quietEvidenceFrames - frames / 3L).coerceAtLeast(0L)
        }
        val rate = sampleRate.coerceAtLeast(1)
        val required = rate.toLong() * 340L / 1000L
        val desiredFrames = rate.toLong() * AutoTransitionPolicy.LEAD_FADE_WINDOW_MS / 1000L
        if (quietEvidenceFrames >= required && scheduledFramesLeft > desiredFrames + frames) {
            requested = true
            return AutoTransitionPolicy.LEAD_FADE_WINDOW_MS
        }
        return null
    }
}
