package com.rawsmusic.module.player

/**
 * Per-renderer transport envelope contract.
 *
 * Commands may be published from a controller thread. PCM processing happens on
 * the renderer thread immediately before the output write boundary.
 */
internal interface TransportFadeProcessor : AutoCloseable {
    val isActive: Boolean
    val isNativeBacked: Boolean
    val currentGain: Float

    fun startFadeIn(durationMs: Int, reason: String)
    fun startFadeOut(durationMs: Int, reason: String)
    fun clear(reason: String)

    fun processInPlace(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
    )

    override fun close() = Unit
}
