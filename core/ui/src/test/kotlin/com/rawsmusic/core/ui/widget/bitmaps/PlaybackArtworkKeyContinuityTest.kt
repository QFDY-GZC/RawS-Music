package com.rawsmusic.core.ui.widget.bitmaps

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackArtworkKeyContinuityTest {
    @After
    fun tearDown() {
        PlaybackArtworkKeyContinuity.clearForTests()
    }

    @Test
    fun metadataOnlyRewritePreservesExactOldToNewPairForMultipleConsumers() {
        val oldKey = "audio:///music/a.flac|100|10"
        val newKey = "audio:///music/a.flac|104|20"

        PlaybackArtworkKeyContinuity.markMetadataOnlyRewrite(oldKey, newKey)

        assertTrue(PlaybackArtworkKeyContinuity.preservesVisual(oldKey, newKey))
        assertTrue(PlaybackArtworkKeyContinuity.preservesVisual(oldKey, newKey))
    }

    @Test
    fun unrelatedArtworkMutationCannotReuseMetadataContinuity() {
        val oldKey = "audio:///music/a.flac|100|10"
        val metadataKey = "audio:///music/a.flac|104|20"
        val laterCoverMutationKey = "audio:///music/a.flac|180|30"

        PlaybackArtworkKeyContinuity.markMetadataOnlyRewrite(oldKey, metadataKey)

        assertFalse(PlaybackArtworkKeyContinuity.preservesVisual(oldKey, laterCoverMutationKey))
        assertFalse(PlaybackArtworkKeyContinuity.preservesVisual(metadataKey, laterCoverMutationKey))
    }

    @Test
    fun artworkRewriteIsClassifiedWithoutPretendingPixelsAreUnchanged() {
        val oldKey = "audio:///music/a.flac|100|10"
        val newKey = "audio:///music/a.flac|180|30"

        PlaybackArtworkKeyContinuity.markArtworkRewrite(oldKey, newKey)

        assertEquals(
            PlaybackArtworkKeyContinuity.RewriteKind.ArtworkChanged,
            PlaybackArtworkKeyContinuity.rewriteKind(oldKey, newKey),
        )
        assertFalse(PlaybackArtworkKeyContinuity.preservesVisual(oldKey, newKey))
        assertNull(PlaybackArtworkKeyContinuity.rewriteKind(newKey, oldKey))
    }
}
