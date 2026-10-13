package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

class FolderGroupSafTest {
    @Test
    fun differentSafTreeRootsForSameDirectoryFormOneReadableFolder() {
        val songs = listOf(
            AudioFile(
                path = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                    "document/primary%3AMusic%2FAlbum%2F01.flac",
            ),
            AudioFile(
                path = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlbum/" +
                    "document/primary%3AMusic%2FAlbum%2F02.flac",
            ),
        )

        val folder = songs.toFolderGroups().single()

        assertEquals("/storage/emulated/0/Music/Album", folder.path)
        assertEquals("Album", folder.name)
        assertEquals("Music", folder.parentName)
        assertEquals(2, folder.songCount)
        assertEquals(songs.map { it.path }, folder.songs.map { it.path })
    }
}
