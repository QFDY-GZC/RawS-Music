package com.rawsmusic.module.player

import android.os.SystemClock

/** Throttles decoder PCM callbacks used by the visualizer without touching playback timing. */
internal class PcmWaveformDispatcher(
    private val frameCallback: () -> ((ByteArray, Int, Int, Int, Int, Int) -> Unit)?,
    private val sampleEncoding: (bitsPerSample: Int) -> Int,
) {
    // Keep producer-side PCM copies aligned with the UI delivery cadence. The
    // spectrum worker still analyzes its latest frame at its own cadence, but
    // the playback writer no longer copies the same stream twice as often as
    // the UI can display it.
    private companion object {
        const val DISPATCH_PERIOD_MS = 24L
    }

    private var lastDispatchTime = 0L

    fun dispatch(
        buffer: ByteArray,
        read: Int,
        channels: Int,
        sampleRate: Int,
        bitsPerSample: Int,
    ) {
        if (bitsPerSample <= 1) return
        val callback = frameCallback() ?: return
        val now = SystemClock.elapsedRealtime()
        if (now - lastDispatchTime < DISPATCH_PERIOD_MS) return
        lastDispatchTime = now
        callback(
            buffer,
            read.coerceAtMost(buffer.size),
            channels,
            sampleRate,
            bitsPerSample,
            sampleEncoding(bitsPerSample),
        )
    }
}
