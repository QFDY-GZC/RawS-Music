package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class PlayerSeekControlCoordinatorTest {
    @Test
    fun cueSeekPublishesDisplayPositionAndRoutesRealPosition() {
        val harness = Harness(paused = false)
        harness.coordinator.seekTo(2_500L)

        assertEquals(2_500L, harness.displayedPosition)
        assertEquals(listOf("a.flac", 12_500L, 2_500L, false), harness.directSeek)
        assertEquals(1, harness.previousBypassCount)
        assertEquals(1, harness.markSeekCount)
    }

    @Test
    fun pausedSeekUsesConflatedEventLaneAndDropsChangedSong() = runBlocking {
        val harness = Harness(paused = true)
        harness.coordinator.seekTo(4_000L, userInitiated = false)
        val queued = assertNotNull(harness.queue.pending)
        queued!!.handler(queued.positionMs, queued.songPath)
        assertEquals(listOf("a.flac", 14_000L, 4_000L), harness.pausedSeek)

        harness.pausedSeek = emptyList()
        harness.coordinator.seekTo(5_000L, userInitiated = false)
        val stale = assertNotNull(harness.queue.pending)
        harness.currentSong = AudioFile(path = "b.flac")
        stale!!.handler(stale.positionMs, stale.songPath)
        assertEquals(emptyList<Any>(), harness.pausedSeek)
    }

    private class Harness(paused: Boolean) {
        val queue = FakeSeekQueue()
        var currentSong: AudioFile? = AudioFile(
            path = "a.flac",
            cueOffsetMs = 10_000L,
            cueTrackIndex = 2,
        )
        var isPaused = paused
        var displayedPosition = -1L
        var previousBypassCount = 0
        var markSeekCount = 0
        var directSeek: List<Any> = emptyList()
        var pausedSeek: List<Any> = emptyList()

        val coordinator = PlayerSeekControlCoordinator(
            eventQueue = queue,
            callbacks = PlayerSeekControlCoordinator.Callbacks(
                isReleased = { false },
                currentSong = { currentSong },
                armPreviousRestartBypass = { previousBypassCount++ },
                isPaused = { isPaused },
                setDisplayedPosition = { displayedPosition = it },
                markSeekPerformed = { markSeekCount++ },
                isSameSongIdentity = { current, expected -> current == expected },
                executePausedSeekRequest = { song, real, display ->
                    pausedSeek = listOf(song.path, real, display)
                },
                executeSeekRequest = { song, real, display, keepPaused ->
                    directSeek = listOf(song.path, real, display, keepPaused)
                },
                logInfo = {},
            ),
        )
    }

    private class FakeSeekQueue : PlayerSeekEventQueue {
        override var isRunning: Boolean = true
        var pending: Pending? = null

        override fun submitSeek(
            positionMs: Long,
            songPath: String,
            handler: suspend (Long, String) -> Unit,
        ) {
            pending = Pending(positionMs, songPath, handler)
        }
    }

    private data class Pending(
        val positionMs: Long,
        val songPath: String,
        val handler: suspend (Long, String) -> Unit,
    )
}
