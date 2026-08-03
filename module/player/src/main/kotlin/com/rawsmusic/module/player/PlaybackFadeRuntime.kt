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
    private val processor = PlaybackFadeController(tag)

    @Volatile
    private var suppressNextStartFadeIn = false

    @Volatile
    private var nextStartFadeOverrideMs = 0

    fun suppressNextStartFadeIn(reason: String) {
        suppressNextStartFadeIn = true
        AppLogger.d(tag, "PlaybackFade: suppress next start fade-in reason=$reason")
    }

    fun armNextStartFadeIn(durationMs: Int, reason: String) {
        nextStartFadeOverrideMs = durationMs
        AppLogger.d(tag, "PlaybackFade: arm next start fade-in durationMs=$durationMs reason=$reason")
    }

    fun fadeOutForTransitionBlocking(durationMs: Int, reason: String): Boolean {
        if (durationMs <= 0 || !isPlayingState() || !isPlaying() || isReleased()) return false
        processor.startFadeOut(durationMs, reason)
        val deadline = SystemClock.elapsedRealtime() + durationMs.coerceAtLeast(1) + 80L
        while (SystemClock.elapsedRealtime() < deadline && processor.isActive && isPlaying() && !isReleased()) {
            try {
                Thread.sleep(8L)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                break
            }
        }
        return true
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
        if (!processor.isActive || shouldBypass() || bitsPerSample <= 1) return
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
}
