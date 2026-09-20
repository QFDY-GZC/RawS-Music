package com.rawsmusic.module.player

import android.os.Process
import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * Continuously decodes a prepared next track on its own producer thread.
 *
 * The renderer is never allowed to call [PlaybackDecoderBridge.decodeChunk] for a pending
 * track. PCM is written into the fixed native pending-slot queue and consumed by
 * crossfade/gapless readers. Before ownership commit the producer is frozen at a
 * decoder chunk boundary, so the active decoder loop can safely take over the
 * same playback decoder handle without concurrent native calls or skipped PCM.
 */
internal class PendingDecoderPcmProducer(
    private val tag: String,
    private val handle: Long,
    private val path: String,
    private val generation: Int,
    private val frameSize: Int,
    private val minimumReadyFrames: Long,
    private val handoffReadyFrames: Long,
    private val pcm: PreparedPcmBuffer,
    private val gaplessAuditRegistry: DecoderGaplessAuditRegistry,
    private val isGenerationCurrent: (Int) -> Boolean,
    private val decodeChunk: (Long, ByteArray, Int, Int) -> Int = PlaybackDecoderBridge::decodeChunk,
    private val closeDecoder: (Long) -> Unit = PlaybackDecoderBridge::closeDecoder,
) {
    enum class TerminalState {
        RUNNING,
        EOF,
        FAILED,
        CANCELLED,
        TRANSFERRED,
    }

    data class ReadyResult(
        val ready: Boolean,
        val readyFrames: Long,
        val eof: Boolean,
        val failureReason: String?,
    )

    private val monitor = Object()
    private val started = AtomicBoolean(false)
    private val handleClosed = AtomicBoolean(false)

    @Volatile private var terminalState = TerminalState.RUNNING
    @Volatile private var failureReason: String? = null
    @Volatile private var freezeRequested = false
    @Volatile private var frozen = false
    @Volatile private var transferRequested = false
    @Volatile private var cancelRequested = false
    @Volatile private var closeHandleOnExit = false
    @Volatile private var producerThread: Thread? = null

    val isEof: Boolean get() = terminalState == TerminalState.EOF
    val isFailed: Boolean get() = terminalState == TerminalState.FAILED
    val state: TerminalState get() = terminalState

    fun start() {
        if (!started.compareAndSet(false, true)) return
        producerThread = thread(
            start = true,
            isDaemon = true,
            name = "RawS Pending Decoder",
        ) { runProducer() }
    }

    fun awaitReady(timeoutMs: Long): ReadyResult {
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        synchronized(monitor) {
            while (true) {
                val frames = pcm.availableFrames
                val eof = terminalState == TerminalState.EOF
                val ready = frames >= minimumReadyFrames || (eof && frames > 0L)
                if (ready) return ReadyResult(true, frames, eof, null)
                if (terminalState == TerminalState.FAILED || terminalState == TerminalState.CANCELLED) {
                    return ReadyResult(false, frames, false, failureReason ?: terminalState.name.lowercase())
                }
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    return ReadyResult(false, frames, eof, "ready_timeout_${timeoutMs}ms")
                }
                val waitMs = (remainingNs / 1_000_000L).coerceIn(1L, 50L)
                monitor.wait(waitMs)
            }
        }
    }

    fun requestFreezeForHandoff() {
        synchronized(monitor) {
            if (terminalState != TerminalState.RUNNING) return
            freezeRequested = true
            monitor.notifyAll()
        }
    }

    /**
     * Freezes the producer at a decoder chunk boundary. This method is bounded;
     * it never waits for arbitrary decoder I/O while holding a player lock.
     */
    fun freezeForHandoff(timeoutMs: Long): Boolean {
        val deadlineNs = System.nanoTime() + timeoutMs.coerceAtLeast(1L) * 1_000_000L
        synchronized(monitor) {
            if (terminalState == TerminalState.EOF) return true
            if (terminalState != TerminalState.RUNNING) return false
            freezeRequested = true
            monitor.notifyAll()
            while (!frozen) {
                if (terminalState == TerminalState.EOF) return true
                if (terminalState != TerminalState.RUNNING) return false
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) {
                    freezeRequested = false
                    monitor.notifyAll()
                    return false
                }
                monitor.wait((remainingNs / 1_000_000L).coerceIn(1L, 10L))
            }
            return true
        }
    }

    fun resumeAfterRejectedHandoff() {
        synchronized(monitor) {
            if (terminalState != TerminalState.RUNNING) return
            freezeRequested = false
            frozen = false
            monitor.notifyAll()
        }
    }

    /**
     * Ends the pending producer without closing the decoder handle, drains every
     * queued byte into [target], and leaves the handle ready for the standard
     * active decoder loop. Must be called only after a successful native slot
     * commit while the producer is frozen.
     */
    fun completeHandoff(target: RingBuffer, writeChunk: ((ByteArray, Int) -> Int)? = null): Int {
        synchronized(monitor) {
            transferRequested = true
            freezeRequested = false
            monitor.notifyAll()
        }
        joinProducerUninterruptibly()
        return pcm.drainInto(target, writeChunk)
    }

    /** Cancels the pending worker. The worker owns closing its FFmpeg handle. */
    fun cancelAndClose(reason: String, joinTimeoutMs: Long = 500L) {
        synchronized(monitor) {
            if (terminalState == TerminalState.TRANSFERRED) return
            failureReason = reason
            cancelRequested = true
            closeHandleOnExit = true
            freezeRequested = false
            monitor.notifyAll()
        }
        producerThread?.interrupt()
        joinProducerUntil(System.nanoTime() + joinTimeoutMs.coerceAtLeast(1L) * 1_000_000L)
        if (producerThread?.isAlive == true) {
            AppLogger.w(tag, "Pending producer cancel is still waiting on decoder I/O path=$path reason=$reason")
        } else {
            closeHandleOnce()
        }
    }

    private fun runProducer() {
        // This is a speculative/buffered decoder, not the real-time output owner. Giving it
        // THREAD_PRIORITY_AUDIO lets the current decoder, pending decoder and audio sink all
        // pre-empt RenderThread at the exact moment the artwork pager is moving. The reference
        // player keeps preparation/control workers at normal priority and reserves urgent/audio
        // priority for the active renderer/sink. The pending queue has an 8 s readiness budget,
        // so normal priority is both sufficient and materially fairer to UI frames.
        runCatching { Process.setThreadPriority(Process.THREAD_PRIORITY_DEFAULT) }
        runCatching {
            AppLogger.d(
                tag,
                "Pending decoder worker started generation=$generation " +
                    "thread=${Thread.currentThread().name} nice=${runCatching {
                        Process.getThreadPriority(Process.myTid())
                    }.getOrDefault(Int.MIN_VALUE)} path=${path.substringAfterLast('/')}",
            )
        }
        val chunkBytes = PcmFrameAligner.alignDown(
            DEFAULT_DECODE_CHUNK_BYTES.coerceAtLeast(frameSize),
            frameSize,
        ).coerceAtLeast(frameSize)
        val scratch = ByteArray(chunkBytes)
        try {
            while (true) {
                if (cancelRequested || !isGenerationCurrent(generation)) {
                    terminalState = TerminalState.CANCELLED
                    if (failureReason == null) failureReason = "obsolete_generation"
                    break
                }

                if (awaitFreezeOrWritableSpace()) break
                if (cancelRequested || !isGenerationCurrent(generation)) continue

                val writable = PcmFrameAligner.alignDown(pcm.writableBytes, frameSize)
                if (writable <= 0) continue
                val decodeLimit = minOf(scratch.size, writable)
                val decoded = try {
                    decodeChunk(handle, scratch, 0, decodeLimit)
                } catch (error: Throwable) {
                    failureReason = "decode_exception:${error.javaClass.simpleName}"
                    -2
                }

                when {
                    decoded > 0 -> {
                        gaplessAuditRegistry.recordDecodedBytes(handle, decoded)
                        var offset = 0
                        while (offset < decoded) {
                            if (cancelRequested) break
                            val appended = pcm.append(scratch, offset, decoded - offset)
                            if (appended < 0) {
                                failureReason = "native_queue_ownership_lost"
                                terminalState = TerminalState.FAILED
                                break
                            }
                            if (appended == 0) {
                                synchronized(monitor) { monitor.wait(2L) }
                                continue
                            }
                            offset += appended
                            synchronized(monitor) { monitor.notifyAll() }
                        }
                        if (terminalState == TerminalState.FAILED) break
                    }

                    decoded == -1 -> {
                        terminalState = TerminalState.EOF
                        gaplessAuditRegistry.finish(handle)
                        synchronized(monitor) { monitor.notifyAll() }
                        break
                    }

                    else -> {
                        terminalState = TerminalState.FAILED
                        if (failureReason == null) failureReason = "decode_result_$decoded"
                        gaplessAuditRegistry.invalidate(handle, failureReason ?: "pending_decode_failed")
                        synchronized(monitor) { monitor.notifyAll() }
                        break
                    }
                }
            }
        } catch (_: InterruptedException) {
            if (transferRequested) {
                terminalState = TerminalState.TRANSFERRED
            } else {
                terminalState = TerminalState.CANCELLED
                if (failureReason == null) failureReason = "interrupted"
            }
        } finally {
            synchronized(monitor) {
                if (transferRequested) terminalState = TerminalState.TRANSFERRED
                monitor.notifyAll()
            }
            if (closeHandleOnExit && terminalState != TerminalState.TRANSFERRED) closeHandleOnce()
        }
    }

    /** Returns true when the worker should leave its decode loop. */
    private fun awaitFreezeOrWritableSpace(): Boolean {
        synchronized(monitor) {
            while (true) {
                if (cancelRequested) return false
                if (transferRequested) {
                    terminalState = TerminalState.TRANSFERRED
                    return true
                }
                if (freezeRequested && pcm.availableFrames >= handoffReadyFrames) {
                    frozen = true
                    monitor.notifyAll()
                    while (freezeRequested && !transferRequested && !cancelRequested) monitor.wait()
                    frozen = false
                    if (transferRequested) {
                        terminalState = TerminalState.TRANSFERRED
                        return true
                    }
                    continue
                }
                if (pcm.writableBytes >= frameSize) return false
                // Queue is full: this is also the safest possible handoff point.
                if (freezeRequested) {
                    frozen = true
                    monitor.notifyAll()
                    while (freezeRequested && !transferRequested && !cancelRequested) monitor.wait()
                    frozen = false
                    if (transferRequested) {
                        terminalState = TerminalState.TRANSFERRED
                        return true
                    }
                    continue
                }
                monitor.wait(2L)
            }
        }
    }


    private fun joinProducerUninterruptibly() {
        var interrupted = false
        while (producerThread?.isAlive == true) {
            try {
                producerThread?.join()
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun joinProducerUntil(deadlineNs: Long) {
        var interrupted = false
        while (producerThread?.isAlive == true) {
            val remainingNs = deadlineNs - System.nanoTime()
            if (remainingNs <= 0L) break
            try {
                producerThread?.join((remainingNs / 1_000_000L).coerceIn(1L, 50L))
            } catch (_: InterruptedException) {
                interrupted = true
            }
        }
        if (interrupted) Thread.currentThread().interrupt()
    }

    private fun closeHandleOnce() {
        if (!handleClosed.compareAndSet(false, true)) return
        runCatching { closeDecoder(handle) }
            .onFailure { AppLogger.w(tag, "Pending producer failed to close decoder path=$path", it) }
    }

    private companion object {
        const val DEFAULT_DECODE_CHUNK_BYTES = 64 * 1024
    }
}
