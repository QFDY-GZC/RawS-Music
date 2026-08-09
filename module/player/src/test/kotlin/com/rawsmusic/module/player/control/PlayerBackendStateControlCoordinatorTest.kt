package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayQueue
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.model.RepeatMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerBackendStateControlCoordinatorTest {
    @Test
    fun playingResetsFailureFuseAndPublishesAudioSession() {
        val harness = Harness().apply { sessionId = 42 }
        repeat(3) {
            harness.coordinator.handleUnavailableSource(harness.songA, listOf(harness.songA), 0)
        }

        harness.coordinator.onStateChanged(PlayerBackendStateControlCoordinator.BackendState.PLAYING)

        assertEquals(PlayState.PLAYING, harness.playState)
        assertEquals("recover_success", harness.transitionReason)
        assertEquals(1, harness.startProgressCount)
        assertEquals(listOf(42), harness.sessions)

        repeat(5) {
            assertFalse(
                harness.coordinator.handleUnavailableSource(
                    harness.songA,
                    listOf(harness.songA),
                    0,
                ) == PlayerBackendStateControlCoordinator.UnavailableSourceResult.STOPPED_FAILURE_FUSE
            )
        }
    }

    @Test
    fun ordinaryDecoderErrorAdvancesButOutputErrorDoesNot() {
        val decoderHarness = Harness().apply {
            lastError = "decoder read failed"
            queue = PlayQueue(listOf(songA, songB), 0)
        }
        decoderHarness.coordinator.onStateChanged(
            PlayerBackendStateControlCoordinator.BackendState.ERROR
        )
        assertEquals(1, decoderHarness.playCount)
        assertEquals(1, decoderHarness.playedIndex)

        val usbHarness = Harness().apply {
            lastError = "USB device claim failed"
            queue = PlayQueue(listOf(songA, songB), 0)
        }
        usbHarness.coordinator.onStateChanged(
            PlayerBackendStateControlCoordinator.BackendState.ERROR
        )
        assertEquals(0, usbHarness.playCount)
        assertTrue(usbHarness.warnings.any { it.contains("Not auto-advancing") })
    }

    @Test
    fun sixthUnavailableSourceOpensFailureFuseAndStops() {
        val harness = Harness()
        repeat(5) {
            assertEquals(
                PlayerBackendStateControlCoordinator.UnavailableSourceResult.CLEARED_SINGLE_SOURCE,
                harness.coordinator.handleUnavailableSource(harness.songA, listOf(harness.songA), 0),
            )
        }

        assertEquals(
            PlayerBackendStateControlCoordinator.UnavailableSourceResult.STOPPED_FAILURE_FUSE,
            harness.coordinator.handleUnavailableSource(harness.songA, listOf(harness.songA), 0),
        )
        assertEquals(1, harness.stopCount)
    }

    @Test
    fun completionHonoursSleepRepeatAndEndOfQueue() {
        val sleepHarness = Harness().apply { consumeCompletion = true }
        sleepHarness.coordinator.onStateChanged(
            PlayerBackendStateControlCoordinator.BackendState.COMPLETED
        )
        assertEquals(1, sleepHarness.pauseCount)
        assertEquals(0, sleepHarness.nextCount)

        val repeatOneHarness = Harness().apply { repeatMode = RepeatMode.ONE }
        repeatOneHarness.coordinator.onStateChanged(
            PlayerBackendStateControlCoordinator.BackendState.COMPLETED
        )
        assertEquals(1, repeatOneHarness.playCount)
        assertEquals(repeatOneHarness.songA.path, repeatOneHarness.playedSong?.path)

        val middleHarness = Harness().apply {
            repeatMode = RepeatMode.OFF
            queue = PlayQueue(listOf(songA, songB), 0)
        }
        middleHarness.coordinator.onStateChanged(
            PlayerBackendStateControlCoordinator.BackendState.COMPLETED
        )
        assertEquals(1, middleHarness.nextCount)

        val endHarness = Harness().apply {
            repeatMode = RepeatMode.OFF
            queue = PlayQueue(listOf(songA, songB), 1)
        }
        endHarness.coordinator.onStateChanged(
            PlayerBackendStateControlCoordinator.BackendState.COMPLETED
        )
        assertEquals(0, endHarness.nextCount)
    }

    private class Harness {
        val songA = AudioFile(path = "a.flac", title = "A")
        val songB = AudioFile(path = "b.flac", title = "B")

        var released = false
        var playState = PlayState.IDLE
        var transitionReason: String? = null
        var startProgressCount = 0
        var stopProgressCount = 0
        var sessionId = 0
        val sessions = mutableListOf<Int>()
        var lastError: String? = null
        var usbExclusive = false
        var backendUsbExclusive = false
        var queue = PlayQueue(listOf(songA), 0)
        var currentSong: AudioFile? = songA
        var repeatMode = RepeatMode.OFF
        var consumeCompletion = false
        var playCount = 0
        var playedSong: AudioFile? = null
        var playedIndex = -1
        var pauseCount = 0
        var nextCount = 0
        var stopCount = 0
        var clearedCount = 0
        val warnings = mutableListOf<String>()

        val coordinator = PlayerBackendStateControlCoordinator(
            callbacks = PlayerBackendStateControlCoordinator.Callbacks(
                isReleased = { released },
                forcePlayState = { state, reason ->
                    playState = state
                    transitionReason = reason
                },
                startProgressUpdate = { startProgressCount++ },
                stopProgressUpdate = { stopProgressCount++ },
                audioSessionId = { sessionId },
                onAudioSessionReady = { session -> sessions.add(session) },
                lastPlayerError = { lastError },
                isUsbExclusiveActive = { usbExclusive },
                backendUsbExclusiveMode = { backendUsbExclusive },
                currentQueue = { queue },
                currentSong = { currentSong },
                currentRepeatMode = { repeatMode },
                consumePlaybackCompletion = { consumeCompletion },
                playTransport = { song, _, index ->
                    playCount++
                    playedSong = song
                    playedIndex = index
                },
                replayCurrentSong = { song ->
                    playCount++
                    playedSong = song
                    playedIndex = queue.currentIndex
                },
                pauseTransport = { pauseCount++ },
                nextTransport = { nextCount++ },
                stopTransport = { stopCount++ },
                clearUnavailableSong = { clearedCount++ },
                logDebug = {},
                logWarning = { message -> warnings.add(message) },
            ),
        )
    }
}
