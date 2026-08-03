package com.rawsmusic.module.player

/**
 * Frame-alignment helpers for PCM read/write sizing.
 *
 * Ensures byte counts fed to AudioTrack are always whole sample frames, which is
 * critical for packed-24 (3 bytes/sample) where a misaligned chunk produces a
 * byte slip heard as a "motor" / "engine" artifact.
 */
internal object PcmFrameAligner {
    fun alignDown(bytes: Int, frameSize: Int): Int {
        if (bytes <= 0 || frameSize <= 1) return bytes.coerceAtLeast(0)
        return bytes - (bytes % frameSize)
    }

    fun alignUp(bytes: Int, frameSize: Int): Int {
        if (bytes <= 0 || frameSize <= 1) return bytes.coerceAtLeast(0)
        val rem = bytes % frameSize
        return if (rem == 0) bytes else bytes + (frameSize - rem)
    }

    fun readLimit(maxBytes: Int, frameSize: Int): Int {
        val aligned = alignDown(maxBytes, frameSize)
        return if (aligned > 0) aligned else maxBytes.coerceAtLeast(0)
    }
}
