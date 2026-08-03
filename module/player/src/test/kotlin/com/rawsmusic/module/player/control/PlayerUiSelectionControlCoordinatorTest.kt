package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerUiSelectionControlCoordinatorTest {
    @Test
    fun requestedIdentityWinsUntilMatchingCommit() {
        val harness = Harness()
        val requested = AudioFile(path = "b.flac", title = "Requested")

        harness.coordinator.primeSelection(requested)

        assertEquals(requested, harness.coordinator.currentOrRequestedSong())
        assertEquals(requested, harness.persisted)
        assertEquals(1, harness.resetPositionCount)

        harness.coordinator.clearRequestedSongIfMatching(AudioFile(path = "a.flac"))
        assertTrue(harness.coordinator.hasRequestedSong())

        harness.coordinator.clearRequestedSongIfMatching(AudioFile(path = "b.flac"))
        assertFalse(harness.coordinator.hasRequestedSong())
        assertEquals(harness.currentSong, harness.coordinator.currentOrRequestedSong())
    }

    @Test
    fun metadataRefreshUpdatesCommittedItemAndQueueSlot() {
        val harness = Harness()
        val refreshed = AudioFile(path = "a.flac", title = "Refreshed")

        harness.coordinator.updateCurrentSongIfSameIdentity(refreshed)

        assertEquals(refreshed, harness.currentSong)
        assertEquals(refreshed, harness.queue.songs.first())
        assertEquals("b.flac", harness.queue.songs[1].path)
    }

    @Test
    fun releasedControllerRejectsNewUiSelection() {
        val harness = Harness()
        harness.released = true

        harness.coordinator.primeSelection(AudioFile(path = "c.flac"))

        assertFalse(harness.coordinator.hasRequestedSong())
        assertEquals(null, harness.persisted)
        assertEquals(0, harness.resetPositionCount)
    }

    private class Harness {
        var released = false
        var currentSong: AudioFile? = AudioFile(path = "a.flac", title = "Original")
        var queue = PlayQueue(
            songs = listOf(
                AudioFile(path = "a.flac", title = "Original"),
                AudioFile(path = "b.flac"),
            ),
            currentIndex = 0,
        )
        var persisted: AudioFile? = null
        var resetPositionCount = 0

        val coordinator = PlayerUiSelectionControlCoordinator(
            isReleased = { released },
            currentSong = { currentSong },
            setCurrentSong = { currentSong = it },
            currentQueue = { queue },
            updateQueue = { queue = it },
            samePlaybackItem = { left, right ->
                left.path == right.path &&
                    left.cueOffsetMs == right.cueOffsetMs &&
                    left.cueTrackIndex == right.cueTrackIndex
            },
            persistSelection = { persisted = it },
            resetPersistedPosition = { resetPositionCount++ },
        )
    }
}
