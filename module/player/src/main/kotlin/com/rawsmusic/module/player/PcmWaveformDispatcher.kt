package com.rawsmusic.module.player

import android.os.SystemClock

/** Throttles decoder PCM callbacks used by the visualizer without touching playback timing. */
internal class PcmWaveformDispatcher(
    private val frameCallback: () -> ((ByteArray, Int, Int, Int, Int, Int) -> Unit)?,
    private val sampleEncoding: (bitsPerSample: Int) -> Int,
) {
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
        if (now - lastDispatchTime < 12L) return
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
