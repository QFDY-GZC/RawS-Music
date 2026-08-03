package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.common.utils.OnlinePlaybackDiagnostics
import android.os.SystemClock
import com.rawsmusic.module.data.source.playback.MUSIC_SOURCE_ONLINE_ENCODING_MARKER
import com.rawsmusic.module.data.source.playback.MusicSourceBackendSnapshot
import com.rawsmusic.module.data.source.playback.MusicSourceBackendStatus
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackBackend
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackRequest
import com.rawsmusic.module.data.source.playback.MusicSourceResolvedStreamRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** PlayerController-backed online source output. */
internal class PlayerControllerMusicSourcePlaybackBackend(
    private val controller: PlayerController,
) : MusicSourcePlaybackBackend {
    private val mutableSnapshot = MutableStateFlow(MusicSourceBackendSnapshot())
    override val snapshot: StateFlow<MusicSourceBackendSnapshot> = mutableSnapshot.asStateFlow()

    private var activeRequest: MusicSourcePlaybackRequest? = null
    private var pendingSeekMs = 0L
    private var pendingSeekApplied = false
    private var pauseAfterStart = false
    private var pauseAfterStartIssued = false
    private var hasOwnedRendererSession = false
    private var completionConsumePath: String? = null
    private var activeStartedAtMs = 0L

    override fun play(request: MusicSourcePlaybackRequest) {
        activeRequest?.let { previous ->
            MusicSourceResolvedStreamRegistry.remove(previous.source.url, previous.generation)
        }
        activeRequest = request
        activeStartedAtMs = SystemClock.elapsedRealtime()
        pendingSeekMs = request.startPositionMs.coerceAtLeast(0L)
        pendingSeekApplied = pendingSeekMs <= 0L
        pauseAfterStart = !request.autoPlay
        pauseAfterStartIssued = false
        hasOwnedRendererSession = false
        MusicSourceResolvedStreamRegistry.register(request)

        mutableSnapshot.value = MusicSourceBackendSnapshot(
            generation = request.generation,
            sourceUrl = request.source.url,
            status = MusicSourceBackendStatus.Preparing,
            positionMs = pendingSeekMs,
            durationMs = request.item.durationMs.coerceAtLeast(0L),
        )

        val song = request.toAudioFile()
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} PLAY_START generation=${request.generation} " +
                "quality=${request.source.quality} headers=${OnlinePlaybackDiagnostics.headerNames(request.source.headers)} " +
                "ua=${!request.source.userAgent.isNullOrBlank()} autoPlay=${request.autoPlay} " +
                "startMs=${request.startPositionMs} url=${OnlinePlaybackDiagnostics.safeUrl(request.source.url)}"
        )
        controller.play(song, listOf(song), 0)
    }

    override fun pause() {
        if (!ownsCurrentSong()) return
        controller.pause()
    }

    override fun resume() {
        if (!ownsCurrentSong()) return
        controller.resume()
    }

    override fun seekTo(positionMs: Long) {
        if (!ownsCurrentSong()) return
        val target = positionMs.coerceAtLeast(0L)
        pendingSeekMs = target
        pendingSeekApplied = true
        controller.seekTo(target)
        mutableSnapshot.value = mutableSnapshot.value.copy(positionMs = target)
    }

    override fun stop() {
        val request = activeRequest
        request?.let {
            AppLogger.i(
                TAG,
                "${OnlinePlaybackDiagnostics.PREFIX} PLAY_STOP generation=${it.generation} " +
                    "owned=${ownsCurrentSong()} elapsedMs=${SystemClock.elapsedRealtime() - activeStartedAtMs}"
            )
        }
        val ownsCurrent = ownsCurrentSong()
        activeRequest = null
        pendingSeekMs = 0L
        pendingSeekApplied = false
        pauseAfterStart = false
        pauseAfterStartIssued = false
        hasOwnedRendererSession = false
        request?.let {
            MusicSourceResolvedStreamRegistry.remove(it.source.url, it.generation)
        }
        if (ownsCurrent) {
            controller.stop()
        }
        mutableSnapshot.value = MusicSourceBackendSnapshot()
    }

    fun onRendererStateChanged(state: FfmpegAudioPlayer.State) {
        val request = activeRequest ?: return
        val currentPath = controller.currentSong.value?.path
        val ownsCurrent = currentPath == request.source.url
        if (ownsCurrent) {
            hasOwnedRendererSession = true
        } else {
            // PlayerController.play() can publish PREPARING before currentSong has switched
            // from the previous local item. Do not detach the online backend until it has
            // actually observed ownership once; after that, a different current path means
            // an external/local playback request replaced this session.
            if (hasOwnedRendererSession &&
                (state == FfmpegAudioPlayer.State.PREPARING ||
                    state == FfmpegAudioPlayer.State.PLAYING ||
                    state == FfmpegAudioPlayer.State.PAUSED)
            ) {
                detachForExternalPlayback(request)
            }
            return
        }
        val current = mutableSnapshot.value
        AppLogger.i(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} RENDERER_STATE generation=${request.generation} " +
                "state=$state positionMs=${controller.position.value} durationMs=${controller.duration.value} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - activeStartedAtMs}"
        )
        when (state) {
            FfmpegAudioPlayer.State.IDLE -> Unit
            FfmpegAudioPlayer.State.PREPARING -> {
                mutableSnapshot.value = current.copy(
                    status = MusicSourceBackendStatus.Preparing,
                    error = "",
                )
            }
            FfmpegAudioPlayer.State.PLAYING -> {
                applyPendingStartPolicy()
                mutableSnapshot.value = current.copy(
                    status = if (pauseAfterStart) {
                        MusicSourceBackendStatus.Preparing
                    } else {
                        MusicSourceBackendStatus.Playing
                    },
                    durationMs = controller.duration.value
                        .takeIf { it > 0L }
                        ?: request.item.durationMs.coerceAtLeast(0L),
                    error = "",
                )
            }
            FfmpegAudioPlayer.State.PAUSED -> {
                mutableSnapshot.value = current.copy(
                    status = MusicSourceBackendStatus.Paused,
                    positionMs = controller.position.value.coerceAtLeast(0L),
                    durationMs = controller.duration.value
                        .takeIf { it > 0L }
                        ?: current.durationMs,
                    error = "",
                )
            }
            FfmpegAudioPlayer.State.STOPPED -> {
                if (current.status != MusicSourceBackendStatus.Completed &&
                    current.status != MusicSourceBackendStatus.Error
                ) {
                    mutableSnapshot.value = current.copy(
                        status = if (controller.playState.value == PlayState.PAUSED) {
                            MusicSourceBackendStatus.Paused
                        } else {
                            MusicSourceBackendStatus.Idle
                        },
                    )
                }
            }
            FfmpegAudioPlayer.State.ERROR -> {
                mutableSnapshot.value = current.copy(
                    status = MusicSourceBackendStatus.Error,
                    error = current.error.ifBlank { "在线播放解码失败" },
                )
            }
            FfmpegAudioPlayer.State.COMPLETED -> {
                completionConsumePath = request.source.url
                val duration = controller.duration.value
                    .takeIf { it > 0L }
                    ?: current.durationMs
                mutableSnapshot.value = current.copy(
                    status = MusicSourceBackendStatus.Completed,
                    positionMs = duration.coerceAtLeast(current.positionMs),
                    durationMs = duration.coerceAtLeast(0L),
                )
            }
        }
    }

    fun onPositionChanged(positionMs: Long, durationMs: Long) {
        if (!ownsCurrentSong()) return
        val current = mutableSnapshot.value
        mutableSnapshot.value = current.copy(
            positionMs = positionMs.coerceAtLeast(0L),
            durationMs = durationMs.takeIf { it > 0L } ?: current.durationMs,
        )
    }

    fun onRendererError(message: String) {
        if (!ownsCurrentSong()) return
        AppLogger.e(
            TAG,
            "${OnlinePlaybackDiagnostics.PREFIX} RENDERER_ERROR generation=${activeRequest?.generation ?: 0L} " +
                "message=${message.replace('\n', ' ').replace('\r', ' ').take(512)} " +
                "elapsedMs=${SystemClock.elapsedRealtime() - activeStartedAtMs}"
        )
        mutableSnapshot.value = mutableSnapshot.value.copy(
            status = MusicSourceBackendStatus.Error,
            error = message.take(1_024),
        )
    }

    /** Prevent the global local-queue repeat policy from consuming an online completion. */
    fun consumePlaybackCompletion(): Boolean {
        val completedPath = completionConsumePath ?: return false
        if (controller.currentSong.value?.path != completedPath) return false
        completionConsumePath = null
        return true
    }

    fun close() {
        stop()
    }

    private fun detachForExternalPlayback(request: MusicSourcePlaybackRequest) {
        MusicSourceResolvedStreamRegistry.remove(request.source.url, request.generation)
        activeRequest = null
        pendingSeekMs = 0L
        pendingSeekApplied = false
        pauseAfterStart = false
        pauseAfterStartIssued = false
        hasOwnedRendererSession = false
        mutableSnapshot.value = MusicSourceBackendSnapshot()
    }

    private fun applyPendingStartPolicy() {
        if (!pendingSeekApplied && pendingSeekMs > 0L) {
            pendingSeekApplied = true
            controller.seekTo(pendingSeekMs)
        }
        if (pauseAfterStart && !pauseAfterStartIssued) {
            pauseAfterStartIssued = true
            controller.pause()
        }
    }

    private fun ownsCurrentSong(): Boolean {
        val request = activeRequest ?: return false
        return controller.currentSong.value?.path == request.source.url
    }

    private fun MusicSourcePlaybackRequest.toAudioFile(): AudioFile {
        val inferredFormat = source.url
            .substringBefore('?')
            .substringAfterLast('.', "")
            .uppercase()
            .take(12)
        return AudioFile(
            id = stableLong(item.stableIdentity),
            path = source.url,
            title = item.title,
            artist = item.artists.joinToString(", "),
            album = item.album,
            duration = item.durationMs.coerceAtLeast(0L),
            format = inferredFormat,
            albumArtPath = item.artworkUrl,
            encodingFormat = MUSIC_SOURCE_ONLINE_ENCODING_MARKER,
        )
    }

    private fun stableLong(value: String): Long {
        var hash = 1125899906842597L
        value.forEach { char -> hash = hash * 31L + char.code.toLong() }
        return hash and Long.MAX_VALUE
    }

    private companion object {
        const val TAG = "MusicSourcePlayback"
    }
}
