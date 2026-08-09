package com.rawsmusic.core.ui.widget.powerlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import com.rawsmusic.core.common.model.AudioFile

class ComposeGenericPowerListArtworkTest {
    @Test
    fun virtualCollectionKeyDoesNotReplaceResolvedArtworkIdentity() {
        val artworkKey = "audio:///music/song.flac|123|456"
        val item = TestVisualItem(
            stableId = 42L,
            stableKey = "artist␟album",
            coverKey = artworkKey
        )

        val audioFile = item.toPowerListAudioFile()

        assertEquals(42L, audioFile.id)
        assertTrue(audioFile.path.isBlank())
        assertEquals(artworkKey, audioFile.albumArtPath)
        assertEquals(artworkKey, audioFile.coverKey)
    }

    @Test
    fun contentArtworkIdentityRemainsAvailableForVirtualRows() {
        val artworkKey = "content://media/external/audio/albumart/7"
        val item = TestVisualItem(
            stableId = 7L,
            stableKey = "search:album:7",
            coverKey = artworkKey
        )

        assertEquals(artworkKey, item.toPowerListAudioFile().coverKey)
    }

    @Test
    fun songMetaContainsOnlyFormatBitDepthAndSampleRate() {
        val song = AudioFile(
            path = "/music/example.flac",
            duration = 245_000L,
            format = "flac",
            encodingFormat = "flac",
            bitsPerSample = 24,
            sampleRate = 96_000,
            bitRate = 1_200_000
        )

        assertEquals("FLAC｜24bit｜96 kHz", formatPowerListSongMeta(song))
    }

    @Test
    fun songMetaOmitsUnknownFieldsInsteadOfShowingPlaceholders() {
        val song = AudioFile(
            path = "/music/example.mp3",
            format = "mp3",
            sampleRate = 44_100
        )

        assertEquals("MP3｜44.1 kHz", formatPowerListSongMeta(song))
    }

    private data class TestVisualItem(
        override val stableId: Long,
        override val stableKey: String,
        override val coverKey: String
    ) : PowerListVisualItem {
        override val sharedCoverElementId: String = "cover:test:$stableId"
        override val title: String = "Title"
        override val subtitle: String = "Subtitle"
        override val meta: String = "Meta"
    }
}
