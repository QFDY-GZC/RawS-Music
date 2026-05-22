package com.rawsmusic.ui.widget

import android.os.Handler
import android.os.Looper
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.core.ui.widget.BottomCapsuleStateManager
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.ui.songs.PlayerHolder

object CapsuleProgressSync {

    private val handler = Handler(Looper.getMainLooper())
    private const val UPDATE_INTERVAL_MS = 250L

    private var isRunning = false
    private var playerController: PlayerController? = null

    private val updateRunnable = object : Runnable {
        override fun run() {
            if (!isRunning) return
            try {
                val controller = playerController ?: PlayerHolder.controller
                if (controller != null) {
                    BottomCapsuleStateManager.updateProgress(
                        controller.position.value,
                        controller.duration.value
                    )
                }
            } catch (_: Exception) {}
            handler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    fun start(controller: PlayerController? = null) {
        if (controller != null) {
            playerController = controller
        }
        if (isRunning) return
        isRunning = true
        handler.post(updateRunnable)
    }

    fun stop() {
        isRunning = false
        handler.removeCallbacks(updateRunnable)
    }

    fun syncOnce(controller: PlayerController? = null) {
        try {
            val ctrl = controller ?: playerController ?: PlayerHolder.controller
            if (ctrl != null) {
                BottomCapsuleStateManager.updateProgress(
                    ctrl.position.value,
                    ctrl.duration.value
                )
                BottomCapsuleStateManager.updatePlaybackState(
                    isPlaying = ctrl.playState.value == PlayState.PLAYING,
                    title = ctrl.currentSong.value?.title ?: "",
                    artist = ctrl.currentSong.value?.artist ?: "",
                    coverPath = ctrl.currentSong.value?.albumArtPath ?: ""
                )
            }
        } catch (_: Exception) {}
    }
}
