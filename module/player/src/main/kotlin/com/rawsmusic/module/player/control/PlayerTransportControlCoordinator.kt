package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.statemachine.PlaybackEventQueue
import com.rawsmusic.module.player.statemachine.PlaybackEventQueue.PlaybackEvent as PE
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal interface PlayerTransportEventQueue {
    fun submitPlay(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
        handler: suspend (AudioFile, List<AudioFile>, Int) -> Unit,
    )

    fun submitPause(handler: suspend () -> Unit)
    fun submitResume(handler: suspend () -> Unit)
    fun submitStop(handler: suspend () -> Unit)
}

internal class PlaybackEventQueueTransportAdapter(
    private val delegate: PlaybackEventQueue,
) : PlayerTransportEventQueue {
    override fun submitPlay(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
        handler: suspend (AudioFile, List<AudioFile>, Int) -> Unit,
    ) {
        delegate.submit(PE.PlayEvent(song, queue, index, handler))
    }

    override fun submitPause(handler: suspend () -> Unit) {
        delegate.submit(PE.PauseEvent(handler))
    }

    override fun submitResume(handler: suspend () -> Unit) {
        delegate.submit(PE.ResumeEvent(handler))
    }

    override fun submitStop(handler: suspend () -> Unit) {
        delegate.submit(PE.StopEvent(handler))
    }
}

/**
 * Owns the public transport command lane without owning any audio backend.
 *
 * PlayerController still owns decoder, Android-output and USB-exclusive implementation details.
 * This coordinator only decides how play/pause/resume/stop commands are serialized, conflated and
 * routed. Keeping backend work behind callbacks prevents this boundary from growing into another
 * all-knowing controller while making the command policy independently testable.
 */
internal class PlayerTransportControlCoordinator(
    private val eventQueue: PlayerTransportEventQueue,
    private val transportMutex: Mutex,
    private val latestPlayRequestToken: AtomicLong,
    private val callbacks: Callbacks,
) {
    internal enum class BackendState {
        IDLE,
        PREPARING,
        PLAYING,
        PAUSED,
        STOPPED,
        ERROR,
        COMPLETED,
    }

    internal data class Callbacks(
        val isReleased: () -> Boolean,
        val clearAutomaticFocusResume: (String) -> Unit,
        val resolveExplicitPlayQueue: (AudioFile, List<AudioFile>, Int) -> Pair<List<AudioFile>, Int>,
        val primeSongSelectionForUi: (AudioFile) -> Unit,
        val shouldRouteExplicitPlayThroughManualSwitch: (AudioFile) -> Boolean,
        val playManualSwitchFromStartLocked: suspend (AudioFile, List<AudioFile>, Int, String) -> Unit,
        val playInternal: (AudioFile, List<AudioFile>, Int) -> Unit,
        val backendState: () -> BackendState,
        val backendStateAgeMs: () -> Long,
        val backendStateSummary: () -> String,
        val resolvePlayPauseSeedSong: () -> AudioFile?,
        val transitionPlayState: (PlayState, String) -> Unit,
        val forcePlayState: (PlayState, String) -> Unit,
        val isUsbExclusiveActive: () -> Boolean,
        val controllerPlayState: () -> PlayState,
        val pauseUsbWarmInternal: suspend () -> Unit,
        val pauseSystemImmediateUi: () -> Unit,
        val pauseSystemBackendInternal: suspend () -> Unit,
        val markAppForegroundForResume: () -> Unit,
        val resumeInternal: suspend () -> Unit,
        val stopInternal: suspend () -> Unit,
        val logWarn: (String) -> Unit,
    )

    fun play(song: AudioFile, queue: List<AudioFile> = emptyList(), index: Int = 0) {
        if (callbacks.isReleased()) return
        callbacks.clearAutomaticFocusResume("explicit_play")
        val (requestedQueue, requestedIndex) = callbacks.resolveExplicitPlayQueue(song, queue, index)
        callbacks.primeSongSelectionForUi(song)
        val token = latestPlayRequestToken.incrementAndGet()

        eventQueue.submitPlay(
            song = song,
            queue = requestedQueue,
            index = requestedIndex,
        ) playHandler@{ queuedSong, queuedQueue, queuedIndex ->
            if (!isLatestPlayRequest(token)) {
                callbacks.logWarn(
                    "play() skipped stale queued request: title=${queuedSong.title} " +
                        "token=$token latest=${latestPlayRequestToken.get()}"
                )
                return@playHandler
            }

            transportMutex.withLock {
                if (!isLatestPlayRequest(token)) {
                    callbacks.logWarn(
                        "play() skipped stale request after mutex: title=${queuedSong.title} " +
                            "token=$token latest=${latestPlayRequestToken.get()}"
                    )
                    return@withLock
                }

                val (resolvedQueue, resolvedIndex) = callbacks.resolveExplicitPlayQueue(
                    queuedSong,
                    queuedQueue,
                    queuedIndex,
                )
                if (callbacks.shouldRouteExplicitPlayThroughManualSwitch(queuedSong)) {
                    callbacks.logWarn(
                        "play(): routing explicit song selection through manual switch " +
                            "title=${queuedSong.title} index=$resolvedIndex " +
                            "queueSize=${resolvedQueue.size}"
                    )
                    callbacks.playManualSwitchFromStartLocked(
                        queuedSong,
                        resolvedQueue,
                        resolvedIndex,
                        "manual_select",
                    )
                } else {
                    callbacks.playInternal(queuedSong, resolvedQueue, resolvedIndex)
                }
            }
        }
    }

    fun playQueue(songs: List<AudioFile>, startIndex: Int = 0) {
        if (songs.isEmpty() || callbacks.isReleased()) return
        val safeIndex = startIndex.coerceIn(0, songs.lastIndex)
        play(songs[safeIndex], songs, safeIndex)
    }

    fun playPause() {
        if (callbacks.isReleased()) return
        val state = callbacks.backendState()
        callbacks.logWarn("playPause called, state=$state")
        when (state) {
            BackendState.PLAYING -> pause()
            BackendState.PAUSED -> resume()
            BackendState.PREPARING -> handlePreparingPlayPause()
            else -> startFromSeedOrIdle()
        }
    }

    fun pause() {
        if (callbacks.isReleased()) return
        callbacks.clearAutomaticFocusResume("explicit_pause")
        callbacks.logWarn(
            "pause() called, state=${callbacks.backendStateSummary()} " +
                "usbExclusive=${callbacks.isUsbExclusiveActive()}"
        )

        if (callbacks.isUsbExclusiveActive() && callbacks.controllerPlayState() == PlayState.PLAYING) {
            eventQueue.submitPause { callbacks.pauseUsbWarmInternal() }
            return
        }

        callbacks.pauseSystemImmediateUi()
        eventQueue.submitPause { callbacks.pauseSystemBackendInternal() }
    }

    fun resume() {
        if (callbacks.isReleased()) return
        callbacks.clearAutomaticFocusResume("explicit_resume")
        callbacks.markAppForegroundForResume()
        callbacks.logWarn("resume() called, ${callbacks.backendStateSummary()}")
        eventQueue.submitResume { callbacks.resumeInternal() }
    }

    fun stop() {
        if (callbacks.isReleased()) return
        eventQueue.submitStop { callbacks.stopInternal() }
    }

    private fun handlePreparingPlayPause() {
        val preparingAgeMs = callbacks.backendStateAgeMs()
        if (preparingAgeMs < STALE_PREPARING_MS) {
            callbacks.logWarn(
                "playPause: already PREPARING (${preparingAgeMs}ms), ignoring duplicate tap"
            )
            callbacks.transitionPlayState(PlayState.PREPARING, "play_pause_preparing")
            return
        }

        val seedSong = callbacks.resolvePlayPauseSeedSong()
        callbacks.logWarn(
            "playPause: stale PREPARING (${preparingAgeMs}ms), " +
                "forcing restart song=${seedSong?.path}"
        )
        if (seedSong != null) {
            callbacks.forcePlayState(PlayState.PREPARING, "play_pause_stale_preparing_retry")
            play(seedSong)
        } else {
            callbacks.forcePlayState(PlayState.ERROR, "play_pause_stale_preparing_no_seed")
        }
    }

    private fun startFromSeedOrIdle() {
        callbacks.logWarn("playPause: state=${callbacks.backendState()}, falling back to play()")
        val seedSong = callbacks.resolvePlayPauseSeedSong()
        if (seedSong != null) {
            callbacks.transitionPlayState(PlayState.PREPARING, "play_pause_start")
            play(seedSong)
        } else {
            callbacks.logWarn("playPause: no song available for cold-start capsule tap")
            callbacks.transitionPlayState(PlayState.IDLE, "play_pause_no_seed")
        }
    }

    private fun isLatestPlayRequest(token: Long): Boolean =
        token == latestPlayRequestToken.get()

    private companion object {
        const val STALE_PREPARING_MS = 3_500L
    }
}
