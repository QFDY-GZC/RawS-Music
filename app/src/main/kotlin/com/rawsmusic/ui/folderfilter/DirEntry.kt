package com.rawsmusic.ui.folderfilter

data class DirEntry(
    val path: String,
    val displayName: String,
    var flags: Int = 0
) : Comparable<DirEntry> {
    override fun compareTo(other: DirEntry): Int {
        val nameCmp = displayName.compareTo(other.displayName, ignoreCase = true)
        if (nameCmp != 0) return nameCmp
        val pathCmp = path.compareTo(other.path, ignoreCase = true)
        if (pathCmp != 0) return pathCmp
        return flags.compareTo(other.flags)
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is DirEntry) return false
        return flags == other.flags &&
            path.equals(other.path, ignoreCase = true) &&
            displayName.equals(other.displayName, ignoreCase = true)
    }

    override fun hashCode(): Int {
        var result = path.lowercase().hashCode()
        result = 31 * result + displayName.lowercase().hashCode()
        result = 31 * result + flags
        return result
    }
}
