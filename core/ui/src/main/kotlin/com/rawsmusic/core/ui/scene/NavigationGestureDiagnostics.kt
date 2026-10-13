package com.rawsmusic.core.ui.scene

import com.rawsmusic.core.common.utils.AppLogger

/** Temporary navigation-input diagnostics; bounded output, no rendering hooks. */
internal class NavigationGestureDiagnostics {
    private var sequence = 0
    private var remaining = 0
    private var previous = 0f
    private var first = true
    private var reversed = false

    fun begin(from: NavScene, to: NavScene?, direction: Float) {
        sequence++
        remaining = 8
        previous = 0f
        first = true
        reversed = false
        emit("BEGIN from=$from to=$to direction=$direction")
    }

    fun progress(value: Float) {
        val reverse = !reversed && value < previous - 0.0001f
        val boundary = (value <= 0f && previous > 0f) || (value >= 1f && previous < 1f)
        if (first || reverse || boundary) {
            emit("PROGRESS value=$value previous=$previous first=$first reverse=$reverse boundary=$boundary")
        }
        first = false
        reversed = reversed || reverse
        previous = value
    }

    fun end(commit: Boolean, velocity: Float) {
        // Always retain release, even when the progress budget was exhausted.
        AppLogger.i("SceneGesture", "nav id=$sequence END progress=$previous commit=$commit velocity=$velocity")
        remaining = 0
    }

    private fun emit(message: String) {
        if (remaining > 0) {
            remaining--
            AppLogger.i("SceneGesture", "nav id=$sequence $message")
        }
    }
}
