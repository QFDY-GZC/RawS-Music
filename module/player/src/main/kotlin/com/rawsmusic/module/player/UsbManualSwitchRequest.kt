package com.rawsmusic.module.player

import kotlinx.coroutines.CompletableDeferred

/** Request published by the controller and consumed at a USB feeder boundary. */
internal data class UsbManualSwitchRequest(
    val serial: Long,
    val generation: Int,
    val targetPath: String,
    val reason: String,
    val completion: CompletableDeferred<Boolean> = CompletableDeferred()
)
