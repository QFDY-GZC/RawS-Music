package com.rawsmusic.module.player

/**
 * Optional process-local PCM transformer installed by the app layer.
 *
 * Returning zero means the processor accepted the input but is still preparing
 * delayed output. The playback loop must skip the current write in that case.
 */
interface RealtimePlaybackPcmProcessor {
    val active: Boolean

    fun process(
        buffer: ByteArray,
        byteCount: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        floatEncoding: Boolean,
    ): Int

    /**
     * Signals that the decoder reached EOF and returns delayed PCM.
     *
     * A positive value is ready-to-play PCM, zero means inference is still pending,
     * and -1 means every accepted source frame has been drained.
     */
    fun drain(buffer: ByteArray, maxByteCount: Int): Int = -1

    fun reset(reason: String)
}

object RealtimePlaybackPcmProcessorRegistry {
    @Volatile
    private var processor: RealtimePlaybackPcmProcessor? = null

    fun install(value: RealtimePlaybackPcmProcessor?) {
        processor?.takeIf { it !== value }?.reset("processor_replaced")
        processor = value
    }

    fun process(
        buffer: ByteArray,
        byteCount: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
        floatEncoding: Boolean,
    ): Int {
        val current = processor ?: return byteCount
        if (!current.active) return byteCount
        return current.process(
            buffer = buffer,
            byteCount = byteCount,
            channels = channels,
            sampleRate = sampleRate,
            bitsPerSample = bitsPerSample,
            floatEncoding = floatEncoding,
        )
    }

    fun reset(reason: String) {
        processor?.reset(reason)
    }

    fun drain(buffer: ByteArray, maxByteCount: Int): Int {
        val current = processor ?: return -1
        if (!current.active) return -1
        return current.drain(buffer, maxByteCount)
    }

    fun isActive(): Boolean = processor?.active == true
}
