package com.rawsmusic.module.player.usb

import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger

/** Serializes the bounded stop/decoder-drain phase before entering USB exclusive output. */
internal class UsbExclusiveCutoverCoordinator(
    private val stopPlayback: () -> Unit,
    private val cancelPlaybackWorker: (reason: String, interrupt: Boolean) -> Unit,
    private val awaitWorkerIdle: (timeoutMs: Long) -> Boolean,
    private val decoderThread: () -> Thread?,
    private val tag: String,
) {
    fun stop(timeoutMs: Long): Boolean {
        val oldDecoderThread = decoderThread()
        stopPlayback()
        cancelPlaybackWorker("usb_exclusive_cutover", true)

        val boundedTimeoutMs = timeoutMs.coerceIn(250L, 10_000L)
        val deadline = SystemClock.elapsedRealtime() + boundedTimeoutMs
        val workerIdle = awaitWorkerIdle(boundedTimeoutMs)

        if (oldDecoderThread?.isAlive == true) {
            val remaining = (deadline - SystemClock.elapsedRealtime()).coerceAtLeast(1L)
            runCatching { oldDecoderThread.join(remaining) }
        }

        val decoderExited = oldDecoderThread?.isAlive != true
        val drained = workerIdle && decoderExited
        AppLogger.i(
            tag,
            "stopForUsbExclusiveCutover drained=$drained timeoutMs=$boundedTimeoutMs " +
                "workerIdle=$workerIdle threadAlive=${oldDecoderThread?.isAlive == true}",
        )
        return drained
    }
}
