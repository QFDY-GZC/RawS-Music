package com.rawsmusic.module.player.usb

/** Pure sizing math shared by the USB feeder and runtime diagnostics. */
internal object UsbOutputSizingPolicy {
    fun recommendedWriteChunkBytes(
        deviceBytesPerSecond: Long,
        frameBytes: Int,
        targetMs: Long = 32L,
    ): Int {
        val frame = frameBytes.coerceAtLeast(1)
        val timed = ((deviceBytesPerSecond.coerceAtLeast(1L) * targetMs) / 1000L)
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
        val min = frame * 256
        val max = frame * 8192
        val bounded = timed.coerceIn(min, max)
        return bounded - (bounded % frame)
    }

    fun runtimeBytesPerSecond(sampleRate: Int, channels: Int, subslotSize: Int): Int {
        val sr = sampleRate.takeIf { it > 0 } ?: return 0
        val ch = channels.takeIf { it > 0 } ?: return 0
        val subslot = subslotSize.takeIf { it > 0 } ?: return 0
        return (sr.toLong() * ch.toLong() * subslot.toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }
}
