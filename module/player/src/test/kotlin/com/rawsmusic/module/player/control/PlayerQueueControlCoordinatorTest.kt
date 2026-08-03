package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerQueueControlCoordinatorTest {
    @Test
    fun sequentialNavigationKeepsRestartAndHistorySemantics() {
        val harness = Harness()

        assertEquals(harness.b, harness.coordinator.next())
        assertEquals(1, harness.queue.currentIndex)
        assertEquals("manual_next:b", harness.switches.last())

        harness.current = harness.b
        harness.coordinator.recordCurrentSongBeforePlay(harness.a, harness.b)
        harness.positionMs = 5_000L
        assertEquals(harness.b, harness.coordinator.previous(restartCurrentAfterThreshold = true))
        assertEquals(0L, harness.positionMs)

        harness.nowMs = 1_000L
        harness.coordinator.armPreviousRestartBypass()
        harness.positionMs = 5_000L
        assertEquals(harness.a, harness.coordinator.previous(restartCurrentAfterThreshold = true))
        assertEquals("manual_previous_history:a", harness.switches.last())
    }

    @Test
    fun priorityQueueAndShuffleStayBehindCallbacks() {
        val harness = Harness()

        harness.coordinator.addToPriorityQueue(harness.c)
        assertEquals(harness.c, harness.coordinator.previewNextSong())
        assertEquals(harness.c, harness.coordinator.next())
        assertEquals("play:c", harness.switches.last())

        harness.queue = PlayQueue(listOf(harness.a, harness.b, harness.c), 1)
        harness.current = harness.b
        harness.playMode = PlayMode.SHUFFLE_ALL
        harness.shuffleEnabled = true
        assertEquals(harness.c, harness.coordinator.previewNextSong())
        assertEquals(harness.a, harness.coordinator.previewPreviousSong())
        assertEquals(harness.c, harness.coordinator.next())

        harness.coordinator.setRepeatMode(RepeatMode.ALL)
        assertEquals(RepeatMode.ALL, harness.repeatModeSet)
    }

    @Test
    fun removingCurrentSongUsesCueIdentityAndStopsOnlyWhenQueueBecomesEmpty() {
        val harness = Harness()
        val cueOne = audio(10, "album.flac", cueOffsetMs = 0L, cueTrackIndex = 1)
        val cueTwo = audio(11, "album.flac", cueOffsetMs = 120_000L, cueTrackIndex = 2)

        harness.queue = PlayQueue(listOf(cueOne, cueTwo), 0)
        harness.current = cueOne
        harness.coordinator.removeSongsFromQueue(listOf(cueTwo))
        assertEquals(listOf(cueOne), harness.queue.songs)
        assertFalse(harness.stopped)

        harness.coordinator.removeSongsFromQueue(listOf(cueOne))
        assertTrue(harness.queue.songs.isEmpty())
        assertEquals(-1, harness.queue.currentIndex)
        assertNull(harness.current)
        assertTrue(harness.requestedSongCleared)
        assertTrue(harness.timelineReset)
        assertTrue(harness.stopped)
    }

    private class Harness {
        val a = audio(1, "a")
        val b = audio(2, "b")
        val c = audio(3, "c")

        var queue = PlayQueue(listOf(a, b, c), 0)
        var current: AudioFile? = a
        var positionMs = 0L
        var nowMs = 100L
        var stopped = false
        var requestedSongCleared = false
        var timelineReset = false
        var playMode = PlayMode.SEQUENTIAL
        var shuffleEnabled = false
        var repeatModeSet: RepeatMode? = null
        val switches = mutableListOf<String>()

        val coordinator = PlayerQueueControlCoordinator(
            mode = PlayerQueueControlCoordinator.ModeCallbacks(
                currentPlayMode = { playMode },
                isShuffleEnabled = { shuffleEnabled },
                nextShuffleIndex = { 2 },
                previousShuffleIndex = { 0 },
                peekNextShuffleIndex = { 2 },
                peekPreviousShuffleIndex = { 0 },
                toggleRepeatMode = {},
                setRepeatMode = { repeatModeSet = it },
                toggleShuffle = { shuffleEnabled = !shuffleEnabled },
                cyclePlayMode = { playMode = PlayMode.REPEAT_ONE },
                setPlayMode = { playMode = it },
                rebuildShuffleForCurrentQueue = {},
            ),
            callbacks = PlayerQueueControlCoordinator.Callbacks(
                isReleased = { false },
                currentQueue = { queue },
                updateQueue = { queue = it },
                currentSong = { current },
                clearCurrentSong = { current = null },
                clearRequestedSong = { requestedSongCleared = true },
                resetTimeline = { positionMs = 0L; timelineReset = true },
                playerPositionMs = { positionMs },
                seekToStart = { positionMs = 0L },
                savePosition = {},
                saveState = {},
                play = { song, songs, index ->
                    current = song
                    queue = PlayQueue(songs, index)
                    switches += "play:${song.path}"
                },
                manualSwitchFromStart = { song, songs, index, reason ->
                    current = song
                    queue = PlayQueue(songs, index)
                    switches += "$reason:${song.path}"
                },
                stop = { stopped = true },
            ),
            uptimeMillis = { nowMs },
        )
    }

    companion object {
        private fun audio(
            id: Long,
            path: String,
            cueOffsetMs: Long = 0L,
            cueTrackIndex: Int = 0,
        ) = AudioFile(
            id = id,
            path = path,
            cueOffsetMs = cueOffsetMs,
            cueTrackIndex = cueTrackIndex,
        )
    }
}
