package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import org.junit.Assert.*
import org.junit.Test

class ShuffleQueueCursorCostTest {
    @Test fun movingCursorDoesNotRewriteTraversalOrRebuildItsIndex() {
        val songs = List(4000) { AudioFile(path = "track-$it") }
        val prefs = Preferences((songs.indices).reversed().joinToString(","))
        val controller = ShuffleQueueController(prefs)
        var queue = PlayQueue(songs, songs.lastIndex)
        assertEquals(3998, controller.peekNextIndex(queue))
        assertEquals(1, prefs.orderWrites)
        repeat(100) {
            val next = controller.nextIndex(queue)
            queue = queue.copy(currentIndex = next)
            assertEquals(next + 1, controller.peekPreviousIndex(queue))
        }
        assertEquals(1, prefs.orderWrites)
        assertEquals(100, prefs.cursor)
    }
    @Test fun newSnapshotWithSameSizeCannotUseOldQueueSignature() {
        val songs = listOf(AudioFile(path = "a"), AudioFile(path = "b"))
        val controller = ShuffleQueueController(Preferences("0,1"))
        val queue = PlayQueue(songs, 0)
        assertEquals(1, controller.peekNextIndex(queue))
        val other = queue.copy(songs = listOf(AudioFile(path = "x"), AudioFile(path = "y")))
        assertEquals(-1, controller.peekNextIndex(other))
        assertEquals(1, controller.peekNextIndex(queue))
        assertEquals(1, controller.peekNextIndex(queue.copy(songs = songs.map { it.copy() })))
    }
    private class Preferences(initialOrder: String) : ShuffleQueuePreferences {
        var orderWrites = 0
        override var order = initialOrder
            set(value) { field = value; orderWrites++ }
        override var cursor = 0
        override val playMode = PlayMode.SHUFFLE_ALL
        override val randomFactor = 1f
        override val categoryMode = 0
        override val noReshuffle = true
        override val noReshuffleForLargeLists = true
        override fun clearOriginalQueue() = Unit
    }
}
