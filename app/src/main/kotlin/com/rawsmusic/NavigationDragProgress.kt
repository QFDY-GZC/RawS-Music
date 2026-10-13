package com.rawsmusic

internal fun navigationDragRawProgress(dx: Float, direction: Float, touchSlop: Float, width: Float): Float =
    (dx * direction - touchSlop) / width

/** Keep the recognition direction and original down point throughout a pointer sequence. */
internal fun navigationDragProgress(dx: Float, direction: Float, touchSlop: Float, width: Float): Float =
    navigationDragRawProgress(dx, direction, touchSlop, width).coerceIn(0f, 1f)
