package com.rawsmusic.ui.treeview

data class TreeNodeInfo(
    val id: Any,
    val level: Int,
    val hasChildren: Boolean,
    val isVisible: Boolean,
    val isExpanded: Boolean
)
