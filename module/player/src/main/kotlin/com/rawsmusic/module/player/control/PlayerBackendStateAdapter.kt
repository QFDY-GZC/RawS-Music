package com.rawsmusic.module.player.control

import com.rawsmusic.module.player.FfmpegAudioPlayer

internal fun FfmpegAudioPlayer.State.toBackendControlState():
    PlayerBackendStateControlCoordinator.BackendState = when (this) {
    FfmpegAudioPlayer.State.IDLE -> PlayerBackendStateControlCoordinator.BackendState.IDLE
    FfmpegAudioPlayer.State.PREPARING -> PlayerBackendStateControlCoordinator.BackendState.PREPARING
    FfmpegAudioPlayer.State.PLAYING -> PlayerBackendStateControlCoordinator.BackendState.PLAYING
    FfmpegAudioPlayer.State.PAUSED -> PlayerBackendStateControlCoordinator.BackendState.PAUSED
    FfmpegAudioPlayer.State.STOPPED -> PlayerBackendStateControlCoordinator.BackendState.STOPPED
    FfmpegAudioPlayer.State.ERROR -> PlayerBackendStateControlCoordinator.BackendState.ERROR
    FfmpegAudioPlayer.State.COMPLETED -> PlayerBackendStateControlCoordinator.BackendState.COMPLETED
}
