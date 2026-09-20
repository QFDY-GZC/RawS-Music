package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReferenceShufflePlannerTest {
    @Test
    fun halfSamplingKeepsOlderHalfBeforeRecentlyPlayedHalf() {
        val songs = (1L..6L).map { AudioFile(id = it, path = "/$it.mp3") }
        val playedAt = songs.associate { it.id to it.id * 100L }

        val result = ReferenceShufflePlanner.songs(songs, 0.5f, playedAt, Random(7))

        assertEquals(setOf(1L, 2L, 3L), result.take(3).map { it.id }.toSet())
        assertEquals(setOf(4L, 5L, 6L), result.drop(3).map { it.id }.toSet())
    }

    @Test
    fun categoryOnlyKeepsSongOrderInsideEachCategory() {
        val first = listOf(song(1), song(2), song(3))
        val second = listOf(song(4), song(5), song(6))

        val result = ReferenceShufflePlanner.categories(
            listOf(first, second),
            ReferenceShufflePlanner.CATEGORIES_ONLY,
            1f,
            emptyMap(),
            Random(2),
        )

        assertTrue(result == first + second || result == second + first)
        assertTrue(result.windowed(3).any { it == first } || result.windowed(3).any { it == second })
    }

    @Test
    fun largeListThresholdMatchesReference() {
        assertFalse(ReferenceShufflePlanner.preserveOnManualSelection(9_999, false, true))
        assertTrue(ReferenceShufflePlanner.preserveOnManualSelection(10_000, false, true))
        assertTrue(ReferenceShufflePlanner.preserveOnManualSelection(2, true, false))
    }

    private fun song(id: Long) = AudioFile(id = id, path = "/$id.mp3")
}
