package com.rawsmusic.ui.widget

import android.os.Handler
import android.os.Looper
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.ui.songs.PlayerHolder

object CapsuleProgressSync {

    private val handler = Handler(Looper.getMainLooper())
    private const val UPDATE_INTERVAL_MS = 250L

    private var isRunning = false
    private var playerController: PlayerController? = null
    private var capsuleView: RawSMusicCapsuleView? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                val controller = playerController ?: PlayerHolder.controller
                val capsule = capsuleView
                if (controller != null && capsule != null) {
                    capsule.updateProgress(
                        controller.position.value,
                        controller.duration.value
                    )
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun start(controller: PlayerController? = null, capsule: RawSMusicCapsuleView? = null) {
        if (controller != null) {
            playerController = controller
        }
        if (capsule != null) {
            capsuleView = capsule
        }
        if (isRunning) return
        isRunning = true
        handler.post(updateRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
    }

    fun syncOnce(controller: PlayerController? = null, capsule: RawSMusicCapsuleView? = null) {
        try {
            val ctrl = controller ?: playerController ?: PlayerHolder.controller
            val cap = capsule ?: capsuleView
            if (ctrl != null && cap != null) {
                cap.updateProgress(
                    ctrl.position.value,
                    ctrl.duration.value
                )
                cap.updatePlaybackState(
                    isPlaying = ctrl.playState.value == PlayState.PLAYING,
                    title = ctrl.currentSong.value?.title ?: "",
                    artist = ctrl.currentSong.value?.artist ?: "",
                    coverPath = ctrl.currentSong.value?.albumArtPath
                )
            }
        } catch (_: Exception) {}
    }
}
