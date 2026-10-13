package com.rawsmusic.module.player

/** UI-facing lifecycle of an automatic transition. Audio mixing remains renderer-owned. */
data class AutoTransitionPresentation(
    val phase: Phase = Phase.IDLE,
    val targetPath: String? = null,
) {
    enum class Phase { IDLE, ARMED, ACTIVE }

    val visible: Boolean get() = phase != Phase.IDLE

    companion object {
        val Idle = AutoTransitionPresentation()
    }
}

internal object AutoTransitionPresentationPolicy {
    const val LYRICS_PREVIEW_LEAD_MS = 10_000L
    // Unlike the lyrics recipe, envelope detection has no known mixing start time. Preview
    // when its tail-analysis window opens, rather than publishing ARMED in the same renderer
    // iteration that starts the mixer. Cap long sparse-lyric windows to avoid a minute-long label.
    const val ENVELOPE_PREVIEW_MAX_LEAD_MS = 30_000L

    fun shouldArmLyrics(
        positionMs: Long,
        handoverPositionMs: Long?,
        targetPreparedAndCompatible: Boolean,
    ): Boolean {
        val handover = handoverPositionMs ?: return false
        return targetPreparedAndCompatible &&
            positionMs >= handover - LYRICS_PREVIEW_LEAD_MS &&
            positionMs < handover
    }

    fun shouldArmEnvelope(
        remainingMs: Long,
        envelopeLookaheadMs: Long,
        targetPreparedAndCompatible: Boolean,
    ): Boolean {
        if (!targetPreparedAndCompatible) return false
        val previewWindowMs = envelopeLookaheadMs
            .coerceAtMost(ENVELOPE_PREVIEW_MAX_LEAD_MS)
        return previewWindowMs > 0L && remainingMs in 1L..previewWindowMs
    }
}
