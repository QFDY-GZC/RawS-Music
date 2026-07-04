package com.rawsmusic.module.player

import java.util.concurrent.atomic.AtomicBoolean

/**
 * Per-decoder stop flag captured by a decoder thread at startup.
 *
 * A single global stop boolean is unsafe during gapless/crossfade handoff: after
 * the old decoder is asked to stop, the new decoder may be started before the
 * old thread has fully exited. If both threads read the same mutable flag, a
 * later clear for the new decoder can let the old thread resume and touch a
 * retired handle. This token makes the stop request belong to one decoder
 * instance only.
 */
internal class DecoderStopToken(val label: String) {
    private val requested = AtomicBoolean(false)
    private val closeRetiredHandleInOwnerThread = AtomicBoolean(false)
    @Volatile
    private var lastReason: String = ""

    val isStopRequested: Boolean
        get() = requested.get()

    /**
     * True when the decoder thread that captured this token must close its own
     * handle in finally after a handoff stop. This is safer than closing the
     * handle from the new playback thread while the old decoder thread may still
     * be inside FFmpeg.
     */
    val shouldCloseRetiredHandleInOwnerThread: Boolean
        get() = closeRetiredHandleInOwnerThread.get()

    val reason: String
        get() = lastReason

    fun request(reason: String, closeRetiredHandleInOwnerThread: Boolean = false) {
        lastReason = reason
        if (closeRetiredHandleInOwnerThread) {
            this.closeRetiredHandleInOwnerThread.set(true)
        }
        requested.set(true)
    }
}
