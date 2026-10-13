package com.rawsmusic

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Test

class HomeCarouselQueueProjectionTest {
    private fun song(index: Int) = AudioFile(path = "/$index.flac", title = "$index")

    @Test fun retainsAllResolvedNeighboursRatherThanWrappingSevenItems() {
        val relative = (0..18).map(::song)
        val result = buildHomeCarouselQueueProjection(
            PlayQueue(relative.reversed(), 9), relative[9], relative[8], relative[10], relative,
        )
        assertEquals(relative, result.songs)
        assertEquals(9, result.currentIndex)
    }

    @Test fun exhaustedShufflePreviewDoesNotInventPhysicalNeighbours() {
        val source = (0..20).map(::song)
        val relative = List<AudioFile?>(19) { if (it < 9) null else song(it) }
        val result = buildHomeCarouselQueueProjection(
            PlayQueue(source, 9), source[9], null, source[10], relative,
        )
        assertEquals((9..18).map(::song), result.songs)
        assertEquals(0, result.currentIndex)
    }

    @Test fun visibleNeighboursStayIdenticalAfterRecentringFourTracks() {
        val old = (0..18).map(::song)
        val next = (4..22).map(::song)
        val before = buildHomeCarouselQueueProjection(PlayQueue(old, 9), old[9], old[8], old[10], old)
        val after = buildHomeCarouselQueueProjection(PlayQueue(next, 9), next[9], next[8], next[10], next)
        assertEquals(before.songs.subList(9, 18), after.songs.subList(5, 14))
    }
}
