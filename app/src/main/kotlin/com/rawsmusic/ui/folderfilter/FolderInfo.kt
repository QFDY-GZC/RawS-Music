package com.rawsmusic.ui.folderfilter

import java.io.Serializable

open class FolderInfo(
    val id: Int,
    val level: Int,
    val fullPath: String,
    val displayName: String,
    val label: String,
    val isStorage: Boolean,
    var notLoaded: Boolean = true
) : Serializable {

    var checked: Boolean = false
    var loading: Boolean = false
    var partiallyChecked: Boolean = false
    var iconRes: Int = 0
    var justExpandedTime: Long = 0L

    open fun canExpand(): Boolean = true

    override fun equals(other: Any?): Boolean {
        return other is FolderInfo && other.id == id
    }

    override fun hashCode(): Int = id

    override fun toString(): String {
        return "FolderInfo[id=$id, displayName=$displayName, checked=$checked, " +
            "notLoaded=$notLoaded, level=$level, fullPath=$fullPath]"
    }
}
