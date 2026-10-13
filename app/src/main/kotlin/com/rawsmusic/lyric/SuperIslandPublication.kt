package com.rawsmusic.lyric

/** Focus extras must also pass through notify(), including the first foreground publication. */
internal object SuperIslandPublication {
    fun publish(
        foregroundStarted: Boolean,
        startFocus: () -> Boolean,
        startWarm: () -> Boolean,
        notify: () -> Unit,
    ): Boolean {
        if (!foregroundStarted && !startFocus() && !startWarm()) return false
        notify()
        return true
    }
}
