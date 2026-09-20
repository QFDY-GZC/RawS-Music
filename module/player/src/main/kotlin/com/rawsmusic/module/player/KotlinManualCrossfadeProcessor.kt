package com.rawsmusic.module.player

/**
 * Compatibility implementation used by JVM tests and installations that still
 * carry an older native library. It intentionally uses the same linear slot
 * envelopes as the native Phase-4 processor.
 */
internal class KotlinManualCrossfadeProcessor : ManualCrossfadeProcessor {
    override val isNativeBacked: Boolean = false

    private var totalFrames: Long = 0L
    private var processedFrames: Long = 0L
    private var active: Boolean = false

    override fun start(durationMs: Int, sampleRate: Int): Boolean {
        if (durationMs <= 0 || sampleRate <= 0) return false
        totalFrames = (durationMs.toLong() * sampleRate.toLong() / 1000L).coerceAtLeast(1L)
        processedFrames = 0L
        active = true
        return true
    }

    override fun reset(reason: String) {
        totalFrames = 0L
        processedFrames = 0L
        active = false
    }

    override fun mixInPlace(
        currentBuffer: ByteArray,
        pendingBuffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean,
        outputIsPacked24: Boolean,
    ): ManualCrossfadeProcessor.Result {
        if (!active || length <= 0 || frameSize <= 0) {
            return ManualCrossfadeProcessor.Result(processedFrames, completed = false, active = active)
        }
        val aligned = PcmFrameAligner.alignDown(length, frameSize)
        if (aligned <= 0) {
            return ManualCrossfadeProcessor.Result(processedFrames, completed = false, active = active)
        }
        val frames = aligned / frameSize
        val remainingFadeFrames = (totalFrames - processedFrames).coerceAtLeast(0L)
        val fadeFrames = minOf(frames.toLong(), remainingFadeFrames).toInt()
        if (fadeFrames > 0) {
            val fadeBytes = fadeFrames * frameSize
            val startProgress = (processedFrames.toDouble() / totalFrames.toDouble())
                .coerceIn(0.0, 1.0).toFloat()
            val lastFadeFrame = processedFrames + fadeFrames - 1L
            val endProgress = (lastFadeFrame.toDouble() / totalFrames.toDouble())
                .coerceIn(0.0, 1.0).toFloat()
            PcmCrossfadeMixer.mixInPlace(
                currentBuf = currentBuffer,
                currentLen = offset + fadeBytes,
                nextBuf = pendingBuffer,
                nextLen = offset + fadeBytes,
                gainOut = 1f - startProgress,
                gainIn = startProgress,
                gainOutEnd = 1f - endProgress,
                gainInEnd = endProgress,
                frameSize = frameSize,
                outputIsFloat = outputIsFloat,
                bitsPerSample = bitsPerSample,
                outputIsPacked24 = outputIsPacked24,
                currentOffset = offset,
                nextOffset = offset,
            )
        }
        if (frames > fadeFrames) {
            val pendingOnlyStart = offset + fadeFrames * frameSize
            val pendingOnlyEnd = offset + aligned
            pendingBuffer.copyInto(currentBuffer, pendingOnlyStart, pendingOnlyStart, pendingOnlyEnd)
        }
        processedFrames = (processedFrames + frames).coerceAtMost(totalFrames)
        val completed = processedFrames >= totalFrames
        if (completed) active = false
        return ManualCrossfadeProcessor.Result(processedFrames, completed, active)
    }

    override fun close() = reset("close")
}
