package com.rawsmusic.core.ui.widget.bitmaps

/**
 * Project-style file-identity artwork id for the VirtualList path.
 *
 * This deliberately does not invent album/folder/entity aliases.  Until RawSMusic has a scanner
 * backed artwork type/id resolver, the only stable identity for list/grid artwork is the song's own
 * versioned cover key.  The same id is used by the cell, record, provider request, and cache probe.
 */
data class FileArtworkId(val value: String) {
    val isBlank: Boolean get() = value.isBlank()

    companion object {
        fun fromCoverKey(key: String): FileArtworkId = FileArtworkId(key.trim())
    }
}
