package com.rawsmusic.module.player.usb

/** Parses the compact audible-state line emitted by the native USB engine. */
internal object UsbAudibleState {
    fun tokenValue(state: String, key: String): String? {
        if (state.isBlank()) return null
        return state.split(' ').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')
    }

    fun accepted(state: String): Boolean = tokenValue(state, "audible") == "1"

    fun needsVolumeRepair(state: String): Boolean {
        if (state.isBlank()) return false
        val completed = tokenValue(state, "completed")?.toLongOrNull() ?: 0L
        val volumeReady = tokenValue(state, "volumeReady")
            ?: tokenValue(state, "audibleVolReady")
        val hwSafeActive = tokenValue(state, "hwSafeActive")
        return completed > 0L && (volumeReady == "0" || hwSafeActive == "1")
    }
}
