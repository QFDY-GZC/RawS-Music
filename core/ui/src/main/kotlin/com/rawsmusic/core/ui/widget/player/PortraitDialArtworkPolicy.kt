package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs

/**
 * Keeps the portrait full-cover rail responsive without starting seventeen full-cover decodes at
 * once. Only the settled centre owns the 1440px fullscreen tier; visible side lanes use bounded
 * list-style requests, one hidden lane on either side is admitted as a preload, and the remaining
 * retained holders keep composition identity without joining the decode wave.
 */
internal enum class PortraitDialArtworkTier(
    val targetSidePx: Int,
    val shouldLoad: Boolean,
) {
    Center(targetSidePx = 1440, shouldLoad = true),
    Near(targetSidePx = 768, shouldLoad = true),
    Outer(targetSidePx = 512, shouldLoad = true),
    Preload(targetSidePx = 384, shouldLoad = true),
    Dormant(targetSidePx = 0, shouldLoad = false),
}

internal fun resolvePortraitDialArtworkTier(position: Float): PortraitDialArtworkTier {
    val distance = abs(position)
    return when {
        distance < 0.55f -> PortraitDialArtworkTier.Center
        distance <= 2.75f -> PortraitDialArtworkTier.Near
        distance <= PORTRAIT_DIAL_VISIBLE_RADIUS + 0.55f -> PortraitDialArtworkTier.Outer
        distance <= PORTRAIT_DIAL_VISIBLE_RADIUS + 1.55f -> PortraitDialArtworkTier.Preload
        else -> PortraitDialArtworkTier.Dormant
    }
}
