package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PlaybackSourceOverridePolicyTest {
    private val song = AudioFile(
        id = 7L,
        path = "/music/original.flac",
        sampleRate = 96_000,
        bitsPerSample = 24,
        channelCount = 2,
        format = "FLAC",
    )

    @Test
    fun originalPathNormalizesToNoOverride() {
        assertNull(
            PlaybackSourceOverridePolicy.normalizeForSong(
                song, song.path, "test", 44_100, 2, 16, "FLAC", 1L,
            )
        )
    }

    @Test
    fun rendererMetadataChangesWithoutChangingSemanticIdentity() {
        val override = PlaybackSourceOverridePolicy.normalizeForSong(
            song, "/cache/variant.flac", "test", 44_100, 2, 16, "FLAC", 9L,
        )!!
        val renderer = override.rendererSong(song)
        assertEquals(song.id, renderer.id)
        assertEquals(song.title, renderer.title)
        assertEquals("/cache/variant.flac", renderer.path)
        assertEquals(44_100, renderer.sampleRate)
        assertEquals(16, renderer.bitsPerSample)
        assertEquals(2, renderer.channelCount)
    }

    @Test
    fun overrideOwnershipIncludesCueIdentity() {
        val cue = song.copy(cueOffsetMs = 12_000L, cueTrackIndex = 2)
        val override = PlaybackSourceOverridePolicy.normalizeForSong(
            cue, "/cache/variant.flac", "test", 44_100, 2, 16, "FLAC", 1L,
        )!!
        assertTrue(override.matches(cue))
        assertFalse(override.matches(cue.copy(cueTrackIndex = 3)))
        assertFalse(override.matches(song))
    }

    @Test
    fun sameTargetIgnoresDiagnosticGenerationButNotFormat() {
        val first = PlaybackSourceOverridePolicy.normalizeForSong(
            song, "/cache/variant.flac", "one", 44_100, 2, 16, "FLAC", 1L,
        )!!
        val second = PlaybackSourceOverridePolicy.normalizeForSong(
            song, "/cache/variant.flac", "two", 44_100, 2, 16, "flac", 2L,
        )!!
        val changed = second.copy(sampleRate = 48_000)
        assertTrue(PlaybackSourceOverridePolicy.sameTarget(first, second))
        assertFalse(PlaybackSourceOverridePolicy.sameTarget(first, changed))
    }

    @Test
    fun sourceExistenceIsValidatedSeparatelyFromIdentity() {
        val temp = File.createTempFile("rsm_variant", ".flac")
        try {
            val override = PlaybackSourceOverridePolicy.normalizeForSong(
                song, temp.absolutePath, "test", 44_100, 2, 16, "FLAC", 1L,
            )!!
            assertTrue(override.sourceExists())
            temp.delete()
            assertFalse(override.sourceExists())
        } finally {
            temp.delete()
        }
    }
}
