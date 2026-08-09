package com.rawsmusic.core.ui.widget.bitmaps

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ArtworkSourceSelectionPolicyTest {
    private lateinit var directory: File

    @Before
    fun setUp() {
        ArtworkSourceIndex.clear()
        directory = createTempDir(prefix = "artwork-source-policy-")
    }

    @After
    fun tearDown() {
        ArtworkSourceIndex.clear()
        directory.deleteRecursively()
    }

    @Test
    fun audioDecodeOrderKeepsFolderCoverLast() {
        assertEquals(
            listOf(
                RawArtworkPolicy.DecodeStage.RegionHandle,
                RawArtworkPolicy.DecodeStage.NativeSource,
                RawArtworkPolicy.DecodeStage.Ffmpeg,
                RawArtworkPolicy.DecodeStage.MediaMetadataRetriever,
                RawArtworkPolicy.DecodeStage.FolderCover
            ),
            ArtworkSourceSelectionPolicy.audioDecodeOrder
        )
    }

    @Test
    fun indexedFolderCoverCannotBypassUncheckedEmbeddedSource() {
        val key = "audio:///music/song.flac|123|456"
        val folder = imageFile("folder.jpg")
        ArtworkSourceIndex.rememberSource(
            key,
            folder.absolutePath,
            ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover
        )

        assertFalse(ArtworkSourceIndex.mayUseFolderFallback(key))
        assertNull(
            ArtworkSourceIndex.sourcePathFor(
                key,
                ArtworkSourceSelectionPolicy.embeddedIndexedKinds
            )
        )

        ArtworkSourceIndex.markEmbeddedAbsent(key)
        assertTrue(ArtworkSourceIndex.mayUseFolderFallback(key))
        assertEquals(folder.absolutePath, ArtworkSourceIndex.sourcePathFor(key))
    }

    @Test
    fun confirmedEmbeddedSourceCannotBeDowngradedByLateMiss() {
        val key = "audio:///music/song.mp3|123|456"
        val embedded = imageFile("embedded.jpg")
        ArtworkSourceIndex.rememberSource(
            key,
            embedded.absolutePath,
            ArtworkSourceSelectionPolicy.IndexedSourceKind.Embedded
        )
        ArtworkSourceIndex.markEmbeddedAbsent(key)

        assertFalse(ArtworkSourceIndex.mayUseFolderFallback(key))
        assertEquals(
            embedded.absolutePath,
            ArtworkSourceIndex.sourcePathFor(
                key,
                ArtworkSourceSelectionPolicy.embeddedIndexedKinds
            )
        )
    }


    @Test
    fun lateFolderCommitIsRejectedAfterEmbeddedBecomesPresent() {
        val key = "audio:///music/race.flac|123|456"
        val folder = imageFile("race-folder.jpg")
        val permit = ArtworkSourceIndex.beginFolderFallback(key)
            ?: error("folder permit expected")

        ArtworkSourceIndex.markEmbeddedPresent(key)

        assertFalse(ArtworkSourceIndex.commitFolderSource(permit, folder.absolutePath))
        assertFalse(ArtworkSourceIndex.mayUseFolderFallback(key))
        assertNull(
            ArtworkSourceIndex.sourcePathFor(
                key,
                ArtworkSourceSelectionPolicy.folderIndexedKinds
            )
        )
    }

    @Test
    fun embeddedAndFolderUseIndependentSlotsWithEmbeddedAuthority() {
        val key = "audio:///music/slots.mp3|123|456"
        val folder = imageFile("slots-folder.jpg")
        val embedded = imageFile("slots-embedded.jpg")

        ArtworkSourceIndex.rememberSource(
            key,
            folder.absolutePath,
            ArtworkSourceSelectionPolicy.IndexedSourceKind.FolderCover
        )
        ArtworkSourceIndex.markEmbeddedAbsent(key)
        assertEquals(folder.absolutePath, ArtworkSourceIndex.sourcePathFor(key))

        ArtworkSourceIndex.rememberSource(
            key,
            embedded.absolutePath,
            ArtworkSourceSelectionPolicy.IndexedSourceKind.Embedded
        )

        assertEquals(embedded.absolutePath, ArtworkSourceIndex.sourcePathFor(key))
        assertFalse(ArtworkSourceIndex.mayUseFolderFallback(key))
        assertNull(
            ArtworkSourceIndex.sourcePathFor(
                key,
                ArtworkSourceSelectionPolicy.folderIndexedKinds
            )
        )
    }

    private fun imageFile(name: String): File = File(directory, name).apply {
        writeBytes(ByteArray(2_048) { (it % 251).toByte() })
    }
}
