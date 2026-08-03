package com.rawsmusic.core.ui.widget.powerlist

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

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
