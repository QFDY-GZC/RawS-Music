package com.rawsmusic.core.ui.scene

import androidx.compose.ui.geometry.Rect

/** Measured home artwork geometry captured at the long-press transaction boundary. */
data class HomeFullCoverSourceAnchor(
    val boundsInRoot: Rect,
    val cornerRadiusDp: Float,
)
