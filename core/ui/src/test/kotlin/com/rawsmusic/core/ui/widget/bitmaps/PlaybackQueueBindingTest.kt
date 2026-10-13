package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile
import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackQueueBindingTest {
    @Test fun correctReportedIndexDoesNotScanLargeQueue() {
        val current = song("last.flac", 12000L, 2)
        var reads = 0
        val queue = object : AbstractList<AudioFile>() {
            override val size = 10000
            override fun get(index: Int): AudioFile {
                reads++
                return if (index == 9999) current else song("track-$index.flac")
            }
        }
        assertEquals(9999, resolvePlaybackQueueBindingIndex(current, queue, 9999))
        assertEquals(1, reads)
    }
    @Test fun duplicateEntriesKeepTheReportedQueueRow() {
        val repeated = song("same.flac")
        assertEquals(1, resolvePlaybackQueueBindingIndex(repeated, listOf(repeated, repeated), 1))
    }
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
