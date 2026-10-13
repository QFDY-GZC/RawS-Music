package com.rawsmusic.metadata

internal object LibraryMetadataMatchResources {
    data class Needs(val lyrics: Boolean, val cover: Boolean)
    fun needs(mode: LibraryMetadataMatchMode, hasLyrics: () -> Boolean, hasCover: () -> Boolean): Needs {
        return when (mode) {
            LibraryMetadataMatchMode.LYRICS_ONLY -> Needs(true, false)
            LibraryMetadataMatchMode.FILL_MISSING -> Needs(!hasLyrics(), !hasCover())
            LibraryMetadataMatchMode.MATCH_CURRENT, LibraryMetadataMatchMode.REMATCH_ALL -> Needs(true, true)
        }
    }
    fun workerCount(maxHeapBytes: Long): Int = when {
        maxHeapBytes < 192L * 1024 * 1024 -> 1
        maxHeapBytes < 512L * 1024 * 1024 -> 2
        else -> 4
    }
}
