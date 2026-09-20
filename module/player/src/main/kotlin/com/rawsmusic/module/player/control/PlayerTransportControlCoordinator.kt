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

    fun submitGeneric(eventType: String, handler: suspend () -> Unit)
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

    override fun submitGeneric(eventType: String, handler: suspend () -> Unit) {
        delegate.submit(PE.GenericEvent(eventType, handler))
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
        val onExplicitQueueSelection: (List<AudioFile>, Int) -> Unit = { _, _ -> },
        val primeSongSelectionForUi: (AudioFile) -> Unit,
        val shouldRouteExplicitPlayThroughManualSwitch: (AudioFile) -> Boolean,
        val playManualSwitchFromStartLocked: suspend (AudioFile, List<AudioFile>, Int, String, () -> Boolean) -> Unit,
        val playInternal: (AudioFile, List<AudioFile>, Int) -> Unit,
        val backendState: () -> BackendState,
        val backendStateAgeMs: () -> Long,
        val backendStateSummary: () -> String,
        val resolvePlayPauseSeedSong: () -> AudioFile?,
        val hasPausedSelectionPendingStart: () -> Boolean = { false },
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
                callbacks.onExplicitQueueSelection(resolvedQueue, resolvedIndex)
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
                    ) { isLatestPlayRequest(token) }
                } else {
                    callbacks.playInternal(queuedSong, resolvedQueue, resolvedIndex)
                }
            }
        }
    }

    /**
     * Renderer-completion continuation lane.
     *
     * Unlike [play], this is not an explicit user selection: it must not prime selection UI,
     * clear focus-resume intent, or enter the manual fade/crossfade policy. The request still uses
     * the same serialized PLAY queue and latest-request token, so a newer user command safely wins.
     */
    fun automaticAdvance(song: AudioFile, queue: List<AudioFile>, index: Int): Boolean {
        if (callbacks.isReleased() || index !in queue.indices || queue[index] != song) return false
        val token = latestPlayRequestToken.incrementAndGet()
        eventQueue.submitPlay(song, queue, index) autoAdvanceHandler@{ queuedSong, queuedQueue, queuedIndex ->
            if (!isLatestPlayRequest(token)) return@autoAdvanceHandler
            transportMutex.withLock {
                if (!isLatestPlayRequest(token)) return@withLock
                callbacks.playInternal(queuedSong, queuedQueue, queuedIndex)
            }
        }
        return true
    }

    /**
     * Internal renderer restart used after an output-policy rebuild.
     *
     * This is deliberately not [play]: a settings transaction must never create a newer user-play
     * token or prime selection UI with the song that happened to be current when the transaction
     * started. If the user selected another row while USB was rebuilding, that explicit request
     * owns a newer token and this continuation simply disappears.
     */
    fun restartAfterSettingsIfUncontested(
        song: AudioFile,
        queue: List<AudioFile>,
        index: Int,
        expectedPlayRequestToken: Long,
    ): Boolean {
        if (callbacks.isReleased() || index !in queue.indices || queue[index] != song) return false
        if (latestPlayRequestToken.get() != expectedPlayRequestToken) return false
        eventQueue.submitGeneric("SETTINGS_RESTART") restartHandler@{
            if (latestPlayRequestToken.get() != expectedPlayRequestToken) {
                callbacks.logWarn(
                    "settings restart dropped: explicit play superseded target=${song.title} " +
                        "expectedToken=$expectedPlayRequestToken latest=${latestPlayRequestToken.get()}"
                )
                return@restartHandler
            }
            transportMutex.withLock {
                if (latestPlayRequestToken.get() != expectedPlayRequestToken) return@withLock
                callbacks.playInternal(song, queue, index)
            }
        }
        return true
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
            BackendState.PAUSED -> {
                if (callbacks.hasPausedSelectionPendingStart()) {
                    startFromSeedOrIdle()
                } else {
                    resume()
                }
            }
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
        if (callbacks.hasPausedSelectionPendingStart()) {
            callbacks.logWarn("resume(): selected track differs from paused renderer; starting selection")
            startFromSeedOrIdle()
            return
        }
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
