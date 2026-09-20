package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueBindingTest {
    private fun song(path: String, cue: Long = 0L, cueIndex: Int = 0) = AudioFile(
        path = path,
        cueOffsetMs = cue,
        cueTrackIndex = cueIndex,
    )

    @Test
    fun committedSongWinsOverOneFrameStaleReportedIndex() {
        val queue = listOf(song("a.flac"), song("b.flac"), song("c.flac"))
        assertEquals(1, resolvePlaybackQueueBindingIndex(queue[1], queue, reportedIndex = 0))
    }

    @Test
    fun cueIdentityIsExact() {
        val queue = listOf(song("album.cue", 0L, 1), song("album.cue", 12000L, 2))
        assertEquals(1, resolvePlaybackQueueBindingIndex(song("album.cue", 12000L, 2), queue, 0))
    }

    @Test
    fun outOfBandItemFallsBackOnlyToValidReportedIndex() {
        val queue = listOf(song("a.flac"), song("b.flac"))
        assertEquals(1, resolvePlaybackQueueBindingIndex(song("priority.flac"), queue, 1))
        assertEquals(-1, resolvePlaybackQueueBindingIndex(song("priority.flac"), queue, 4))
    }
}
