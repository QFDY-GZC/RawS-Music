package com.rawsmusic.core.ui.scene

/** Converts a measured home coordinate to the unscaled transition-host coordinate space. */
internal fun homeFullCoverUnscaledCoordinate(
    coordinateInRoot: Float,
    hostOriginInRoot: Float,
    pivotInHost: Float,
    homeScale: Float,
): Float = pivotInHost + (coordinateInRoot - hostOriginInRoot - pivotInHost) / homeScale
