package com.rawsmusic.core.ui.scene.pages

// Five tablet lanes on either side, plus four tracks of continuous gesture preview. Only the
// visible lanes request artwork; the extra entries are lightweight transport identities.
const val HOME_CAROUSEL_PREVIEW_RADIUS = 9
private const val CONTINUOUS_PREVIEW_LIMIT = 4

internal fun carouselPreviewIndex(index: Int, committedIndex: Int, size: Int): Int {
    if (size <= 0) return 0
    val origin = committedIndex.coerceIn(0, size - 1)
    return index.coerceIn(
        (origin - CONTINUOUS_PREVIEW_LIMIT).coerceAtLeast(0),
        (origin + CONTINUOUS_PREVIEW_LIMIT).coerceAtMost(size - 1),
    )
}
