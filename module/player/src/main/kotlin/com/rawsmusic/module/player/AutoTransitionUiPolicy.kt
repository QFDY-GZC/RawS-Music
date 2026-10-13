package com.rawsmusic.module.player

internal object AutoTransitionUiPolicy {
    fun ready(rendered: Long, total: Long, audible: Long, rate: Int, completed: Boolean,
              gentleFrames: Long = 0L, outgoingGain: Float = 1f,
              dominantFrames: Long = 0L, incomingGain: Float = 1f - outgoingGain,
              outgoingDb: Float = 0f): Boolean {
        if (completed) return true
        if (total <= 0L || rate <= 0 || !outgoingGain.isFinite() || !incomingGain.isFinite()) return false
        val stable = rate.toLong() * 500L / 1000L
        if (audible < stable || incomingGain < .1f) return false
        // Time alone says nothing about which track the listener still hears.
        return dominantFrames >= stable &&
            (outgoingGain <= .12f || effectiveDb(outgoingDb, outgoingGain) <= -42f)
    }

    fun effectiveDb(rawDb: Float, gain: Float): Float = if (!rawDb.isFinite() || gain <= 0f) -120f
        else rawDb + 20f * kotlin.math.log10(gain.coerceIn(.000001f, 1f))

    fun incomingDominates(outgoingGain: Float, incomingDb: Float, outgoingDb: Float,
                          incomingGain: Float = 1f - outgoingGain): Boolean {
        if (!outgoingGain.isFinite()) return false
        val old = outgoingGain.coerceIn(0f, 1f)
        val next = incomingGain.coerceIn(0f, 1f)
        val effectiveNext = effectiveDb(incomingDb, next)
        val effectiveOld = effectiveDb(outgoingDb, old)
        return next >= .1f && effectiveNext > -48f &&
            (old <= .12f || effectiveOld <= -42f) && effectiveNext >= effectiveOld + 6f
    }
}
