package com.rawsmusic.module.player.transition

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Keeps trace-token ownership out of FfmpegAudioPlayer. No playback decisions live here.
 */
internal class FfmpegTransitionTraceCoordinator(
    private val telemetry: PlaybackTransitionTelemetry,
) {
    private val play = AtomicReference<PlaybackTransitionToken?>(null)
    private val seek = AtomicReference<PlaybackTransitionToken?>(null)
    private val transport = AtomicReference<PlaybackTransitionToken?>(null)
    private val handoff = AtomicReference<PlaybackTransitionToken?>(null)
    private val handoffMixed = AtomicBoolean(false)
    private val handoffCommitted = AtomicBoolean(false)
    private val firstOutputArmed = AtomicBoolean(false)

    fun beginPlay(path: String, generation: Int): PlaybackTransitionToken {
        cancelAll("superseded_by_new_play")
        val token = telemetry.beginSession(path, generation)
        play.set(token)
        firstOutputArmed.set(true)
        return token
    }

    fun beginSeek(targetMs: Long, generation: Int, sourcePath: String?): PlaybackTransitionToken =
        replace(
            holder = seek,
            token = telemetry.begin(
                reason = PlaybackTransitionReason.SEEK,
                generation = generation,
                sourcePath = sourcePath,
                targetPath = sourcePath,
                detail = "targetMs=$targetMs",
            ),
            supersededDetail = "superseded_by_new_seek",
        ).also { telemetry.record(it, PlaybackTransitionPhase.SEEK_BARRIER_ARMED, detail = "targetMs=$targetMs") }

    fun beginTransport(
        reason: PlaybackTransitionReason,
        generation: Int,
        sourcePath: String?,
        detail: String = "",
    ): PlaybackTransitionToken = replace(
        holder = transport,
        token = telemetry.begin(reason, generation, sourcePath, sourcePath, detail),
        supersededDetail = "superseded_by_${reason.name.lowercase()}",
    )

    fun armFirstOutput(token: PlaybackTransitionToken) {
        telemetry.armFirstOutput(token)
        firstOutputArmed.set(true)
    }

    fun beginHandoff(
        reason: PlaybackTransitionReason,
        generation: Int,
        sourcePath: String?,
        targetPath: String,
        detail: String = "",
    ): PlaybackTransitionToken {
        handoffMixed.set(false)
        handoffCommitted.set(false)
        return replace(
            holder = handoff,
            token = telemetry.begin(reason, generation, sourcePath, targetPath, detail),
            supersededDetail = "superseded_by_new_handoff",
        ).also { telemetry.record(it, PlaybackTransitionPhase.HANDOFF_BEGIN, detail = detail) }
    }

    fun ensureHandoff(
        reason: PlaybackTransitionReason,
        generation: Int,
        sourcePath: String?,
        targetPath: String,
        detail: String = "",
    ): PlaybackTransitionToken {
        val existing = handoff.get()
        if (
            existing != null &&
            existing.generation == generation &&
            existing.targetPath == targetPath &&
            existing.reason == reason
        ) {
            return existing
        }
        return beginHandoff(reason, generation, sourcePath, targetPath, detail)
    }

    fun currentPlay(): PlaybackTransitionToken? = play.get()
    fun currentSeek(): PlaybackTransitionToken? = seek.get()
    fun currentTransport(): PlaybackTransitionToken? = transport.get()
    fun currentHandoff(): PlaybackTransitionToken? = handoff.get()

    fun decoderOpenBegin(path: String, generation: Int) {
        val token = handoff.get() ?: play.get()
        telemetry.record(token, PlaybackTransitionPhase.DECODER_OPEN_BEGIN, detail = "path=${path.substringAfterLast('/')} gen=$generation")
    }

    fun decoderReady(path: String, readyFrames: Long, detail: String) {
        val token = handoff.get() ?: play.get()
        telemetry.record(
            token,
            PlaybackTransitionPhase.DECODER_READY,
            readyFrames = readyFrames,
            detail = "path=${path.substringAfterLast('/')} $detail",
        )
    }

    fun decoderFailed(path: String, detail: String) {
        val token = handoff.get() ?: play.get()
        telemetry.record(token, PlaybackTransitionPhase.DECODER_FAILED, detail = "path=${path.substringAfterLast('/')} $detail")
    }

    fun seekDecoderCommitted(serial: Long, targetMs: Long) {
        telemetry.record(seek.get(), PlaybackTransitionPhase.SEEK_DECODER_COMMITTED, detail = "serial=$serial targetMs=$targetMs")
    }

    fun seekOutputCommitted(serial: Long, targetMs: Long) {
        val token = seek.get()
        telemetry.record(token, PlaybackTransitionPhase.SEEK_OUTPUT_COMMITTED, detail = "serial=$serial targetMs=$targetMs")
        if (token != null) armFirstOutput(token)
    }

    fun handoffMixed(detail: String) {
        val token = handoff.get() ?: return
        if (handoffMixed.compareAndSet(false, true)) {
            telemetry.record(token, PlaybackTransitionPhase.FIRST_MIXED_FRAME, detail = detail)
        }
    }

    fun handoffCommitted(detail: String) {
        val token = handoff.get() ?: return
        if (handoffCommitted.compareAndSet(false, true)) {
            telemetry.record(token, PlaybackTransitionPhase.HANDOFF_COMMITTED, detail = detail)
        }
        // Commit is a state decision, not proof that the replacement PCM reached the output.
        // Complete only after the next successful renderer write.
        armFirstOutput(token)
    }

    fun handoffFailed(detail: String) {
        handoffMixed.set(false)
        handoffCommitted.set(false)
        firstOutputArmed.set(false)
        telemetry.fail(handoff.getAndSet(null), detail)
    }

    fun stateChanged(old: String, new: String) {
        val token = seek.get() ?: transport.get() ?: handoff.get() ?: play.get()
        telemetry.record(token, PlaybackTransitionPhase.STATE_CHANGED, detail = "$old->$new")
        when (new) {
            "PLAYING" -> {
                val current = transport.get()
                if (current?.reason == PlaybackTransitionReason.RESUME) {
                    telemetry.record(current, PlaybackTransitionPhase.FADE_END, detail = "state=PLAYING awaiting_first_output")
                } else {
                    transport.getAndSet(null)?.let {
                        telemetry.record(it, PlaybackTransitionPhase.FADE_END, detail = "state=PLAYING")
                        telemetry.complete(it, "state=PLAYING")
                    }
                }
            }
            "PAUSED" -> {
                transport.getAndSet(null)?.let {
                    telemetry.record(it, PlaybackTransitionPhase.FADE_END, detail = "state=PAUSED")
                    telemetry.complete(it, "state=PAUSED")
                }
            }
            "STOPPED", "IDLE", "COMPLETED" -> {
                transport.getAndSet(null)?.let {
                    telemetry.record(it, PlaybackTransitionPhase.FADE_END, detail = "state=$new")
                    telemetry.complete(it, "state=$new")
                }
            }
            "ERROR" -> {
                transport.getAndSet(null)?.let { telemetry.fail(it, "state=ERROR") }
                seek.getAndSet(null)?.let { telemetry.fail(it, "state=ERROR") }
                handoff.getAndSet(null)?.let { telemetry.fail(it, "state=ERROR") }
                play.getAndSet(null)?.let { telemetry.fail(it, "state=ERROR") }
                handoffMixed.set(false)
                handoffCommitted.set(false)
                firstOutputArmed.set(false)
            }
        }
    }

    fun onPcmSubmitted(frames: Long, renderedFrame: Long, backend: String) {
        if (frames <= 0L || !firstOutputArmed.get()) return
        if (!firstOutputArmed.compareAndSet(true, false)) return
        telemetry.onPcmSubmitted(frames, renderedFrame, backend)
        val committedHandoff = handoff.get()?.takeIf { handoffCommitted.get() }
        if (committedHandoff != null) {
            finishHandoff(committedHandoff, "first_output backend=$backend")
        }
        play.getAndSet(null)?.let { telemetry.complete(it, "first_output backend=$backend") }
        seek.getAndSet(null)?.let { telemetry.complete(it, "first_output backend=$backend") }
        transport.get()?.takeIf { it.reason == PlaybackTransitionReason.RESUME }?.let { token ->
            if (transport.compareAndSet(token, null)) {
                telemetry.complete(token, "first_output backend=$backend")
            }
        }
    }

    fun outputGeneration(backend: String, reason: String, detail: String = ""): Long =
        telemetry.outputGeneration(backend, reason, detail)

    fun outputEvent(phase: PlaybackTransitionPhase, detail: String) {
        telemetry.record(transport.get() ?: handoff.get() ?: play.get(), phase, detail = detail)
    }

    fun failActive(detail: String) {
        seek.getAndSet(null)?.let { telemetry.fail(it, detail) }
        transport.getAndSet(null)?.let { telemetry.fail(it, detail) }
        handoff.getAndSet(null)?.let { telemetry.fail(it, detail) }
        play.getAndSet(null)?.let { telemetry.fail(it, detail) }
        handoffMixed.set(false)
        handoffCommitted.set(false)
        firstOutputArmed.set(false)
    }

    fun completeTransport(token: PlaybackTransitionToken, detail: String) {
        if (transport.compareAndSet(token, null)) telemetry.complete(token, detail)
    }

    fun cancelAll(detail: String) {
        seek.getAndSet(null)?.let { telemetry.cancel(it, detail) }
        transport.getAndSet(null)?.let { telemetry.cancel(it, detail) }
        handoff.getAndSet(null)?.let { telemetry.cancel(it, detail) }
        handoffMixed.set(false)
        handoffCommitted.set(false)
        firstOutputArmed.set(false)
        play.getAndSet(null)?.let { telemetry.cancel(it, detail) }
    }

    fun snapshot(): PlaybackTransitionTraceSnapshot = telemetry.snapshot()

    private fun finishHandoff(token: PlaybackTransitionToken, detail: String) {
        if (!handoff.compareAndSet(token, null)) return
        handoffMixed.set(false)
        handoffCommitted.set(false)
        telemetry.complete(token, detail)
    }

    private fun replace(
        holder: AtomicReference<PlaybackTransitionToken?>,
        token: PlaybackTransitionToken,
        supersededDetail: String,
    ): PlaybackTransitionToken {
        holder.getAndSet(token)?.let { telemetry.cancel(it, supersededDetail) }
        return token
    }
}
