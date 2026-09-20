package com.rawsmusic.module.player

/** Byte-exact fallback used by JVM tests and APKs carrying an older native library. */
internal object KotlinGaplessStitcher : GaplessStitcher {
    override fun stitch(
        outputBuffer: ByteArray,
        currentBytes: Int,
        pendingBuffer: ByteArray,
        pendingBytes: Int,
        targetBytes: Int,
        frameSize: Int,
    ): GaplessStitcher.Result? {
        if (frameSize <= 0 || currentBytes <= 0 || pendingBytes <= 0) return null
        val alignedCurrent = PcmFrameAligner.alignDown(currentBytes.coerceAtMost(outputBuffer.size), frameSize)
        val alignedTarget = PcmFrameAligner.alignDown(targetBytes.coerceAtMost(outputBuffer.size), frameSize)
        val alignedPending = PcmFrameAligner.alignDown(pendingBytes.coerceAtMost(pendingBuffer.size), frameSize)
        if (alignedCurrent <= 0 || alignedTarget <= alignedCurrent || alignedPending <= 0) return null
        val copied = minOf(alignedTarget - alignedCurrent, alignedPending)
        if (copied <= 0) return null
        System.arraycopy(pendingBuffer, 0, outputBuffer, alignedCurrent, copied)
        return GaplessStitcher.Result(
            totalBytes = alignedCurrent + copied,
            pendingBytes = copied,
            boundaryFrameOffset = alignedCurrent / frameSize,
            nativeBacked = false,
        )
    }
}
