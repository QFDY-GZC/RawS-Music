package com.rawsmusic.module.player

/** Facade for transport fade policy and PCM fade application. */
internal class FfmpegPlaybackFadeCoordinator(
    private val runtime: PlaybackFadeRuntime,
    private val useFloatOutput: () -> Boolean,
    private val usePacked24Output: () -> Boolean,
    private val pausePlayback: () -> Unit,
) {
    fun suppressNextStartFadeIn(reason: String) = runtime.suppressNextStartFadeIn(reason)

    fun armNextStartFadeIn(durationMs: Int, reason: String) =
        runtime.armNextStartFadeIn(durationMs, reason)

    fun fadeOutForTransitionBlocking(durationMs: Int, reason: String): Boolean =
        runtime.fadeOutForTransitionBlocking(durationMs, reason)

    fun armConfiguredStartFade(reason: String) = runtime.armConfiguredStartFade(reason)

    fun armSeekFadeIn(durationMs: Int, reason: String) = runtime.armSeekFadeIn(durationMs, reason)

    fun armDefaultStartFadeIn(durationMs: Int, reason: String) =
        runtime.armDefaultStartFadeIn(durationMs, reason)

    fun pauseWithFadeBlocking(durationMs: Int, reason: String) =
        runtime.pauseWithFadeBlocking(durationMs, reason, pausePlayback)

    fun process(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean = useFloatOutput(),
        outputIsPacked24: Boolean = usePacked24Output(),
    ) {
        runtime.process(
            buffer = buffer,
            offset = offset,
            length = PcmFrameAligner.alignDown(length, frameSize),
            sampleRate = sampleRate,
            frameSize = frameSize,
            bitsPerSample = bitsPerSample,
            outputIsFloat = outputIsFloat,
            outputIsPacked24 = outputIsPacked24,
        )
    }
}
