package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AudioUtils
import org.junit.Assert.*
import org.junit.Test

class SacdScannerPolicyTest {
    @Test fun isoIsAScanCandidateWhileNormalFormatsRemainSupported() {
        assertTrue(AudioUtils.isAudioFile("album.ISO"))
        assertTrue(AudioUtils.isAudioFile("track.flac"))
        assertTrue(AudioUtils.isAudioFile("track.dsf"))
        assertFalse(AudioUtils.isAudioFile("document.pdf"))
    }

    @Test fun cuePreferenceDoesNotTurnAnUnrecognizedIsoIntoOneFakeSong() {
        val iso = AudioFile(path = "not-a-disc.iso", format = "ISO")
        assertTrue(MediaStoreScanner.expandContainerTracks(iso, expandCue = false).isEmpty())
        val normal = AudioFile(path = "track.flac", format = "FLAC")
        assertEquals(listOf(normal), MediaStoreScanner.expandContainerTracks(normal, expandCue = false))
    }
}
