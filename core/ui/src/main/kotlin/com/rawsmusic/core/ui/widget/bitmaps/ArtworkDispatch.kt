package com.rawsmusic.core.ui.widget.bitmaps

internal sealed interface ArtworkDispatch {
    data object Front : ArtworkDispatch
    data object Immediate : ArtworkDispatch
    data class Delayed(val delayMs: Long) : ArtworkDispatch
}

internal fun resolveArtworkDispatch(
    front: Boolean,
    prefetch: Boolean,
    retryDelayMs: Long,
): ArtworkDispatch = when {
    // A busy-source retry must yield to the existing extractor even for active playback.
    // Front-of-queue dispatch would otherwise consume every retry before that source is ready.
    retryDelayMs > 0L -> ArtworkDispatch.Delayed(retryDelayMs)
    front -> ArtworkDispatch.Front
    prefetch -> ArtworkDispatch.Delayed(24L)
    else -> ArtworkDispatch.Immediate
}
