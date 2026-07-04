package com.rawsmusic.helper

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.data.prefs.PlaybackStatsStore
import com.rawsmusic.module.player.PlayerController

class PlaybackStatsHelper(
    context: Context,
    private val getPlayerController: () -> PlayerController?
) {
    private val playbackStatsStore by lazy {
        PlaybackStatsStore.getInstance(context.applicationContext)
    }
    private val statsHandler = Handler(Looper.getMainLooper())
    private val statsTickRunnable = object : Runnable {
        override fun run() {
            updatePlaybackStats()
            statsHandler.postDelayed(this, 1000L)
        }
    }

    private var statsSongId: Long? = null
    private var statsSong: AudioFile? = null
    private var playCountedSongId: Long? = null
    private var pendingListenMs = 0L
    private var lastStatsTickMs = 0L

    fun start() {
        statsHandler.removeCallbacks(statsTickRunnable)
        statsHandler.post(statsTickRunnable)
    }

    fun stop() {
        statsHandler.removeCallbacks(statsTickRunnable)
        flushPlaybackStats()
    }

    private fun updatePlaybackStats() {
        val now = SystemClock.elapsedRealtime()
        val playerController = getPlayerController()
        val song = playerController?.currentSong?.value
        val isPlaying = playerController?.playState?.value == PlayState.PLAYING
        val songId = song?.id

        if (songId != statsSongId) {
            flushPlaybackStats()
            statsSongId = songId
            statsSong = song
            playCountedSongId = null
            lastStatsTickMs = now
            return
        }

        if (song != null && isPlaying) {
            if (lastStatsTickMs > 0L) {
                pendingListenMs += (now - lastStatsTickMs).coerceIn(0L, 1500L)
            }
            if (playCountedSongId != song.id && pendingListenMs >= 20_000L) {
                playbackStatsStore.recordPlay(song)
                playCountedSongId = song.id
            }
            if (playCountedSongId == song.id && pendingListenMs >= 5000L) {
                playbackStatsStore.addListenTime(song, pendingListenMs)
                pendingListenMs = 0L
            }
        } else {
            flushPlaybackStats()
        }
        lastStatsTickMs = now
    }

    private fun flushPlaybackStats() {
        val song = statsSong
        if (song != null && playCountedSongId == song.id && pendingListenMs > 0L) {
            playbackStatsStore.addListenTime(song, pendingListenMs)
        }
        pendingListenMs = 0L
    }
}
