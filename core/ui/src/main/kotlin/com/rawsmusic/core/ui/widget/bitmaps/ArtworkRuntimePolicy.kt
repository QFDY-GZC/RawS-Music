package com.rawsmusic.core.ui.widget.bitmaps

import android.content.Context
import com.rawsmusic.core.common.artwork.ArtworkResolutionPolicy
import com.rawsmusic.module.data.prefs.AppPreferences

/** Resolve the artwork tier from the current display and persisted preferences. */
fun artworkHighTargetSide(context: Context): Int {
    val metrics = context.resources.displayMetrics
    val shortSide = if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
        minOf(metrics.widthPixels, metrics.heightPixels)
    } else {
        ArtworkResolutionPolicy.LARGE_DISPLAY_MIN_SIDE_PX
    }
    return ArtworkResolutionPolicy.highTargetSide(
        increaseResolution = runCatching { AppPreferences.AlbumArt.useHigherRes }.getOrDefault(false),
        displayShortSidePx = shortSide,
        maxMemoryBytes = Runtime.getRuntime().maxMemory(),
    )
}

fun artworkLowTargetSide(context: Context): Int {
    val metrics = context.resources.displayMetrics
    val shortSide = if (metrics.widthPixels > 0 && metrics.heightPixels > 0) {
        minOf(metrics.widthPixels, metrics.heightPixels)
    } else {
        ArtworkResolutionPolicy.LARGE_DISPLAY_MIN_SIDE_PX
    }
    return ArtworkResolutionPolicy.lowTargetSide(shortSide)
}
