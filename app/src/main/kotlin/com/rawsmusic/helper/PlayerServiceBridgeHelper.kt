package com.rawsmusic.helper

import android.content.Context
import android.content.Intent
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService

class PlayerServiceBridgeHelper(
    private val context: Context,
    private val getPlayerController: () -> PlayerController?,
    private val resolveCoverUri: (AudioFile) -> String
) {
    fun startForegroundServiceIfNeeded() {
        val controller = getPlayerController()
        val persistedState = PlayState.entries.getOrElse(AppPreferences.Player.lastPlayStateOrdinal) {
            PlayState.IDLE
        }
        // MainActivity calls this from a short deferred startup callback. A paused selection is
        // UI state, not a reason to create a foreground service and all of its native/audio
        // resources; only active playback/preparation or an explicitly retained USB session needs
        // the service owner.
        val shouldStart = controller?.playState?.value == PlayState.PLAYING ||
            controller?.playState?.value == PlayState.PREPARING ||
            persistedState == PlayState.PLAYING ||
            persistedState == PlayState.PREPARING ||
            controller?.isUsbExclusiveActive() == true ||
            AppPreferences.Player.usbExclusiveRequested
        if (!shouldStart) return
        PlayerService.ensureServiceStarted(
            context,
            "player_service_bridge_start"
        )
    }

    fun pushLyricsUpdate() {
        PlayerService.pushLyricsToMediaSession()
    }

    fun pushSongUpdate(song: AudioFile) {
        if (!PlayerService.isRunning) return
        val playerController = getPlayerController()
        val coverUri = resolveCoverUri(song).ifBlank { song.coverKey }
        // Keep system notification / MediaSession artwork on the same identity as the UI.
        // MediaStore albumArtPath is often blank in RawSMusic because covers are extracted from
        // the audio file itself, so pass the real audio path as a fallback.
        val serviceArtworkPath = coverUri.ifBlank { song.path }
        val playState = playerController?.playState?.value ?: PlayState.IDLE
        val position = playerController?.position?.value ?: 0L
        if (PlayerService.syncSongUpdateFromUi(song, serviceArtworkPath, playState, position)) {
            return
        }
        try {
            val intent = Intent(context, PlayerService::class.java).apply {
                action = PlayerService.ACTION_UPDATE
                putExtra("title", song.title)
                putExtra("artist", song.artist)
                putExtra("album", song.album)
                putExtra("albumArtPath", serviceArtworkPath)
                putExtra("duration", song.duration)
                putExtra("path", song.path)
                putExtra("fileSize", song.fileSize)
                putExtra("dateModified", song.dateModified)
                putExtra("cueTrackIndex", song.cueTrackIndex)
                putExtra("playState", playState.ordinal)
                putExtra("position", position)
                putExtra("sampleRate", song.sampleRate)
                putExtra("bitRate", song.bitRate)
            }
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    fun syncPosition(positionMs: Long) {
        if (!PlayerService.isRunning) return
        if (PlayerService.syncPositionFromUi(positionMs)) return
        try {
            val intent = Intent(context, PlayerService::class.java).apply {
                action = "com.rawsmusic.action.SYNC_POSITION"
                putExtra("position", positionMs)
            }
            context.startService(intent)
        } catch (_: Exception) {
        }
    }
}
