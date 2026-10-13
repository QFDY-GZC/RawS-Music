package com.rawsmusic.core.common.sacd

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.*
import org.junit.Test

class SacdTrackProjectionTest {
    @Test fun tracksRetainThePhysicalImageAndHaveIndependentCueIdentity() {
        val source = AudioFile(path = "album.iso", title = "Disc", fileSize = 4_500_000_000L)
        val tracks = (1..3).map { number ->
            SacdIsoBridge.Track(0, number, 2, (number - 1L) * 750L, 750, number == 2,
                "Track $number", "Artist", "Album", 2026, 1, "Composer", "Album Artist")
        }
        val projected = SacdIsoBridge.project(source, tracks)
        assertEquals(3, projected.size)
        assertEquals(3, projected.map { it.id }.distinct().size)
        assertEquals(listOf(0L, 10_000L, 20_000L), projected.map { it.cueOffsetMs })
        assertEquals(listOf(10_000L, 20_000L, 30_000L), projected.map { it.cueEndMs })
        assertTrue(projected.all { it.path == source.path && it.duration == 10_000L && it.isDsdFormat })
        assertTrue(projected.all { it.fileSize == source.fileSize && it.sampleRate == 2_822_400 && it.bitsPerSample == 1 })
        assertEquals("DSD64 (DST)", projected[1].encodingFormat)
        assertEquals(projected.map { it.id }, SacdIsoBridge.project(source, tracks).map { it.id })
    }

    @Test fun normalAudioIsNotClassifiedAsAnIso() {
        assertFalse(SacdIsoBridge.isIso(AudioFile(path = "track.flac", format = "FLAC")))
        assertTrue(SacdIsoBridge.isIso(AudioFile(path = "disc.ISO")))
        assertTrue(SacdIsoBridge.isIso(AudioFile(path = "content://provider/42", format = "ISO")))
    }

    @Test fun sourceRegistryIsBoundedAndPreservesAreaSelection() {
        repeat(200) { SacdIsoSourceRegistry.register("content://test/$it", it % 2) }
        assertNull(SacdIsoSourceRegistry.area("content://test/0"))
        assertEquals(1, SacdIsoSourceRegistry.area("content://test/199"))
    }
}
