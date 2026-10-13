package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.*
import org.junit.Test

class MetadataScanOutcomeTest {
    @Test fun readableAudioWithoutTagsOrArtworkIsComplete() {
        assertTrue(metadataReadCompleted(44100, 2))
    }
    @Test fun guessedFormatWithoutAudioStreamIsNotComplete() {
        assertFalse(metadataReadCompleted(0, 0))
    }
    @Test fun failureKeepsExistingCueTracksAndGoodMetadata() {
        val raw = AudioFile(path = "/a.flac", title = "a", fileSize = 20, dateModified = 30)
        val old = raw.copy(title = "Good", cueTrackIndex = 1, cueOffsetMs = 1000, fileSize = 10)
        assertEquals(listOf(old), MetadataScanOutcome(raw, false).resolveAgainst(listOf(old)))
    }
}
