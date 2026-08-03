package com.rawsmusic.module.player.control

/** Pure admission and retry pacing for a deferred USB seek. */
object UsbSeekRuntimePolicy {
    data class Snapshot(
        val usbExclusiveActive: Boolean,
        val publicPreparing: Boolean,
        val decoderPreparing: Boolean,
        val transportTransitioning: Boolean,
        val seekAlreadyRunning: Boolean,
        val handle: Long,
        val engineInitialized: Boolean,
    )

    fun isReady(snapshot: Snapshot): Boolean =
        snapshot.usbExclusiveActive &&
            !snapshot.publicPreparing &&
            !snapshot.decoderPreparing &&
            !snapshot.transportTransitioning &&
            !snapshot.seekAlreadyRunning &&
            snapshot.handle != 0L &&
            snapshot.engineInitialized

    fun retryDelayMs(attempt: Int): Long = if (attempt < 4) 120L else 180L
}
