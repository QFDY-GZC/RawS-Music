package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import kotlin.concurrent.thread

/**
 * Centralizes decoder ownership handoff used by gapless and crossfade commits.
 *
 * FfmpegAudioPlayer still owns the active decoder fields. This helper only
 * performs the repeated mechanical work: ask the old decoder thread to stop,
 * optionally wake its ring buffer, wait for a bounded time, retire the handle
 * with clear ownership, compute the new ring-buffer size, and start the
 * replacement decoder thread.
 */
internal class DecoderHandoffController(private val tag: String) {
    data class RetireResult(
        val oldHandle: Long,
        val oldThreadAliveAfterJoin: Boolean,
        val closeOwner: CloseOwner,
        val elapsedMs: Double
    ) {
        enum class CloseOwner {
            NONE,
            HANDOFF_HELPER,
            OWNER_THREAD
        }
    }

    fun retireOldDecoder(
        oldHandle: Long,
        oldThread: Thread?,
        oldRingBuffer: RingBuffer?,
        joinTimeoutMs: Long,
        stopToken: DecoderStopToken,
        reason: String
    ): RetireResult {
        if (oldHandle == 0L) {
            return RetireResult(
                oldHandle = 0L,
                oldThreadAliveAfterJoin = oldThread?.isAlive == true,
                closeOwner = RetireResult.CloseOwner.NONE,
                elapsedMs = 0.0
            )
        }

        val startNs = System.nanoTime()
        val ownerThreadAliveAtStart = oldThread != null && oldThread !== Thread.currentThread() && oldThread.isAlive
        // Respect caller-pre-armed close ownership: if the caller already requested
        // owner-thread close on this token, keep that decision even if the thread
        // has since exited — the token's flag is authoritative.
        val closeInOwnerThread =
            stopToken.shouldCloseRetiredHandleInOwnerThread || ownerThreadAliveAtStart
        stopToken.request(
            reason = reason,
            closeRetiredHandleInOwnerThread = closeInOwnerThread
        )

        // Wake a decoder thread blocked in rb.write() before waiting for it.
        try {
            oldRingBuffer?.close()
        } catch (t: Throwable) {
            AppLogger.w(tag, "Decoder handoff: failed to close old ring buffer", t)
        }

        if (oldThread != null && oldThread !== Thread.currentThread() && oldThread.isAlive) {
            try {
                oldThread.interrupt()
                oldThread.join(joinTimeoutMs.coerceAtLeast(0L))
            } catch (t: Throwable) {
                AppLogger.w(tag, "Decoder handoff: interrupted while waiting old decoder", t)
            }
        }

        val aliveAfterJoin = oldThread?.isAlive == true
        val closeOwner = when {
            closeInOwnerThread -> {
                if (aliveAfterJoin) {
                    AppLogger.w(
                        tag,
                        "Decoder handoff: old decoder thread still alive after ${joinTimeoutMs}ms; " +
                            "retired handle=$oldHandle remains owned by old thread token=${stopToken.label}"
                    )
                } else {
                    AppLogger.i(
                        tag,
                        "Decoder handoff: old decoder thread exited; retired handle=$oldHandle was closed by owner thread token=${stopToken.label}"
                    )
                }
                RetireResult.CloseOwner.OWNER_THREAD
            }
            else -> {
                try {
                    FFmpegBridge.closeDecoder(oldHandle)
                } catch (t: Throwable) {
                    AppLogger.w(tag, "Decoder handoff: closeDecoder failed handle=$oldHandle", t)
                }
                RetireResult.CloseOwner.HANDOFF_HELPER
            }
        }

        val elapsedMs = (System.nanoTime() - startNs) / 1_000_000.0
        return RetireResult(
            oldHandle = oldHandle,
            oldThreadAliveAfterJoin = aliveAfterJoin,
            closeOwner = closeOwner,
            elapsedMs = elapsedMs
        )
    }

    fun decoderDurationMs(handle: Long): Long {
        if (handle == 0L) return 0L
        return try {
            FFmpegBridge.getDecoderDuration(handle).let { if (it <= 0L) 0L else it }
        } catch (t: Throwable) {
            AppLogger.w(tag, "Decoder handoff: getDecoderDuration failed handle=$handle", t)
            0L
        }
    }

    fun ringBufferCapacity(
        sampleRate: Int,
        channels: Int,
        bytesPerSample: Int,
        minCapacity: Int
    ): Int {
        val requested = sampleRate.toLong()
            .coerceAtLeast(1L)
            .times(channels.coerceAtLeast(1).toLong())
            .times(bytesPerSample.coerceAtLeast(1).toLong())
            .times(2L)
        return requested
            .coerceAtLeast(minCapacity.coerceAtLeast(1).toLong())
            .coerceAtMost(Int.MAX_VALUE.toLong())
            .toInt()
    }

    fun startDecoderThread(
        name: String,
        handle: Long,
        generation: Int,
        sourcePath: String,
        stopToken: DecoderStopToken,
        loop: (Long, Int, String, DecoderStopToken) -> Unit
    ): Thread {
        return thread(start = true, isDaemon = true, name = name) {
            loop(handle, generation, sourcePath, stopToken)
        }
    }
}
