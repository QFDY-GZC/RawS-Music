package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import android.os.SystemClock

/** Coordinates fade lifecycle without owning the decoder or AudioTrack loop. */
internal class PlaybackFadeRuntime(
    private val tag: String,
    private val isPlaying: () -> Boolean,
    private val isReleased: () -> Boolean,
    private val isPlayingState: () -> Boolean,
    private val shouldBypass: () -> Boolean,
    private val useFloatOutput: () -> Boolean,
    private val usePacked24Output: () -> Boolean
) {
    private val processor: TransportFadeProcessor =
        NativeTransportFadeProcessor.createOrNull(tag) ?: PlaybackFadeController(tag)

    init {
        AppLogger.i(
            tag,
            "PlaybackFade: processor=${if (processor.isNativeBacked) "native_render_clock" else "kotlin_fallback"}",
        )
    }

    @Volatile
    private var suppressNextStartFadeIn = false

    @Volatile
    private var nextStartFadeOverrideMs = 0

    fun suppressNextStartFadeIn(reason: String) {
        suppressNextStartFadeIn = true
        AppLogger.d(tag, "PlaybackFade: suppress next start fade-in reason=$reason")
    }

    fun armNextStartFadeIn(durationMs: Int, reason: String) {
        suppressNextStartFadeIn = false
        nextStartFadeOverrideMs = durationMs
        AppLogger.d(tag, "PlaybackFade: arm next start fade-in durationMs=$durationMs reason=$reason")
    }

    fun fadeOutForTransitionBlocking(
        durationMs: Int,
        reason: String,
        shouldContinue: () -> Boolean = { true },
    ): Boolean {
        if (durationMs <= 0 || !isPlayingState() || !isPlaying() || isReleased() || shouldBypass() || !shouldContinue()) {
            return false
        }
        processor.startFadeOut(durationMs, reason)
        val deadline = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(1) + 80L
        while (
            SystemClock.elapsedRealtime() < deadline &&
            processor.isActive &&
            isPlaying() &&
            !isReleased() &&
            shouldContinue()
        ) {
            try {
                Thread.sleep(8L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        if (!shouldContinue()) {
            processor.clear("fade_out_cancelled_stale:$reason")
            AppLogger.d(tag, "PlaybackFade: fade-out cancelled by newer transport request reason=$reason")
            return false
        }
        val completed = !processor.isActive
        if (!completed) {
            processor.clear("fade_out_timeout:$reason")
            AppLogger.w(
                tag,
                "PlaybackFade: fade-out timed out reason=$reason durationMs=$durationMs " +
                    "gain=${processor.currentGain}",
            )
        }
        return completed
    }

    fun armConfiguredStartFade(reason: String) {
        if (suppressNextStartFadeIn) {
            suppressNextStartFadeIn = false
            nextStartFadeOverrideMs = 0
            AppLogger.d(tag, "PlaybackFade: start fade suppressed reason=$reason")
            return
        }
        val duration = nextStartFadeOverrideMs.takeIf { it > 0 } ?: PlaybackTransitionRuntime.transportFadeMs
        nextStartFadeOverrideMs = 0
        if (duration > 0 && !shouldBypass()) {
            processor.startFadeIn(duration, reason)
        }
    }

    fun armSeekFadeIn(durationMs: Int, reason: String) {
        if (durationMs > 0 && !shouldBypass()) {
            processor.startFadeIn(durationMs, reason)
        }
    }

    fun armDefaultStartFadeIn(durationMs: Int, reason: String) {
        if (nextStartFadeOverrideMs > 0) {
            AppLogger.d(
                tag,
                "PlaybackFade: keep pending transition fade-in durationMs=$nextStartFadeOverrideMs reason=$reason"
            )
            return
        }
        if (durationMs <= 0) {
            suppressNextStartFadeIn = true
            nextStartFadeOverrideMs = 0
            AppLogger.d(tag, "PlaybackFade: default start fade suppressed (durationMs=0) reason=$reason")
            return
        }
        nextStartFadeOverrideMs = durationMs
        AppLogger.d(tag, "PlaybackFade: default start fade armed durationMs=$durationMs reason=$reason")
    }

    fun pauseWithFadeBlocking(durationMs: Int, reason: String, pause: () -> Unit) {
        if (!isPlayingState()) {
            AppLogger.w(tag, "pauseWithFadeBlocking: state NOT PLAYING, skipping reason=$reason")
            return
        }
        if (durationMs > 0 && !shouldBypass()) {
            processor.startFadeOut(durationMs, "$reason:fade_out")
            val deadline = System.currentTimeMillis() + durationMs + 50L
            while (processor.isActive && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(4L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
            }
            if (processor.isActive) {
                processor.clear("pause_fade_timeout:$reason")
                AppLogger.w(
                    tag,
                    "pauseWithFadeBlocking: fade timed out reason=$reason durationMs=$durationMs " +
                        "gain=${processor.currentGain}",
                )
            }
        }
        pause()
        AppLogger.d(tag, "pauseWithFadeBlocking: done reason=$reason durationMs=$durationMs")
    }

    fun process(
        buffer: ByteArray,
        offset: Int,
        length: Int,
        sampleRate: Int,
        frameSize: Int,
        bitsPerSample: Int,
        outputIsFloat: Boolean = useFloatOutput(),
        outputIsPacked24: Boolean = usePacked24Output()
    ) {
        if (shouldBypass() || bitsPerSample <= 1) return
        processor.processInPlace(
            buffer = buffer,
            offset = offset,
            length = PcmFrameAligner.alignDown(length, frameSize),
            sampleRate = sampleRate,
            frameSize = frameSize,
            bitsPerSample = bitsPerSample,
            outputIsFloat = outputIsFloat,
            outputIsPacked24 = outputIsPacked24
        )
    }

    fun clear(reason: String) {
        processor.clear(reason)
    }

    fun close() {
        processor.close()
    }
}
