package com.rawsmusic.core.ui.widget.virtuallist

/** Keeps the last settled provider input until the outer scene transaction releases ownership. */
internal class VirtualListTransitionInputs<T> {
    private var initialized = false
    private var retained: T? = null

    @Suppress("UNCHECKED_CAST")
    fun resolve(transitionOwnsInput: Boolean, current: T): T {
        if (!initialized || !transitionOwnsInput) {
            retained = current
            initialized = true
        }
        return retained as T
    }
}
