package com.rawsmusic.module.player

/** Pure lead/follow gain math shared by the Kotlin fallback and unit tests. */
internal object AutomaticCrossfadeEnvelope {
    const val GENTLE_GAIN = .05f
    const val MIN_GENTLE_GAIN = .015f

    fun gentleEntryGain(outgoingDb: Float, incomingDb: Float): Float {
        if (!outgoingDb.isFinite() || !incomingDb.isFinite() || outgoingDb <= -60f || incomingDb <= -60f)
            return MIN_GENTLE_GAIN
        val louderBy = (incomingDb - outgoingDb).coerceAtLeast(0f)
        return (GENTLE_GAIN * Math.pow(10.0, -louderBy / 20.0).toFloat())
            .coerceIn(MIN_GENTLE_GAIN, GENTLE_GAIN)
    }
    data class Config(
        val totalFrames: Long,
        val sampleRate: Int,
        val pivotFrames: Long = totalFrames * AutoTransitionPolicy.SMART_PIVOT_PERCENT / 100L,
        val gentleFrames: Long = 0L,
        val gentleIncomingGain: Float = GENTLE_GAIN,
    )

    /**
     * Gains matching the native path. A protected vocal window reaches a small overlap first,
     * then clears its outgoing tail without changing the incoming ramp. Other plans retain
     * their complementary adaptive linear ramps.
     */
    fun gains(
        localFrame: Long,
        config: Config,
        startOutgoingGain: Float,
        startIncomingGain: Float,
    ): Pair<Float, Float> {
        val entry = config.gentleIncomingGain.coerceIn(MIN_GENTLE_GAIN, GENTLE_GAIN)
        val progress = if (config.gentleFrames > 0L) {
            val lead = config.gentleFrames.coerceAtMost(config.totalFrames - 1L).coerceAtLeast(1L)
            if (localFrame <= lead) {
                val t = (localFrame.toFloat() / minOf(lead, 2L * config.sampleRate.coerceAtLeast(1))).coerceIn(0f, 1f)
                entry * t * t * (3f - 2f * t)
            } else {
                val t = ((localFrame - lead).toFloat() / (config.totalFrames - lead).coerceAtLeast(1L)).coerceIn(0f, 1f)
                val original = kotlin.math.exp(kotlin.math.ln(.15f) * (1f - t * t * (3f - 2f * t)))
                entry + (1f - entry) * (original - .15f) / .85f
            }
        } else transitionProgress(localFrame, config.totalFrames, config.pivotFrames)
        var outgoing = lerp(startOutgoingGain.coerceIn(0f, 1f), 0f, progress)
        if (config.gentleFrames > 0L) {
            // Clear the protected vocal tail over the main exchange, ending at 90% incoming.
            // Incoming retains its original envelope, so clearing the tail cannot boost it.
            val tail = ((progress - entry) / (.9f - entry)).coerceIn(0f, 1f)
            outgoing *= 1f - tail * tail * (3f - 2f * tail)
        }
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
