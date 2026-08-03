package com.rawsmusic.module.player

/**
 * Keeps retired decoder completion from mutating the active playback lane after gapless/crossfade
 * handoff. The retired thread may return EOF after the new handle and ring buffer are already live;
 * only the thread that still owns every active decoder identity component may publish decoderDone.
 */
internal object DecoderCompletionOwnership {
    fun ownsActiveDecoder(
        activeHandle: Long,
        loopHandle: Long,
        sameRingBuffer: Boolean,
        sameStopToken: Boolean,
        stopRequested: Boolean
    ): Boolean {
        return activeHandle == loopHandle &&
            sameRingBuffer &&
            sameStopToken &&
            !stopRequested
    }
}
