package com.rawsmusic.helper

import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.PlayMode
import com.rawsmusic.core.common.model.PlayState
import com.rawsmusic.module.player.PlayerController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * 播放器状态观察回调。
 */
interface PlayerObserverCallbacks {
    fun onPlaybackStateChanged(state: PlayState) {}
    fun onCurrentSongChanged(song: AudioFile) {}
    fun onPositionChanged(positionMs: Long, durationMs: Long) {}
    fun onUsbOutputSampleRateChanged(sampleRate: Int) {}
    fun onPlayModeChanged(mode: PlayMode) {}
}

/**
 * 播放器状态观察协调器。
 *
 * 统一订阅 PlayerController 的 StateFlow，
 * 把事件分发给 callbacks，替代 MainActivity initObserver() 里的散落 collector。
 */
class PlayerObserverCoordinator(
    private val lifecycleOwner: LifecycleOwner,
    private val getPlayerController: () -> PlayerController?,
    private val callbacks: PlayerObserverCallbacks
) {
    private val jobs = mutableListOf<Job>()

    fun start() {
        stop()
        val controller = getPlayerController() ?: return

        jobs += lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            controller.playState.collect { callbacks.onPlaybackStateChanged(it) }
        }

        jobs += lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            controller.currentSong.collect { song ->
                if (song != null) callbacks.onCurrentSongChanged(song)
            }
        }

        jobs += lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            controller.position.collect { pos ->
                callbacks.onPositionChanged(pos, controller.duration.value)
            }
        }

        jobs += lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            controller.usbOutputSampleRate.collect { sr ->
                callbacks.onUsbOutputSampleRateChanged(sr)
            }
        }

        jobs += lifecycleOwner.lifecycleScope.launch(Dispatchers.Main) {
            controller.playMode.collect { mode ->
                callbacks.onPlayModeChanged(mode)
            }
        }
    }

    fun stop() {
        jobs.forEach { it.cancel() }
        jobs.clear()
    }
}
