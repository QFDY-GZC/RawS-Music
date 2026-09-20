package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackArtworkKeyTest {
    @Test
    fun ordinaryFileUsesExactSameArtworkIdentityAsListCoverKey() {
        val song = AudioFile(
            path = "/music/song.ape",
            fileSize = 27_426_609L,
            dateModified = 1_787_117_794_000L,
            cueTrackIndex = 0,
        )

        assertEquals(song.coverKey, song.fileArtworkKeyOrNull())
        assertEquals(
            "audio:///music/song.ape|27426609|1787117794000",
            song.fileArtworkKeyOrNull(),
        )
    }

    @Test
    fun cueRowsSharePhysicalArtworkIdentity() {
        val first = AudioFile(
            path = "/music/album.flac",
            fileSize = 123L,
            dateModified = 456L,
            cueOffsetMs = 0L,
            cueTrackIndex = 1,
        )
        val second = first.copy(cueOffsetMs = 120_000L, cueTrackIndex = 2)

        assertEquals(first.coverKey, first.fileArtworkKeyOrNull())
        assertEquals(first.fileArtworkKeyOrNull(), second.fileArtworkKeyOrNull())
    }
}
