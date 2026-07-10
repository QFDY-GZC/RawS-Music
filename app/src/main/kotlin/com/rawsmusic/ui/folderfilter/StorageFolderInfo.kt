package com.rawsmusic.ui.folderfilter

class StorageFolderInfo(
    id: Int,
    fullPath: String,
    displayName: String,
    label: String,
    isStorage: Boolean,
    notLoaded: Boolean,
    val isRemovable: Boolean,
    val isUnavailable: Boolean,
    val isUsb: Boolean
) : FolderInfo(
    id = id,
    level = 0,
    fullPath = fullPath,
    displayName = displayName,
    label = label,
    isStorage = isStorage,
    notLoaded = notLoaded
) {
    override fun canExpand(): Boolean = !isUnavailable
}
