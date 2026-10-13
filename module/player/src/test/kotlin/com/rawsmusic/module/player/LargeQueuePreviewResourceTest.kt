package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import org.junit.Assert.assertTrue
import org.junit.Test

/** Read-only audit of the production shuffle preview, without starting playback or writing prefs. */
class LargeQueuePreviewResourceTest {
    private class CountingSongs(private val count: Int) : AbstractList<AudioFile>() {
        var reads = 0
        private val data = List(count) {
            AudioFile(id = it.toLong(), path = "/storage/emulated/0/Music/Album-$it/Track-$it.flac")
        }
        override val size get() = count
        override fun get(index: Int): AudioFile {
            reads++
            return data[index]
        }
    }

    private class Preferences(count: Int) : ShuffleQueuePreferences {
        override var order = (0 until count).joinToString(",")
        override var cursor = 0
        override val playMode = PlayMode.SHUFFLE_ALL
        override val randomFactor = 1f
        override val categoryMode = 0
        override val noReshuffle = true
        override val noReshuffleForLargeLists = true
        override fun clearOriginalQueue() = Unit
    }

    @Test fun measureSteadyPreviewQueueReads() {
        for (count in listOf(100, 1000, 4000, 10000)) {
            val songs = CountingSongs(count)
            val controller = ShuffleQueueController(Preferences(count))
            val queue = PlayQueue(songs, 0)
            controller.peekNextIndex(queue) // Warm traversal before measuring.
            songs.reads = 0
            repeat(100) {
                controller.peekPreviousIndex(queue)
                controller.peekNextIndex(queue)
            }
            println("QUEUE_COST songs=$count previews=200 songReads=${songs.reads} readsPerPreview=${songs.reads / 200}")
            assertTrue("Unchanged queue previews must not enumerate songs: ${songs.reads}", songs.reads == 0)
        }
    }
}
