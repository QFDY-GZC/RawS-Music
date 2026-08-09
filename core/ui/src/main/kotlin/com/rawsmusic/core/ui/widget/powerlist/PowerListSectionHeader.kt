package com.rawsmusic.core.ui.widget.powerlist

/** A full-width entry embedded in the same layout stream as PowerList items. */
data class PowerListSectionHeader(
    val stableKey: String,
    val beforeItemIndex: Int,
    val title: String,
    val count: Int
)
