package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class OriginalArtworkCacheFilesTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun legacyArtCacheWithJpegBytesBecomesLoadableImageFile() {
        val bytes = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte()) + ByteArray(1200)
        val legacy = temporaryFolder.newFile("cover.art").apply { writeBytes(bytes) }

        val image = OriginalArtworkCacheFiles.imageFileForViewer(legacy)

        assertNotNull(image)
        assertEquals("cover.jpg", image!!.name)
        assertTrue(image.isFile)
        assertArrayEquals(bytes, image.readBytes())
        assertEquals(image, OriginalArtworkCacheFiles.imageFileForViewer(legacy))
    }

    @Test
    fun pngCacheUsesPngExtensionAndRejectsUnknownBytes() {
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) + ByteArray(1200)
        val png = temporaryFolder.newFile("picture.art").apply { writeBytes(pngBytes) }
        val unknown = temporaryFolder.newFile("unknown.art").apply { writeBytes(ByteArray(1208)) }

        assertEquals("picture.png", OriginalArtworkCacheFiles.imageFileForViewer(png)?.name)
        assertNull(OriginalArtworkCacheFiles.imageFileForViewer(unknown))
    }
}
