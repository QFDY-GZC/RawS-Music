package com.rawsmusic.module.scanner

/** Automatic request timing, independent of Android and coroutine scheduling. */
class AutomaticScanPolicy {
    var requestsEnabledAtMs: Long? = null
    var fileDeletedAtMs: Long? = null
    var initializationAtMs: Long? = null

    private var lastChangeMs: Long? = null
    private var changeCount = 0

    fun startupDelayMs(uptimeMs: Long): Long = if (uptimeMs > 60000L) 2000L else 10000L

    fun systemChangeDelayMs(nowMs: Long, uptimeMs: Long, immediate: Boolean): Long {
        val bootDelay = if (uptimeMs > 60000L) 0L else 10000L
        val longWindow = (if (immediate) 1000L else 12000L) + bootDelay
        val shortRequest = lastChangeMs == null || nowMs - lastChangeMs!! > longWindow || changeCount < 2
        if (shortRequest) changeCount++
        lastChangeMs = nowMs
        return bootDelay + if (shortRequest) {
            (if (immediate) 500L else 1000L) + bootDelay
        } else longWindow
    }

    fun canDispatch(nowMs: Long, scanRunning: Boolean, disabledRequests: Int): Boolean =
        !scanRunning && disabledRequests <= 0 &&
            outsideWindow(nowMs, initializationAtMs, 3000L) &&
            outsideWindow(nowMs, requestsEnabledAtMs, 5000L) &&
            outsideWindow(nowMs, fileDeletedAtMs, 11000L)

    private fun outsideWindow(nowMs: Long, timestamp: Long?, window: Long): Boolean =
        timestamp == null || nowMs - timestamp >= window
}
