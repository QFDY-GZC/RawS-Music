package com.rawsmusic.module.data.repository

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.stableFolderHierarchyId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FolderHierarchyIndexTest {
    @Test
    fun buildsStableParentChildTreeAndSubtreeAggregates() {
        val songs = listOf(
            AudioFile(path = "/storage/emulated/0/Music/Artist/Album/a.flac", duration = 100L),
            AudioFile(path = "/storage/emulated/0/Music/Artist/Other/b.flac", duration = 200L),
            AudioFile(path = "/storage/emulated/0/Download/c.mp3", duration = 300L),
        )

        val index = buildFolderHierarchyIndex(songs)
        val music = index.single { it.path == "/storage/emulated/0/Music" }
        val artist = index.single { it.path == "/storage/emulated/0/Music/Artist" }
        val album = index.single { it.path == "/storage/emulated/0/Music/Artist/Album" }
        val download = index.single { it.path == "/storage/emulated/0/Download" }

        assertEquals(0L, music.parentId)
        assertEquals(stableFolderHierarchyId(music.path), artist.parentId)
        assertEquals(stableFolderHierarchyId(artist.path), album.parentId)
        assertEquals(2, music.hierarchicalSongCount)
        assertEquals(300L, music.hierarchicalDurationMs)
        assertEquals(1, album.directSongCount)
        assertEquals(1, download.directSongCount)
        assertEquals(300L, download.hierarchicalDurationMs)
        assertNotEquals(music.id, download.id)
    }

    @Test
    fun hierarchyIdentityDoesNotDependOnSongOrdering() {
        val a = AudioFile(path = "/storage/emulated/0/Music/A/a.flac")
        val b = AudioFile(path = "/storage/emulated/0/Music/B/b.flac")
        val forward = buildFolderHierarchyIndex(listOf(a, b)).associate { it.path to it.id }
        val reverse = buildFolderHierarchyIndex(listOf(b, a)).associate { it.path to it.id }
        assertEquals(forward, reverse)
    }

    @Test
    fun safDocumentIdsProduceRealFolderHierarchyWithoutChangingSongPaths() {
        val first = AudioFile(
            path = "content://com.android.externalstorage.documents/tree/primary%3AMusic/" +
                "document/primary%3AMusic%2FAlbum%2F01.flac",
            duration = 100L,
        )
        val second = AudioFile(
            path = "content://com.android.externalstorage.documents/tree/primary%3AMusic%2FAlbum/" +
                "document/primary%3AMusic%2FAlbum%2F02.flac",
            duration = 200L,
        )

        val index = buildFolderHierarchyIndex(listOf(first, second))
        val music = index.single { it.path == "/storage/emulated/0/Music" }
        val album = index.single { it.path == "/storage/emulated/0/Music/Album" }

        assertEquals("Music", music.name)
        assertEquals("Album", album.name)
        assertEquals(stableFolderHierarchyId(music.path), album.parentId)
        assertEquals(2, album.directSongCount)
        assertEquals(2, music.hierarchicalSongCount)
        assertEquals(300L, music.hierarchicalDurationMs)
    }

    @Test
    fun safAndDirectPathsWithPlusSignShareOneFolder() {
        val direct = AudioFile(path = "/storage/1234-ABCD/Music/A+B/01.flac")
        val saf = AudioFile(
            path = "content://com.android.externalstorage.documents/tree/1234-ABCD%3AMusic/" +
                "document/1234-ABCD%3AMusic%2FA%2BB%2F02.flac",
        )

        val index = buildFolderHierarchyIndex(listOf(direct, saf))
        val album = index.single { it.path == "/storage/1234-ABCD/Music/A+B" }

        assertEquals("A+B", album.name)
        assertEquals(2, album.directSongCount)
        assertEquals(1, index.count { it.path == album.path })
    }
}
