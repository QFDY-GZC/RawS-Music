package com.rawsmusic.module.player.control

/** UI-thread ownership of the shared PCM callback and its capture gate. No playback transport changes. */
class PcmWaveformConsumerBinding<T : Any>(
    private val callback: T,
    private val currentCallback: () -> T?,
    private val setCallback: (T?) -> Unit,
    private val setCaptureActive: (Boolean) -> Unit,
    private val restorePreviousCallback: Boolean = true,
) {
    private var previousCallback: T? = null

    fun update(active: Boolean) {
        if (active && currentCallback() !== callback) {
            previousCallback = if (restorePreviousCallback) currentCallback() else null
            setCallback(callback)
        }
        if (currentCallback() === callback) setCaptureActive(active)
    }

    fun release() {
        if (currentCallback() === callback) {
            setCaptureActive(false)
            setCallback(previousCallback)
        }
        previousCallback = null
    }
}
