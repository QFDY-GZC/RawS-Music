package com.rawsmusic.module.scanner

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IncrementalBaselineIndexTest {
    @Test
    fun `same size and mtime reuses enriched database row`() {
        val old = song(path = "/Music/A.flac", size = 1234, modified = 55, title = "Enriched")
        val raw = song(path = "/music/a.flac", size = 1234, modified = 55)

        assertEquals(listOf(old), IncrementalBaselineIndex(listOf(old)).reusableSongs(raw))
    }

    @Test
    fun `changed fingerprint must be enriched again`() {
        val old = song(path = "/Music/A.flac", size = 1234, modified = 55)

        assertNull(
            IncrementalBaselineIndex(listOf(old)).reusableSongs(
                song(path = "/Music/A.flac", size = 1235, modified = 56)
            )
        )
    }

    @Test
    fun `unknown mtime never suppresses tag parsing`() {
        val old = song(path = "/Music/A.flac", size = 1234, modified = 0)
        assertNull(IncrementalBaselineIndex(listOf(old)).reusableSongs(old))
    }

    @Test
    fun `unchanged cue file restores every expanded row`() {
        val cue1 = song("/Music/live.flac", 9000, 77, title = "Part 1", cueTrack = 1)
        val cue2 = song("/Music/live.flac", 9000, 77, title = "Part 2", cueTrack = 2)
        val raw = song("/Music/live.flac", 9000, 77)

        assertEquals(
            listOf(cue1, cue2),
            IncrementalBaselineIndex(listOf(cue2, cue1)).reusableSongs(raw)
        )
    }

    private fun song(
        path: String,
        size: Long,
        modified: Long,
        title: String = "",
        cueTrack: Int = 0
    ) = AudioFile(
        path = path,
        fileSize = size,
        dateModified = modified,
        title = title,
        cueTrackIndex = cueTrack,
        cueOffsetMs = if (cueTrack > 0) cueTrack * 1_000L else 0L
    )
}
