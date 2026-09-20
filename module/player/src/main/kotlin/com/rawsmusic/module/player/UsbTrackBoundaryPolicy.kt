package com.rawsmusic.module.player

/**
 * USB track-boundary safety is independent from the user's cosmetic transition mode.
 * NONE disables the audible transition effect; it must not re-enable a full-scale PCM cut.
 */
internal object UsbTrackBoundaryPolicy {
    const val MIN_SAFETY_FADE_MS: Int = 30

    fun resolveFadeMs(configuredMs: Int): Int =
        configuredMs.takeIf { it > 0 } ?: MIN_SAFETY_FADE_MS
}
