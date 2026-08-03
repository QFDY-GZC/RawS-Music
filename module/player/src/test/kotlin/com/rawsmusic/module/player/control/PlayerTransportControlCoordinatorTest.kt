package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerTransportControlCoordinatorTest {
    @Test
    fun playRoutesNormalAndManualSelectionsThroughOneSerializedLane() {
        val harness = Harness()

        harness.coordinator.play(harness.normal)
        assertEquals(listOf("play:normal"), harness.backendCalls)

        harness.coordinator.play(harness.manual)
        assertEquals(listOf("play:normal", "manual:manual_select:manual"), harness.backendCalls)
        assertEquals(listOf("normal", "manual"), harness.primedTitles)
    }

    @Test
    fun pauseKeepsUsbWarmButPublishesSystemPauseImmediately() {
        val harness = Harness()
        harness.backendState = PlayerTransportControlCoordinator.BackendState.PLAYING
        harness.controllerState = PlayState.PLAYING

        harness.usbExclusive = false
        harness.coordinator.pause()
        assertEquals(1, harness.systemImmediatePauseCount)
        assertEquals(1, harness.systemBackendPauseCount)
        assertEquals(0, harness.usbWarmPauseCount)

        harness.controllerState = PlayState.PLAYING
        harness.usbExclusive = true
        harness.coordinator.pause()
        assertEquals(1, harness.usbWarmPauseCount)
        assertEquals(1, harness.systemBackendPauseCount)
    }

    @Test
    fun playPauseHandlesPreparingResumeAndColdSeedWithoutBackendKnowledge() {
        val harness = Harness()

        harness.backendState = PlayerTransportControlCoordinator.BackendState.PREPARING
        harness.preparingAgeMs = 100L
        harness.coordinator.playPause()
        assertEquals(PlayState.PREPARING, harness.controllerState)
        assertTrue(harness.backendCalls.isEmpty())

        harness.backendState = PlayerTransportControlCoordinator.BackendState.PAUSED
        harness.coordinator.playPause()
        assertEquals(1, harness.resumeCount)

        harness.backendState = PlayerTransportControlCoordinator.BackendState.IDLE
        harness.coordinator.playPause()
        assertEquals("play:seed", harness.backendCalls.last())
    }

    @Test
    fun stopUsesSerializedEventSink() {
        val harness = Harness()
        harness.coordinator.stop()
        assertEquals(1, harness.stopCount)
    }

    private class Harness {
        val normal = AudioFile(path = "normal", title = "normal")
        val manual = AudioFile(path = "manual", title = "manual")
        var backendState = PlayerTransportControlCoordinator.BackendState.IDLE
        var preparingAgeMs = 0L
        var controllerState = PlayState.IDLE
        var usbExclusive = false
        var usbWarmPauseCount = 0
        var systemImmediatePauseCount = 0
        var systemBackendPauseCount = 0
        var resumeCount = 0
        var stopCount = 0
        val primedTitles = mutableListOf<String>()
        val backendCalls = mutableListOf<String>()

        private val eventSink = object : PlayerTransportEventQueue {
            override fun submitPlay(
                song: AudioFile,
                queue: List<AudioFile>,
                index: Int,
                handler: suspend (AudioFile, List<AudioFile>, Int) -> Unit,
            ) {
                runBlocking { handler(song, queue, index) }
            }

            override fun submitPause(handler: suspend () -> Unit) {
                runBlocking { handler() }
            }

            override fun submitResume(handler: suspend () -> Unit) {
                runBlocking { handler() }
            }

            override fun submitStop(handler: suspend () -> Unit) {
                runBlocking { handler() }
            }
        }

        val coordinator = PlayerTransportControlCoordinator(
            eventQueue = eventSink,
            transportMutex = Mutex(),
            latestPlayRequestToken = AtomicLong(0L),
            callbacks = PlayerTransportControlCoordinator.Callbacks(
                isReleased = { false },
                clearAutomaticFocusResume = {},
                resolveExplicitPlayQueue = { song, songs, index ->
                    val resolved = songs.ifEmpty { listOf(song) }
                    resolved to index.coerceIn(0, resolved.lastIndex)
                },
                primeSongSelectionForUi = { primedTitles += it.title },
                shouldRouteExplicitPlayThroughManualSwitch = { it.path == "manual" },
                playManualSwitchFromStartLocked = { song, _, _, reason ->
                    backendCalls += "manual:$reason:${song.path}"
                },
                playInternal = { song, _, _ -> backendCalls += "play:${song.path}" },
                backendState = { backendState },
                backendStateAgeMs = { preparingAgeMs },
                backendStateSummary = { backendState.name },
                resolvePlayPauseSeedSong = { AudioFile(path = "seed", title = "seed") },
                transitionPlayState = { state, _ -> controllerState = state },
                forcePlayState = { state, _ -> controllerState = state },
                isUsbExclusiveActive = { usbExclusive },
                controllerPlayState = { controllerState },
                pauseUsbWarmInternal = { usbWarmPauseCount++ },
                pauseSystemImmediateUi = {
                    systemImmediatePauseCount++
                    controllerState = PlayState.PAUSED
                },
                pauseSystemBackendInternal = { systemBackendPauseCount++ },
                markAppForegroundForResume = {},
                resumeInternal = { resumeCount++ },
                stopInternal = { stopCount++ },
                logWarn = {},
            ),
        )
    }
}
