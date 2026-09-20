package com.rawsmusic.module.player

/**
 * Manual short-crossfade renderer. Implementations own the frame timeline and
 * mix the pending slot into the current output block in-place.
 */
internal interface ManualCrossfadeProcessor : AutoCloseable {
    data class Result(
        val processedFrames: Long,
        val completed: Boolean,
        val active: Boolean,
    )

    val isNativeBacked: Boolean

    fun start(durationMs: Int, sampleRate: Int): Boolean

    fun reset(reason: String)

    fun mixInPlace(
        currentBuffer: ByteArray,
        pendingBuffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
    ): Result

    override fun close()
}
