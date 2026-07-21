package com.rawsmusic.core.ui.widget.bitmaps

import java.io.File

/** Single folder-art naming policy shared by provider and original-art viewer. */
internal object FolderArtworkLocator {
    private val candidateNames = listOf(
        "folder.jpg", "Folder.jpg",
        "cover.jpg", "Cover.jpg",
        "album.jpg", "Album.jpg",
        "front.jpg", "Front.jpg"
    )

    fun find(audioPath: String?, minimumBytes: Long = 1024L): File? {
        val directory = audioPath
            ?.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.parentFile
            ?: return null

        return candidateNames.asSequence()
            .map { File(directory, it) }
            .firstOrNull { file ->
                file.exists() && file.canRead() && file.length() > minimumBytes
            }
    }
}
