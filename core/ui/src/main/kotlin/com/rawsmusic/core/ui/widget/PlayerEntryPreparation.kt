package com.rawsmusic.core.ui.widget

/** UI-thread request ownership: elapsed frames alone do not make a surface ready. */
internal class PlayerEntryPreparation {
    private var pending: Int? = null
    private var laidOut = false

    fun begin(id: Int) {
        pending = id
        laidOut = false
    }

    fun onLayout(id: Int) {
        if (pending == id) laidOut = true
    }

    fun consumeReady(id: Int): Boolean {
        if (pending != id || !laidOut) return false
        cancel()
        return true
    }

    fun cancel() {
        pending = null
        laidOut = false
    }
}
