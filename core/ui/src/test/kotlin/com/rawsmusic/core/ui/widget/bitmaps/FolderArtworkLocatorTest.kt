package com.rawsmusic.core.ui.widget.bitmaps

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class FolderArtworkLocatorTest {
    private lateinit var directory: File
    private lateinit var audio: File

    @Before
    fun setUp() {
        directory = createTempDir(prefix = "folder-artwork-locator-")
        audio = File(directory, "song.flac").apply { writeBytes(ByteArray(16)) }
    }

    @After
    fun tearDown() {
        directory.deleteRecursively()
    }

    @Test
    fun findsSharedFolderCoverNames() {
        val cover = File(directory, "Cover.jpg").apply { writeBytes(ByteArray(2_048)) }
        assertEquals(cover.absolutePath, FolderArtworkLocator.find(audio.absolutePath)?.absolutePath)
    }

    @Test
    fun ignoresTinyPlaceholders() {
        File(directory, "folder.jpg").writeBytes(ByteArray(128))
        assertNull(FolderArtworkLocator.find(audio.absolutePath))
    }
}
