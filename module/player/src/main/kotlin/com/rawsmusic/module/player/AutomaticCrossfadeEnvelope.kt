package com.rawsmusic.module.player

/** Pure lead/follow gain math shared by the Kotlin fallback and unit tests. */
internal object AutomaticCrossfadeEnvelope {
    data class Config(
        val totalFrames: Long,
        val sampleRate: Int,
        val pivotFrames: Long = totalFrames * AutoTransitionPolicy.SMART_PIVOT_PERCENT / 100L,
    )

    /**
     * Independent linear amplitude ramps using the same envelope semantics as the native path. The smart
     * pivot changes when the two ramps reach equal ownership without changing either endpoint.
     */
    fun gains(
        localFrame: Long,
        config: Config,
        startOutgoingGain: Float,
        startIncomingGain: Float,
    ): Pair<Float, Float> {
        val progress = transitionProgress(localFrame, config.totalFrames, config.pivotFrames)
        val outgoing = lerp(startOutgoingGain.coerceIn(0f, 1f), 0f, progress)
        val incoming = lerp(startIncomingGain.coerceIn(0f, 1f), 1f, progress)
        return outgoing.coerceIn(0f, 1f) to incoming.coerceIn(0f, 1f)
    }

    /** A louder follow track moves equal ownership later; a quieter one moves it earlier. */
    fun loudnessAdaptivePivotFraction(
        basePivotFraction: Float,
        outgoingDb: Float,
        incomingDb: Float,
    ): Float {
        val lead = outgoingDb.takeIf { it.isFinite() } ?: -18f
        val follow = incomingDb.takeIf { it.isFinite() } ?: lead
        val adjustment = (follow - lead).coerceIn(-12f, 12f) / 80f
        return (basePivotFraction + adjustment).coerceIn(0.35f, 0.75f)
    }

    private fun transitionProgress(localFrame: Long, totalFrames: Long, pivotFrames: Long): Float {
        val duration = totalFrames.coerceAtLeast(1L)
        val local = localFrame.coerceIn(0L, duration)
        if (local <= 0L) return 0f
        if (local >= duration) return 1f
        val pivot = pivotFrames.coerceIn(1L, (duration - 1L).coerceAtLeast(1L))
        return if (local <= pivot) {
            0.5f * local.toFloat() / pivot.toFloat()
        } else {
            0.5f + 0.5f * (local - pivot).toFloat() / (duration - pivot).toFloat()
        }
    }

    private fun lerp(start: Float, end: Float, progress: Float): Float =
        start + (end - start) * progress.coerceIn(0f, 1f)
}
