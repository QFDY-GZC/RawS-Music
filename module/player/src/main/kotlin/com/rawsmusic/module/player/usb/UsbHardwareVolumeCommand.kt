package com.rawsmusic.module.player.usb

/** Serialized command consumed by the single USB Feature Unit owner. */
data class UsbHardwareVolumeCommand(
    val deviceKey: String,
    val uiVolume: Float,
    val reason: String,
    val userInitiated: Boolean,
    val adjustDirection: Int = 0,
)
