package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Test

class ShuffleQueueControllerTest {
    private val songs = List(4) { AudioFile(id = it.toLong(), path = "song-$it") }

    @Test fun stalePreviewsCannotRewindTransportCursor() {
        val prefs = MemoryPreferences()
        val controller = ShuffleQueueController(prefs)
        var queue = PlayQueue(songs, 0)
        queue = queue.copy(currentIndex = controller.nextIndex(queue))
        queue = queue.copy(currentIndex = controller.nextIndex(queue))
        queue = queue.copy(currentIndex = controller.nextIndex(queue))
        assertEquals(3, queue.currentIndex)
        controller.peekNextIndex(PlayQueue(songs, 0))
        controller.peekPreviousIndex(PlayQueue(songs, 1))
        controller.peekRelativeIndex(PlayQueue(songs, 0), 2)
        val cursorField = ShuffleQueueController::class.java.getDeclaredField("cursor")
        cursorField.isAccessible = true
        assertEquals(3, cursorField.getInt(controller))
        assertEquals(2, controller.previousIndex(queue))
    }

    @Test fun previousAcrossCycleBoundaryReturnsTheActualLastPlayedSong() {
        val controller = ShuffleQueueController(MemoryPreferences())
        var queue = PlayQueue(songs, 3)
        queue = queue.copy(currentIndex = controller.nextIndex(queue))
        assertEquals(3, controller.previousIndex(queue))
        queue = queue.copy(currentIndex = 3)
        assertEquals(2, controller.peekPreviousIndex(queue))
        assertEquals(2, controller.previousIndex(queue))
    }

    @Test fun reversingAcrossBoundaryThenGoingForwardReusesTheSameCycle() {
        val controller = ShuffleQueueController(MemoryPreferences())
        var queue = PlayQueue(songs, 3)
        val incoming = controller.nextIndex(queue)
        queue = queue.copy(currentIndex = incoming)
        queue = queue.copy(currentIndex = controller.previousIndex(queue))
        queue = queue.copy(currentIndex = controller.previousIndex(queue))
        assertEquals(3, controller.nextIndex(queue))
        queue = queue.copy(currentIndex = 3)
        assertEquals(incoming, controller.nextIndex(queue))
    }

    @Test fun shuffleOnceStillStopsAtTheEnd() {
        val prefs = MemoryPreferences().apply { playMode = PlayMode.SHUFFLE_ONCE }
        val controller = ShuffleQueueController(prefs)
        val queue = PlayQueue(songs, 3)
        assertEquals(-1, controller.peekNextIndex(queue))
        assertEquals(-1, controller.nextIndex(queue))
    }

    @Test fun oldQueuePreviewCannotReplaceTheActiveShufflePlan() {
        val prefs = MemoryPreferences()
        val controller = ShuffleQueueController(prefs)
        val queue = PlayQueue(songs, 0)
        assertEquals(1, controller.nextIndex(queue))
        val oldQueue = PlayQueue(listOf(AudioFile(id = 99L, path = "old-song")), 0)
        assertEquals(-1, controller.peekNextIndex(oldQueue))
        assertEquals("0,1,2,3", prefs.order)
        assertEquals(2, controller.nextIndex(queue.copy(currentIndex = 1)))
    }

    @Test fun nextAtTheCycleBoundaryMatchesThePreparedPreview() {
        val controller = ShuffleQueueController(MemoryPreferences())
        val queue = PlayQueue(songs, 3)
        val preview = controller.peekNextIndex(queue)
        assertEquals(preview, controller.nextIndex(queue))
    }

    private class MemoryPreferences : ShuffleQueuePreferences {
        override var order = "0,1,2,3"
        override var cursor = 0
        override var playMode = PlayMode.SHUFFLE_ALL
        override val randomFactor = 1f
        override val categoryMode = 0
        override val noReshuffle = true
        override val noReshuffleForLargeLists = true
        override fun clearOriginalQueue() = Unit
    }
}
