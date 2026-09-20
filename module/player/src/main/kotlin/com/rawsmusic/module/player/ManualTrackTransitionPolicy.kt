package com.rawsmusic.module.player

import com.rawsmusic.module.data.prefs.TransitionPreferences

/**
 * Pure routing policy for manual track changes.
 *
 * Reference-style SHORT_FADE is a short two-slot crossfade, not a serialized
 * fade-out/reopen/fade-in. Both non-NONE modes may therefore attempt the native
 * overlap renderer for any distinct non-CUE target. Source-library sample-rate
 * metadata is deliberately not used as a gate because the pending decoder is
 * opened against the active renderer format. The actual prepared PCM geometry,
 * DSD/bit-perfect route and native READY state are validated immediately before arm.
 */
internal object ManualTrackTransitionPolicy {
    data class Decision(
        val attemptOverlap: Boolean,
        val allowSequentialFallback: Boolean,
    )

    data class Durations(
        val overlapMs: Int,
        val sequentialFallbackMs: Int,
        val usbSequentialMs: Int,
    )

    /**
     * Reference keeps short manual fading (default 400 ms) separate from full
     * crossfade length (default 5000 ms). When overlap is impossible, a long
     * serialized fade is not equivalent to crossfade, so fall back to short.
     */
    fun durations(
        mode: TransitionPreferences.ManualTrackTransitionMode,
        shortFadeMs: Int,
        fullCrossfadeMs: Int,
    ): Durations {
        val shortMs = shortFadeMs.coerceAtLeast(0)
        val fullMs = fullCrossfadeMs.coerceAtLeast(0)
        return when (mode) {
            TransitionPreferences.ManualTrackTransitionMode.NONE -> Durations(0, 0, 0)
            TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE ->
                Durations(shortMs, shortMs, shortMs)
            TransitionPreferences.ManualTrackTransitionMode.CROSSFADE ->
                Durations(fullMs, shortMs, shortMs)
        }
    }

    fun decide(
        mode: TransitionPreferences.ManualTrackTransitionMode,
        pathOnlyCrossfadeIsSafe: Boolean,
    ): Decision = when (mode) {
        TransitionPreferences.ManualTrackTransitionMode.NONE -> Decision(
            attemptOverlap = false,
            allowSequentialFallback = false,
        )

        TransitionPreferences.ManualTrackTransitionMode.SHORT_FADE,
        TransitionPreferences.ManualTrackTransitionMode.CROSSFADE -> Decision(
            // Source-library sample-rate/channel metadata is not an overlap gate.
            // The pending FFmpeg decoder is opened against the active renderer format,
            // and its actual decoded geometry is validated immediately before native arm.
            attemptOverlap = pathOnlyCrossfadeIsSafe,
            allowSequentialFallback = true,
        )
    }
}
