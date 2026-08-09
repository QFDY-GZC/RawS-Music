package com.rawsmusic.core.ui.scene

/**
 * Decode/display policy for the moving centre artwork between home and the portrait dial.
 *
 * The shared lane must reuse the already-visible playback tier immediately, keep accepting
 * quality upgrades while it moves, and prewarm the settled fullscreen tier in parallel.
 */
internal data class HomePortraitDialSharedArtworkPolicy(
    val movingTargetSidePx: Int,
    val freezeBitmapUpdatesDuringMotion: Boolean,
    val prewarmFullCover: Boolean,
)

internal val homePortraitDialSharedArtworkPolicy = HomePortraitDialSharedArtworkPolicy(
    movingTargetSidePx = 1024,
    freezeBitmapUpdatesDuringMotion = false,
    prewarmFullCover = true,
)

/** Prefer the full-screen page's actual centre holder over a potentially lagging player emission. */
internal fun resolveHomePortraitDialReturnArtworkKey(
    fullscreenCenterArtworkKey: String,
    currentArtworkKey: String,
): String = fullscreenCenterArtworkKey.ifBlank { currentArtworkKey }
