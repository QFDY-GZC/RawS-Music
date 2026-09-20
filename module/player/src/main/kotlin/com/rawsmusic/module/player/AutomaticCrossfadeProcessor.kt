package com.rawsmusic.module.player

/**
 * Renderer-owned automatic lead/follow crossfade.
 *
 * The policy supplies a recipe once. Implementations own the real-time frame
 * timeline and may retime a quiet tail without resetting the current gains.
 */
internal interface AutomaticCrossfadeProcessor : AutoCloseable {
    data class Result(
        val consumedFrames: Long,
        val completed: Boolean,
        val active: Boolean,
        val retimed: Boolean,
    )

    val isNativeBacked: Boolean

    fun start(plan: AutoTransitionStartPlan, sampleRate: Int): Boolean

    fun retime(durationMs: Int): Boolean

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
