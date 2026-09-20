package com.rawsmusic.core.ui.widget.virtuallist

/** A full-width entry embedded in the same layout stream as VirtualList items. */
data class VirtualListSectionHeader(
    val stableKey: String,
    val beforeItemIndex: Int,
    val title: String,
    val count: Int
)
