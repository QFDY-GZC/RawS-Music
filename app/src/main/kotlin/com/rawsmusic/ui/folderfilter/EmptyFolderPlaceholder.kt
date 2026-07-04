package com.rawsmusic.ui.folderfilter

class EmptyFolderPlaceholder(
    id: Int,
    fullPath: String,
    displayName: String,
    level: Int
) : FolderInfo(
    id = id,
    level = level,
    fullPath = fullPath,
    displayName = displayName,
    label = "",
    isStorage = false,
    notLoaded = false
)
