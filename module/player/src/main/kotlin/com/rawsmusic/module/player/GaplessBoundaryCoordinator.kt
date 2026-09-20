package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.transition.NativeTrackSlotState
import com.rawsmusic.module.player.transition.NativeTransitionCapabilities

/**
 * Builds one decoder-domain PCM block containing the current tail and pending head.
 * Decoder ownership is committed only after the caller successfully submits this block.
 */
internal class GaplessBoundaryCoordinator(
    private val tag: String,
    private val stitcher: GaplessStitcher = NativeGaplessStitcher,
) {
    data class Boundary(
        val decoderSerial: Long,
        val ownerGeneration: Int,
        val totalBytes: Int,
        val currentBytes: Int,
        val pendingBytes: Int,
        val totalFrames: Long,
        val pendingFrames: Long,
        val nextPositionMs: Long,
        val nativeBacked: Boolean,
    )

    fun tryStitch(
        outputBuffer: ByteArray,
        pendingScratch: ByteArray,
        currentBytes: Int,
        targetBytes: Int,
        frameSize: Int,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
        generation: Int,
        prepared: GaplessNextDecoder.Prepared?,
        outputIsFloat: Boolean = false,
    ): Boundary? {
        val next = prepared ?: return null
        // The pending decoder holds integer PCM; the current ring may already hold float32.
        // Equal byte widths alone do not make these two sample representations interchangeable.
        if (outputIsFloat && bitsPerSample <= 16) return null
        if (frameSize <= 0 || sampleRate <= 0 || currentBytes <= 0 || currentBytes >= targetBytes) return null
        if (next.ownerGeneration != generation ||
            next.sampleRate != sampleRate ||
            next.channels != channels ||
            next.bitsPerSample != bitsPerSample ||
            next.frameSize != frameSize ||
            (next.nativePrimedQueue && !NativeTrackSlotState.isPendingReady(next.decoderSerial, generation)) ||
            (!next.nativePrimedQueue && NativeTransitionCapabilities.complete)
        ) {
            return null
        }

        if (!next.gaplessTrimTrusted) {
            AppLogger.w(
                tag,
                "Gapless stitch blocked: codec=${next.gaplessMetadata?.codec} " +
                    "trimTrust=${next.gaplessTrustState} serial=${next.decoderSerial}",
            )
            return null
        }

        val alignedCurrent = PcmFrameAligner.alignDown(currentBytes, frameSize)
        val alignedTarget = PcmFrameAligner.alignDown(targetBytes.coerceAtMost(outputBuffer.size), frameSize)
        val needed = alignedTarget - alignedCurrent
        if (alignedCurrent <= 0 || needed <= 0) return null
        val scratchLimit = PcmFrameAligner.alignDown(minOf(needed, pendingScratch.size), frameSize)
        if (scratchLimit <= 0) return null

        val nextRead = next.readPcm(pendingScratch, 0, scratchLimit)
        val alignedNext = PcmFrameAligner.alignDown(nextRead.coerceAtLeast(0), frameSize)
        if (alignedNext <= 0) return null

        if (outputIsFloat) {
            PcmSampleConverter.s32ToFloatPcm(pendingScratch, alignedNext, pendingScratch)
        }

        val stitched = stitcher.stitch(
            outputBuffer = outputBuffer,
            currentBytes = alignedCurrent,
            pendingBuffer = pendingScratch,
            pendingBytes = alignedNext,
            targetBytes = alignedTarget,
            frameSize = frameSize,
        ) ?: return null
        val pendingFrames = stitched.pendingBytes.toLong() / frameSize.toLong()
        val result = Boundary(
            decoderSerial = next.decoderSerial,
            ownerGeneration = generation,
            totalBytes = stitched.totalBytes,
            currentBytes = alignedCurrent,
            pendingBytes = stitched.pendingBytes,
            totalFrames = stitched.totalBytes.toLong() / frameSize.toLong(),
            pendingFrames = pendingFrames,
            nextPositionMs = pendingFrames * 1000L / sampleRate.toLong(),
            nativeBacked = stitched.nativeBacked,
        )
        AppLogger.d(
            tag,
            "Gapless block stitched: currentFrames=${alignedCurrent / frameSize} " +
                "pendingFrames=$pendingFrames totalFrames=${result.totalFrames} " +
                "native=${result.nativeBacked} serial=${result.decoderSerial}",
        )
        return result
    }
}
