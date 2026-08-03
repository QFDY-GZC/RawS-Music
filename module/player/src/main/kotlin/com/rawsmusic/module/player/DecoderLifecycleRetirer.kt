package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger

/**
 * Retires a detached active decoder using the same ownership rules as gapless / rebuild handoff.
 *
 * FfmpegAudioPlayer still owns the mutable active decoder fields.  The player first detaches
 * those fields into [Target], then this helper requests the old decoder thread to stop, wakes
 * its ring buffer, and closes the native handle only when it is safe to do so.
 */
internal class DecoderLifecycleRetirer(
    private val tag: String,
    private val handoff: DecoderHandoffController
) {
    data class Target(
        val handle: Long,
        val thread: Thread?,
        val ringBuffer: RingBuffer?,
        val stopToken: DecoderStopToken,
        val decoderDone: Boolean,
        val label: String
    ) {
        val hasDecoder: Boolean get() = handle != 0L || thread != null || ringBuffer != null
        val threadAlive: Boolean get() = thread?.isAlive == true
    }

    fun retire(
        target: Target,
        reason: String,
        joinTimeoutMs: Long
    ): DecoderHandoffController.RetireResult? {
        if (!target.hasDecoder) return null
        AppLogger.i(
            tag,
            "Decoder lifecycle retire: reason=$reason label=${target.label} " +
                "handle=${target.handle} threadAlive=${target.threadAlive} decoderDone=${target.decoderDone} " +
                "token=${target.stopToken.label} joinTimeoutMs=$joinTimeoutMs"
        )
        if (target.handle == 0L) {
            try {
                target.ringBuffer?.close()
            } catch (t: Throwable) {
                AppLogger.w(tag, "Decoder lifecycle retire: failed to close detached ring buffer reason=$reason", t)
            }
            target.thread?.interrupt()
            return null
        }
        return handoff.retireOldDecoder(
            oldHandle = target.handle,
            oldThread = target.thread,
            oldRingBuffer = target.ringBuffer,
            joinTimeoutMs = joinTimeoutMs.coerceAtLeast(1L),
            stopToken = target.stopToken,
            reason = reason
        )
    }
}
