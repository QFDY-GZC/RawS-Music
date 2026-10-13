package com.rawsmusic.core.ui.widget.index

internal data class LibraryScrollGeometry(val top: Float, val travel: Float, val thumbHeight: Float) {
    fun thumbTop(scroll: Float, maxScroll: Float): Float =
        top + if (maxScroll > 0f) travel * (scroll / maxScroll).coerceIn(0f, 1f) else 0f
    fun scrollForThumb(thumbTop: Float, maxScroll: Float): Float =
        if (travel > 0f && maxScroll > 0f) ((thumbTop - top) / travel).coerceIn(0f, 1f) * maxScroll else 0f
}

internal fun libraryScrollGeometry(height: Float, density: Float): LibraryScrollGeometry {
    val available = height.coerceAtLeast(0f)
    val thumb = (64f * density).coerceIn(0f, available)
    val margin = (110f * density).coerceIn(0f, ((available - thumb - 64f * density) / 2f).coerceAtLeast(0f))
    return LibraryScrollGeometry(margin, (available - margin * 2f - thumb).coerceAtLeast(0f), thumb)
}
