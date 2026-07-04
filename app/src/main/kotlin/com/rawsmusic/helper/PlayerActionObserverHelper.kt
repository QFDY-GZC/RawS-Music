package com.rawsmusic.helper

import androidx.lifecycle.LifecycleCoroutineScope
import com.rawsmusic.module.player.PlayerController
import com.rawsmusic.module.player.PlayerEventBus
import com.rawsmusic.module.player.PlayerService
import kotlinx.coroutines.launch

class PlayerActionObserverHelper(
    private val lifecycleScope: LifecycleCoroutineScope,
    private val getPlayerController: () -> PlayerController?,
    private val onSeekAction: () -> Unit
) {
    fun observe() {
        lifecycleScope.launch {
            PlayerEventBus.events.collect { event ->
                val controller = getPlayerController()
                when (event.action) {
                    PlayerService.ACTION_PLAY -> controller?.resume()
                    PlayerService.ACTION_PAUSE -> controller?.pause()
                    PlayerService.ACTION_NEXT -> controller?.next()
                    PlayerService.ACTION_PREVIOUS -> controller?.previous()
                    PlayerService.ACTION_STOP -> controller?.stop()
                    "com.rawsmusic.action.SEEK" -> {
                        onSeekAction()
                        controller?.seekTo(event.position)
                    }
                }
            }
        }
    }
}
