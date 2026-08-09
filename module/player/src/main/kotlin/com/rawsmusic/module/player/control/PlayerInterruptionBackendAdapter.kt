package com.rawsmusic.module.player.control

import com.rawsmusic.module.player.FfmpegAudioPlayer

internal fun FfmpegAudioPlayer.State.toInterruptionBackendState():
    PlayerInterruptionControlCoordinator.BackendState = when (this) {
    FfmpegAudioPlayer.State.IDLE -> PlayerInterruptionControlCoordinator.BackendState.IDLE
    FfmpegAudioPlayer.State.PREPARING -> PlayerInterruptionControlCoordinator.BackendState.PREPARING
    FfmpegAudioPlayer.State.PLAYING -> PlayerInterruptionControlCoordinator.BackendState.PLAYING
    FfmpegAudioPlayer.State.PAUSED -> PlayerInterruptionControlCoordinator.BackendState.PAUSED
    FfmpegAudioPlayer.State.STOPPED -> PlayerInterruptionControlCoordinator.BackendState.STOPPED
    FfmpegAudioPlayer.State.ERROR -> PlayerInterruptionControlCoordinator.BackendState.ERROR
    FfmpegAudioPlayer.State.COMPLETED -> PlayerInterruptionControlCoordinator.BackendState.COMPLETED
}
