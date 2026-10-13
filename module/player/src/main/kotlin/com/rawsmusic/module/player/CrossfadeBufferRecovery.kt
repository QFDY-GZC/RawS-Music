package com.rawsmusic.module.player

/** A short recovery ramp on real incoming PCM; it never advances the mixer during missing data. */
internal class CrossfadeBufferRecovery {
    private var total = 0L
    private var left = 0L
    fun reset() { total = 0L; left = 0L }
    fun markMissing(rate: Int) {
        total = (rate.coerceAtLeast(1).toLong() * 150L / 1000L).coerceAtLeast(1L)
        left = total
    }
    fun apply(buffer: ByteArray, length: Int, frameSize: Int, isFloat: Boolean,
              isPacked24: Boolean, bits: Int) {
        if (left <= 0L || frameSize <= 0 || length <= 0) return
        val rampFrames = minOf(left, (length / frameSize).toLong())
        val endLeft = left - rampFrames
        PcmCrossfadeMixer.scaleInPlace(buffer, (rampFrames * frameSize).toInt(), 1f - left.toFloat() / total,
            1f - endLeft.toFloat() / total, frameSize, isFloat, bits, isPacked24)
        left = endLeft
    }
}
