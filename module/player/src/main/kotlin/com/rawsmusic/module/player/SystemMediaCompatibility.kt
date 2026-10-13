package com.rawsmusic.module.player

/** Dimensions of the artwork snapshot shared by SystemUI and Bluetooth. */
internal fun systemMediaArtworkSize(width: Int, height: Int): Pair<Int, Int> {
    // This session is shared with SystemUI: a Bluetooth optimization must not
    // replace the system media card's already-decoded high-resolution cover.
    return width to height
}

/** State-only compatibility pulse; must never republish artwork or notifications. */
internal class BluetoothPlaybackPulse {
    private var lastPulseMs: Long? = null

    fun shouldPublish(enabled: Boolean, playing: Boolean, nowMs: Long): Boolean {
        if (!enabled || !playing) {
            lastPulseMs = null
            return false
        }
        val last = lastPulseMs
        if (last != null && nowMs - last < 500L) return false
        lastPulseMs = nowMs
        return true
    }
}
