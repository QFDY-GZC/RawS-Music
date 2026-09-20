package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.player.transition.NativeTrackRenderTimeline
import kotlin.math.max

/**
 * Maps native render-track serials to application track metadata.
 *
 * Unlike the removed ManualHandoffPlaybackClock, this class never advances by
 * wall time or by requested fade duration. Position advances only when native
 * confirms that pending PCM frames were accepted into the DSP/render timeline.
 */
internal class TrackStartedPresentationCoordinator(private val tag: String) : AutoCloseable {
    data class Presentation(
        val path: String,
        val decoderSerial: Long,
        val generation: Int,
        val sampleRate: Int,
        val durationMs: Long,
        val positionMs: Long,
        val startedNow: Boolean,
    )

    private data class Pending(
        val path: String,
        val decoderSerial: Long,
        val generation: Int,
        val sampleRate: Int,
        val durationMs: Long,
        val nativeArmed: Boolean,
        val fallbackPositionFrames: Long = 0L,
        val started: Boolean = false,
    )

    private val nativeTimeline = NativeTrackRenderTimeline.createOrNull(tag)

    @Volatile private var pending: Pending? = null
    @Volatile private var activePresentation: Presentation? = null

    fun installCurrent(
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        positionMs: Long = 0L,
    ): Boolean {
        if (decoderSerial <= 0L || generation < 0 || sampleRate <= 0) return false
        val positionFrames = positionMs.coerceAtLeast(0L) * sampleRate.toLong() / 1000L
        return nativeTimeline?.installCurrent(
            decoderSerial = decoderSerial,
            generation = generation,
            sampleRate = sampleRate,
            positionFrames = positionFrames,
        ) ?: false
    }

    fun arm(
        path: String,
        decoderSerial: Long,
        generation: Int,
        sampleRate: Int,
        durationMs: Long,
    ): Boolean {
        if (path.isBlank() || decoderSerial <= 0L || generation < 0 || sampleRate <= 0) return false
        val nativeArmed = nativeTimeline?.armPending(decoderSerial, generation, sampleRate) ?: false
        pending = Pending(
            path = path,
            decoderSerial = decoderSerial,
            generation = generation,
            sampleRate = sampleRate,
            durationMs = durationMs.coerceAtLeast(0L),
            nativeArmed = nativeArmed,
        )
        AppLogger.d(
            tag,
            "TrackTimeline armed path=${path.substringAfterLast('/')} serial=$decoderSerial " +
                "gen=$generation sr=$sampleRate native=$nativeArmed",
        )
        return true
    }

    @Synchronized
    fun onPendingFramesRendered(
        decoderSerial: Long,
        generation: Int,
        renderedFrames: Long,
    ): Presentation? {
        if (renderedFrames <= 0L) return activePresentation
        val state = pending ?: return activePresentation
        if (state.decoderSerial != decoderSerial || state.generation != generation) return activePresentation

        val nativeUpdate = nativeTimeline?.onPendingFramesRendered(
            decoderSerial,
            generation,
            renderedFrames,
        )
        val fallbackFrames = state.fallbackPositionFrames + renderedFrames
        val nativeStarted = nativeUpdate?.event == NativeTrackRenderTimeline.Event.STARTED
        if (!state.started && state.nativeArmed && !nativeStarted) {
            AppLogger.e(
                tag,
                "TrackTimeline rejected first rendered frames for native-owned pending track " +
                    "serial=$decoderSerial gen=$generation update=$nativeUpdate",
            )
            return activePresentation
        }
        val positionFrames = nativeUpdate?.positionFrames?.takeIf { it > 0L } ?: fallbackFrames
        val startedNow = !state.started && (nativeStarted || !state.nativeArmed)
        val positionMs = positionFrames * 1000L / state.sampleRate.toLong()
        val presentation = Presentation(
            path = state.path,
            decoderSerial = state.decoderSerial,
            generation = state.generation,
            sampleRate = state.sampleRate,
            durationMs = state.durationMs,
            positionMs = if (state.durationMs > 0L) positionMs.coerceAtMost(state.durationMs) else positionMs,
            startedNow = startedNow,
        )
        pending = state.copy(fallbackPositionFrames = positionFrames, started = true)
        activePresentation = presentation
        return presentation
    }


    fun isArmedFor(decoderSerial: Long, generation: Int): Boolean {
        val state = pending ?: return false
        return state.decoderSerial == decoderSerial && state.generation == generation
    }

    fun activePositionMsOrNull(): Long? = activePresentation?.positionMs

    fun activeDurationMsOrNull(): Long? = activePresentation?.durationMs?.takeIf { it > 0L }

    fun isStartedFor(path: String): Boolean = activePresentation?.path == path

    @Synchronized
    fun commit(path: String, rendererPositionMs: Long): Long {
        val state = pending
        if (state != null && state.path == path) {
            val update = nativeTimeline?.commitPending(state.decoderSerial, state.generation)
            if (state.nativeArmed && update?.event != NativeTrackRenderTimeline.Event.COMMITTED) {
                AppLogger.e(
                    tag,
                    "TrackTimeline native commit rejected serial=${state.decoderSerial} " +
                        "gen=${state.generation} update=$update",
                )
            }
            pending = null
        }
        val visible = activePresentation?.takeIf { it.path == path }
        val position = max(rendererPositionMs.coerceAtLeast(0L), visible?.positionMs ?: 0L)
        activePresentation = null
        return position
    }

    @Synchronized
    fun cancel(reason: String) {
        val state = pending
        if (state != null) nativeTimeline?.cancelPending(state.decoderSerial, state.generation)
        if (state != null || activePresentation != null) {
            AppLogger.d(tag, "TrackTimeline cancelled reason=$reason state=$state")
        }
        pending = null
        activePresentation = null
    }

    fun snapshot(): String = "presentation=$activePresentation pending=$pending native=${nativeTimeline?.snapshot()}"

    override fun close() {
        cancel("close")
        nativeTimeline?.close()
    }
}
