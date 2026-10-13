package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.*
import org.junit.Test

class ServiceLyricOwnershipTest {
    private val song = AudioFile(path = "/music/a.mp3", title = "a", artist = "artist",
        album = "album", duration = 200000, albumArtPath = "", fileSize = 1000, dateModified = 10)

    @Test fun pauseAndResumeArtworkEnrichmentKeepsLoadedLyrics() {
        assertFalse(shouldInvalidateServiceLyrics(song, song.copy(albumArtPath = "audio:///music/a.mp3|1000|10")))
    }

    @Test fun sameSourceMetadataEnrichmentKeepsLoadedLyrics() {
        assertFalse(shouldInvalidateServiceLyrics(song, song.copy(title = "new title", artist = "new artist",
            album = "new album", duration = 200001)))
    }

    @Test fun changedSourceOrFileVersionInvalidatesLyrics() {
        assertTrue(shouldInvalidateServiceLyrics(song, song.copy(path = "/music/b.mp3")))
        assertTrue(shouldInvalidateServiceLyrics(song, song.copy(fileSize = 2000)))
        assertTrue(shouldInvalidateServiceLyrics(song, song.copy(dateModified = 20)))
        assertTrue(shouldInvalidateServiceLyrics(song, song.copy(cueTrackIndex = 2)))
        assertTrue(shouldInvalidateServiceLyrics(song, song.copy(cueOffsetMs = 5000)))
        assertTrue(shouldInvalidateServiceLyrics(song, song.copy(cueEndMs = 10000)))
        assertTrue(shouldInvalidateServiceLyrics(null, song))
    }

    @Test fun purePauseAndResumeDoesNotInvalidateLyrics() {
        assertFalse(shouldInvalidateServiceLyrics(song, song.copy()))
    }
}
