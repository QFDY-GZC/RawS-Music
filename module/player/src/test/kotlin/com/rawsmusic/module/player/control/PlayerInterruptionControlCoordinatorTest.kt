package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerInterruptionControlCoordinatorTest {
    @Test
    fun activeAndroidPlaybackPausesAndPersistsOnce() {
        val harness = Harness().apply {
            backendState = PlayerInterruptionControlCoordinator.BackendState.PLAYING
        }

        assertTrue(harness.coordinator.pauseForInterruption("transient"))
        assertEquals(1, harness.pauseCount)
        assertEquals("audio_focus_transient", harness.transitionReason)
        assertEquals(1, harness.stopProgressCount)
        assertEquals(1, harness.savePositionCount)
        assertEquals(1, harness.saveStateCount)
    }

    @Test
    fun usbExclusivePlaybackIsNotPausedByAndroidFocusPolicy() {
        val harness = Harness().apply {
            usbExclusive = true
            backendState = PlayerInterruptionControlCoordinator.BackendState.PLAYING
        }

        assertFalse(harness.coordinator.pauseForInterruption("loss"))
        assertEquals(0, harness.pauseCount)
    }

    @Test
    fun pausedBackendResumesExistingTransport() {
        val harness = Harness().apply {
            backendState = PlayerInterruptionControlCoordinator.BackendState.PAUSED
        }

        assertTrue(harness.coordinator.resumeOrStartRememberedSong("gain"))
        assertEquals(1, harness.resumeCount)
        assertEquals(0, harness.playCount)
    }

    @Test
    fun stoppedBackendStartsRememberedQueueSlot() {
        val harness = Harness().apply {
            currentSong = AudioFile(path = "b.flac", title = "B")
            queue = PlayQueue(
                songs = listOf(AudioFile(path = "a.flac"), currentSong!!),
                currentIndex = 0,
            )
            backendState = PlayerInterruptionControlCoordinator.BackendState.STOPPED
        }

        assertTrue(harness.coordinator.resumeOrStartRememberedSong("resume_on_resume"))
        assertEquals(1, harness.playCount)
        assertEquals(1, harness.playedIndex)
        assertEquals("b.flac", harness.playedSong?.path)
    }

    @Test
    fun releasedOrAlreadyActiveControllerRejectsAutomaticResume() {
        val releasedHarness = Harness().apply { released = true }
        assertFalse(releasedHarness.coordinator.resumeOrStartRememberedSong("gain"))

        val activeHarness = Harness().apply {
            backendState = PlayerInterruptionControlCoordinator.BackendState.PREPARING
        }
        assertFalse(activeHarness.coordinator.resumeOrStartRememberedSong("gain"))
        assertEquals(0, activeHarness.playCount)
    }

    private class Harness {
        var released = false
        var usbExclusive = false
        var backendPlayingNow = false
        var backendState = PlayerInterruptionControlCoordinator.BackendState.IDLE
        var playState = PlayState.IDLE
        var currentSong: AudioFile? = AudioFile(path = "a.flac", title = "A")
        var restoredSong: AudioFile? = null
        var queue = PlayQueue(songs = listOf(currentSong!!), currentIndex = 0)

        var pauseCount = 0
        var transitionReason: String? = null
        var stopProgressCount = 0
        var savePositionCount = 0
        var saveStateCount = 0
        var resumeCount = 0
        var playCount = 0
        var playedSong: AudioFile? = null
        var playedIndex = -1

        val coordinator = PlayerInterruptionControlCoordinator(
            callbacks = PlayerInterruptionControlCoordinator.Callbacks(
                isReleased = { released },
                isUsbExclusiveActive = { usbExclusive },
                backendIsPlayingNow = { backendPlayingNow },
                backendState = { backendState },
                controllerPlayState = { playState },
                pauseBackend = { pauseCount++ },
                transitionToPaused = { transitionReason = it },
                stopProgressUpdate = { stopProgressCount++ },
                savePosition = { savePositionCount++ },
                saveState = { saveStateCount++ },
                currentSong = { currentSong },
                restoreLastSong = { restoredSong },
                currentQueue = { queue },
                samePlaybackItem = { left, right ->
                    left.path == right.path &&
                        left.cueOffsetMs == right.cueOffsetMs &&
                        left.cueTrackIndex == right.cueTrackIndex
                },
                resumeTransport = { resumeCount++ },
                playTransport = { song, _, index ->
                    playCount++
                    playedSong = song
                    playedIndex = index
                },
                logInfo = {},
            ),
        )
    }
}
