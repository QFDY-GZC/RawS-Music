package com.rawsmusic.module.player.transition

import com.rawsmusic.core.common.utils.AppLogger
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Bounded, allocation-light transition timeline used during the Reference-style renderer rewrite.
 *
 * Phase 0 intentionally does not change playback behavior. It only gives every asynchronous
 * operation a stable identity and records the exact order in which decoder, renderer, fade, seek,
 * commit and retirement milestones occur. The same token schema is mirrored by the native trace
 * core so later phases can move ownership into C++ without changing diagnostics again.
 */
internal class PlaybackTransitionTelemetry(
    private val tag: String,
    private val maxEvents: Int = 256,
    private val nanoTime: () -> Long = System::nanoTime,
    private val logSink: (String) -> Unit = { AppLogger.i(tag, it) },
    private val nativeContextSink: (PlaybackTransitionToken?, Long) -> Unit = NativeTransitionTrace::setContext,
) {
    companion object {
        private const val PREFIX = "TRANSITION_TRACE"
    }

    private val lock = Any()
    private val sessionCounter = AtomicLong(0L)
    private val transitionCounter = AtomicLong(0L)
    private val eventCounter = AtomicLong(0L)
    private val outputGenerationCounter = AtomicLong(0L)
    private val activeTransitions = ConcurrentHashMap<Long, PlaybackTransitionToken>()
    private val firstPcmPending = AtomicReference<PlaybackTransitionToken?>(null)
    private val nativeContextToken = AtomicReference<PlaybackTransitionToken?>(null)
    private val history = ArrayDeque<PlaybackTransitionTraceEvent>(maxEvents.coerceAtLeast(16))

    @Volatile
    private var currentSessionId: Long = 0L

    @Volatile
    private var currentGeneration: Int = 0

    @Volatile
    private var currentSourcePath: String? = null

    fun beginSession(path: String, generation: Int): PlaybackTransitionToken {
        val now = nanoTime()
        val sessionId = sessionCounter.incrementAndGet()
        currentSessionId = sessionId
        currentGeneration = generation
        currentSourcePath = path
        activeTransitions.clear()
        firstPcmPending.set(null)
        val token = PlaybackTransitionToken(
            sessionId = sessionId,
            transitionId = transitionCounter.incrementAndGet(),
            generation = generation,
            reason = PlaybackTransitionReason.PLAY,
            sourcePath = path,
            targetPath = path,
            startedAtNanos = now,
        )
        activeTransitions[token.transitionId] = token
        nativeContextToken.set(token)
        nativeContextSink(token, outputGenerationCounter.get())
        record(token, PlaybackTransitionPhase.SESSION_BEGIN, detail = "path=${shortPath(path)}")
        record(token, PlaybackTransitionPhase.REQUESTED)
        armFirstOutput(token)
        return token
    }

    fun begin(
        reason: PlaybackTransitionReason,
        generation: Int = currentGeneration,
        sourcePath: String? = currentSourcePath,
        targetPath: String? = null,
        detail: String = "",
    ): PlaybackTransitionToken {
        val token = PlaybackTransitionToken(
            sessionId = currentSessionId,
            transitionId = transitionCounter.incrementAndGet(),
            generation = generation,
            reason = reason,
            sourcePath = sourcePath,
            targetPath = targetPath,
            startedAtNanos = nanoTime(),
        )
        activeTransitions[token.transitionId] = token
        nativeContextToken.set(token)
        nativeContextSink(token, outputGenerationCounter.get())
        record(token, PlaybackTransitionPhase.REQUESTED, detail = detail)
        return token
    }

    fun armFirstOutput(token: PlaybackTransitionToken) {
        firstPcmPending.set(token)
    }

    fun record(
        token: PlaybackTransitionToken?,
        phase: PlaybackTransitionPhase,
        sourceFrame: Long = -1L,
        renderedFrame: Long = -1L,
        readyFrames: Long = -1L,
        underrunFrames: Long = -1L,
        detail: String = "",
    ) {
        val now = nanoTime()
        val event = PlaybackTransitionTraceEvent(
            sequence = eventCounter.incrementAndGet(),
            elapsedNanos = now,
            token = token,
            phase = phase,
            outputGeneration = outputGenerationCounter.get(),
            sourceFrame = sourceFrame,
            renderedFrame = renderedFrame,
            readyFrames = readyFrames,
            underrunFrames = underrunFrames,
            detail = detail,
        )
        synchronized(lock) {
            while (history.size >= maxEvents.coerceAtLeast(16)) history.removeFirst()
            history.addLast(event)
        }
        logSink(format(event))
    }

    fun outputGeneration(backend: String, reason: String, detail: String = ""): Long {
        val generation = outputGenerationCounter.incrementAndGet()
        nativeContextSink(nativeContextToken.get(), generation)
        record(
            token = null,
            phase = PlaybackTransitionPhase.OUTPUT_GENERATION,
            detail = buildString {
                append("backend=").append(backend)
                append(" reason=").append(reason)
                if (detail.isNotBlank()) append(' ').append(detail)
            },
        )
        return generation
    }

    fun onPcmSubmitted(
        frames: Long,
        renderedFrame: Long = -1L,
        backend: String,
    ) {
        if (frames <= 0L) return
        firstPcmPending.getAndSet(null)?.let { token ->
            record(
                token = token,
                phase = PlaybackTransitionPhase.FIRST_PCM_SUBMITTED,
                renderedFrame = renderedFrame,
                detail = "backend=$backend frames=$frames",
            )
        }
    }

    fun complete(token: PlaybackTransitionToken?, detail: String = "") {
        if (token == null) return
        record(token, PlaybackTransitionPhase.COMPLETED, detail = withElapsed(token, detail))
        activeTransitions.remove(token.transitionId)
        firstPcmPending.compareAndSet(token, null)
    }

    fun cancel(token: PlaybackTransitionToken?, detail: String = "") {
        if (token == null) return
        record(token, PlaybackTransitionPhase.CANCELLED, detail = withElapsed(token, detail))
        activeTransitions.remove(token.transitionId)
        firstPcmPending.compareAndSet(token, null)
    }

    fun fail(token: PlaybackTransitionToken?, detail: String) {
        if (token == null) return
        record(token, PlaybackTransitionPhase.FAILED, detail = withElapsed(token, detail))
        activeTransitions.remove(token.transitionId)
        firstPcmPending.compareAndSet(token, null)
    }

    fun snapshot(): PlaybackTransitionTraceSnapshot = synchronized(lock) {
        PlaybackTransitionTraceSnapshot(
            sessionId = currentSessionId,
            generation = currentGeneration,
            outputGeneration = outputGenerationCounter.get(),
            activeTransitionIds = activeTransitions.keys.toSet(),
            events = history.toList(),
        )
    }

    private fun withElapsed(token: PlaybackTransitionToken, detail: String): String {
        val elapsedMs = (nanoTime() - token.startedAtNanos).coerceAtLeast(0L) / 1_000_000.0
        return buildString {
            append("elapsedMs=").append(String.format(java.util.Locale.US, "%.3f", elapsedMs))
            if (detail.isNotBlank()) append(' ').append(detail)
        }
    }

    private fun format(event: PlaybackTransitionTraceEvent): String {
        val token = event.token
        return buildString(192) {
            append(PREFIX)
            append(" seq=").append(event.sequence)
            append(" session=").append(token?.sessionId ?: currentSessionId)
            append(" transition=").append(token?.transitionId ?: 0L)
            append(" generation=").append(token?.generation ?: currentGeneration)
            append(" reason=").append(token?.reason ?: "NONE")
            append(" phase=").append(event.phase)
            append(" outputGen=").append(event.outputGeneration)
            if (event.sourceFrame >= 0L) append(" sourceFrame=").append(event.sourceFrame)
            if (event.renderedFrame >= 0L) append(" renderedFrame=").append(event.renderedFrame)
            if (event.readyFrames >= 0L) append(" readyFrames=").append(event.readyFrames)
            if (event.underrunFrames >= 0L) append(" underrunFrames=").append(event.underrunFrames)
            token?.targetPath?.let { append(" target=").append(shortPath(it)) }
            if (event.detail.isNotBlank()) append(' ').append(event.detail)
        }
    }

    private fun shortPath(path: String): String = path.substringAfterLast('/').takeLast(96)
}
