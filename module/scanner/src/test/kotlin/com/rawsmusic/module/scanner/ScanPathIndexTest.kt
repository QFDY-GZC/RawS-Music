package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.*
import org.junit.Test

class ScanPathIndexTest {
    @Test fun documentUrisUseDocumentHierarchyNotEncodedSlashText() {
        val song = AudioFile(id = 1, path = "content://source/tree/primary%3AMusic/document/primary%3AMusic%2FA.flac")
        val index = ScanPathIndex(listOf(song))
        assertEquals(listOf(song), index.directRows("content://source/tree/primary%3AMusic/document/primary%3AMusic"))
        assertTrue(index.hasRowsUnder("content://source/tree/primary%3AMusic"))
        assertTrue(index.directRows("content://source/tree/primary%3Amusic").isEmpty())
    }
    @Test fun sameNamesInDifferentDirectoriesStaySeparate() {
        val first = AudioFile(id = 1, path = "/a/song.flac")
        val second = AudioFile(id = 2, path = "/b/song.flac")
        val index = ScanPathIndex(listOf(first, second))
        assertEquals(listOf(first), index.directRows("/a"))
        assertEquals(listOf(second), index.rowsForPath("/b/song.flac"))
    }
    @Test fun preservesCueExpansionAndNormalizesFilesystemSeparators() {
        val first = AudioFile(id = 1, path = "D:\\Music\\disc.flac", cueTrackIndex = 1)
        val second = first.copy(id = 2, cueTrackIndex = 2, cueOffsetMs = 1000)
        assertEquals(listOf(first, second), ScanPathIndex(listOf(first, second)).rowsForPath("d:/music/disc.flac"))
        assertNotEquals(scanRowKey(first), scanRowKey(second))
    }
    @Test fun contentUriCaseIsSignificant() {
        val song = AudioFile(id = 1, path = "content://source/document/primary%3AMusic%2FA.flac")
        val index = ScanPathIndex(listOf(song))
        assertEquals(listOf(song), index.rowsForPath(song.path))
        assertTrue(index.rowsForPath(song.path.replace("A.flac", "a.flac")).isEmpty())
    }
    @Test fun nestedRowsCountAsBaselineButNotDirectRows() {
        val song = AudioFile(id = 1, path = "/a/b/song.flac")
        val index = ScanPathIndex(listOf(song))
        assertTrue(index.hasRowsUnder("/a"))
        assertTrue(index.directRows("/a").isEmpty())
    }
}
