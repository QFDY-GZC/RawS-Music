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
    fun successiveNextKeepsRemainingPriorityItemsInTheProjectedQueue() {
        val harness = Harness()

        harness.coordinator.addToPriorityQueue(audio(4, "priority-1"))
        harness.coordinator.addToPriorityQueue(audio(5, "priority-2"))

        assertEquals("priority-1", harness.coordinator.previewNextSong()?.path)
        assertEquals("priority-1", harness.coordinator.next()?.path)
        assertEquals("priority-2", harness.coordinator.previewNextSong()?.path)
        assertEquals("priority-2", harness.coordinator.next()?.path)
        assertEquals("b", harness.coordinator.previewNextSong()?.path)
    }


    @Test
    fun rapidArtworkGesturesAdvancePrivateCursorWithoutPublishingPublicQueue() {
        val harness = Harness()

        assertEquals(harness.b, harness.coordinator.nextFromArtworkGesture())
        assertEquals(0, harness.queue.currentIndex)
        assertEquals("artwork_gesture_next:b", harness.switches.last())

        // Authoritative renderer state is deliberately still A. The second direct gesture must
        // nevertheless resolve from the private B projection and target C without writing queue UI.
        assertEquals(harness.c, harness.coordinator.nextFromArtworkGesture())
        assertEquals(0, harness.queue.currentIndex)
        assertEquals(
            listOf("artwork_gesture_next:b", "artwork_gesture_next:c"),
            harness.switches.takeLast(2),
        )
        assertEquals(0, harness.updateQueueCalls)
        assertEquals(0, harness.visibleQueueOverrideCalls)
        assertEquals(0, harness.savePositionCalls)
    }

    @Test
    fun rapidArtworkPreviousAndReverseUseTheSamePrivateCursor() {
        val harness = Harness()
        harness.queue = PlayQueue(listOf(harness.a, harness.b, harness.c), 2)
        harness.current = harness.c

        assertEquals(harness.b, harness.coordinator.previousFromArtworkGesture())
        assertEquals(harness.a, harness.coordinator.previousFromArtworkGesture())
        assertEquals(2, harness.queue.currentIndex)

        // Reversing direction before renderer acknowledgement must resolve from projected A,
        // therefore Next returns B rather than jumping from authoritative C.
        assertEquals(harness.b, harness.coordinator.nextFromArtworkGesture())
        assertEquals(2, harness.queue.currentIndex)
        assertEquals(0, harness.updateQueueCalls)
        assertEquals(0, harness.visibleQueueOverrideCalls)
    }

    @Test
    fun artworkGestureProjectionCollapsesWhenAuthoritativeCursorCatchesUp() {
        val harness = Harness()

        assertEquals(harness.b, harness.coordinator.nextFromArtworkGesture())
        // Simulate renderer TRACK_STARTED committing B. The next gesture must naturally continue
        // from public B after the private projection collapses.
        harness.queue = harness.queue.copy(currentIndex = 1)
        harness.current = harness.b
        assertEquals(harness.c, harness.coordinator.nextFromArtworkGesture())
        assertEquals("artwork_gesture_next:c", harness.switches.last())
    }

    @Test
    fun automaticAdvanceUsesNaturalLaneWithoutManualTransition() {
        val harness = Harness()

        assertEquals(harness.b, harness.coordinator.automaticAdvance())
        assertEquals(1, harness.queue.currentIndex)
        assertEquals("automatic:b", harness.switches.last())
        assertFalse(harness.switches.any { it.startsWith("manual_next:") })
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
        var savePositionCalls = 0
        var updateQueueCalls = 0
        var visibleQueueOverrideCalls = 0
        val switches = mutableListOf<String>()

        val coordinator = PlayerQueueControlCoordinator(
            mode = PlayerQueueControlCoordinator.ModeCallbacks(
                currentPlayMode = { playMode },
                isShuffleEnabled = { shuffleEnabled },
                nextShuffleIndex = { 2 },
                previousShuffleIndex = { 0 },
                peekNextShuffleIndex = { 2 },
                peekPreviousShuffleIndex = { 0 },
                peekRelativeShuffleIndex = { queue, offset ->
                    (queue.currentIndex + offset).mod(queue.songs.size)
                },
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
                updateQueue = { updateQueueCalls++; queue = it },
                setVisibleQueueOverride = { visibleQueueOverrideCalls++ },
                currentSong = { current },
                clearCurrentSong = { current = null },
                clearRequestedSong = { requestedSongCleared = true },
                resetTimeline = { positionMs = 0L; timelineReset = true },
                playerPositionMs = { positionMs },
                seekToStart = { positionMs = 0L },
                savePosition = { savePositionCalls++ },
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
                manualArtworkGestureSwitchFromStart = { song, songs, index, reason ->
                    switches += "$reason:${song.path}"
                },
                automaticSwitchFromStart = { song, songs, index ->
                    current = song
                    queue = PlayQueue(songs, index)
                    switches += "automatic:${song.path}"
                    true
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
