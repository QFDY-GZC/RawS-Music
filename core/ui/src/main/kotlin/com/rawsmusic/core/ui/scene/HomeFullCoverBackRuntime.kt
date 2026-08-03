package com.rawsmusic.core.ui.scene

/**
 * Activity-owned predictive-back bridge for the portrait home dial.
 *
 * Keeping the platform callback in MainActivity removes the one-frame ownership gap that occurred
 * while a Compose NavigationBackHandler was being inserted/removed around the full-cover scene.
 */
object HomeFullCoverBackRuntime {
    data class Callbacks(
        val onStarted: () -> Unit,
        val onProgressed: (Float) -> Unit,
        val onCancelled: () -> Unit,
        val onCompleted: () -> Unit,
    )

    private var ownerToken: Any? = null
    private var callbacks: Callbacks? = null

    @Synchronized
    fun register(owner: Any, callbacks: Callbacks) {
        ownerToken = owner
        this.callbacks = callbacks
    }

    @Synchronized
    fun unregister(owner: Any) {
        if (ownerToken !== owner) return
        ownerToken = null
        callbacks = null
    }

    @Synchronized
    fun hasOwner(): Boolean = callbacks != null

    fun start(): Boolean {
        val current = synchronized(this) { callbacks } ?: return false
        current.onStarted()
        return true
    }

    fun progress(progress: Float) {
        synchronized(this) { callbacks }?.onProgressed?.invoke(progress.coerceIn(0f, 1f))
    }

    fun cancel() {
        synchronized(this) { callbacks }?.onCancelled?.invoke()
    }

    fun complete(): Boolean {
        val current = synchronized(this) { callbacks } ?: return false
        current.onCompleted()
        return true
    }
}
