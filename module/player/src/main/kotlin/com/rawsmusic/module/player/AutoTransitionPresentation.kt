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
}
