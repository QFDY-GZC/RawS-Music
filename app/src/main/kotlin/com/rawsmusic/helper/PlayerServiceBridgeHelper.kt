package com.rawsmusic.helper

import android.content.Context
import android.content.Intent
import android.os.Build
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerService

class PlayerServiceBridgeHelper(
    private val context: Context,
    private val getPlayerController: () -> PlayerController?,
    private val resolveCoverUri: (AudioFile) -> String
) {
    fun startForegroundServiceIfNeeded() {
        val intent = Intent(context, PlayerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(intent)
        } else {
            context.startService(intent)
        }
    }

    fun pushLyricsUpdate() {
        PlayerService.pushLyricsToMediaSession()
        if (!PlayerService.isRunning) return
        try {
            val intent = Intent(context, PlayerService::class.java).apply {
                action = PlayerService.ACTION_UPDATE_LYRICS
            }
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    fun pushSongUpdate(song: AudioFile) {
        if (!PlayerService.isRunning) return
        try {
            val playerController = getPlayerController()
            val coverUri = resolveCoverUri(song).ifBlank { song.albumArtPath }
            val intent = Intent(context, PlayerService::class.java).apply {
                action = PlayerService.ACTION_UPDATE
                putExtra("title", song.title)
                putExtra("artist", song.artist)
                putExtra("album", song.album)
                putExtra("albumArtPath", coverUri)
                putExtra("duration", song.duration)
                putExtra("playState", (playerController?.playState?.value ?: PlayState.IDLE).ordinal)
                putExtra("position", playerController?.position?.value ?: 0L)
                putExtra("sampleRate", song.sampleRate)
                putExtra("bitRate", song.bitRate)
            }
            context.startService(intent)
        } catch (_: Exception) {
        }
    }

    fun syncPosition(positionMs: Long) {
        if (!PlayerService.isRunning) return
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
