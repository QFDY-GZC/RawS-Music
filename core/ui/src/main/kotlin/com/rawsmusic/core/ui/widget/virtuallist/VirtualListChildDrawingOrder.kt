package com.rawsmusic.core.ui.widget.virtuallist

/** Inactive children first, then the published population and its optional shared actor. */
internal fun resolveVirtualListChildDrawingOrder(count: Int, preferred: List<Int>): IntArray {
    val ordered = preferred.filter { it in 0 until count }.distinct()
    val selected = ordered.toHashSet()
    return ((0 until count).filter { it !in selected } + ordered).toIntArray()
}
