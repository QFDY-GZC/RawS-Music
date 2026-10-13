package com.rawsmusic.core.ui.widget.player

internal class ClassicVisualInteraction {
    var pressed = false
    var dragging = false
    var flinging = false
    fun press() { pressed = true }
    fun drag() { dragging = true }
    fun release(withFling: Boolean) {
        pressed = false
        dragging = false
        flinging = withFling
    }
    fun finish() = release(false)
}

internal class ClassicProgressPress(private val allowed: Boolean, downMs: Long, timeoutMs: Long) {
    val deadlineMs = downMs + timeoutMs
    fun shouldClaim(nowMs: Long, axis: PlayerTimelineGestureAxis, consumed: Boolean) =
        allowed && !consumed && axis == PlayerTimelineGestureAxis.Undecided && nowMs >= deadlineMs
}

internal class ClassicProgressColorOwner(initial: Int) {
    var color = initial
        private set
    var ready = false
        private set
    private var key: String? = null
    fun bind(key: String?) {
        if (key == this.key) return
        this.key = key
        ready = false
    }
    fun accept(key: String?, color: Int?) {
        if (key != this.key || color == null) return
        this.color = color
        ready = true
    }
}

internal data class ClassicGlowGeometry(val width: Float, val height: Float,
    val trackInsetX: Float, val centerY: Float)
internal fun classicGlowGeometry(width: Float, rowHeight: Float, offsetY: Float): ClassicGlowGeometry {
    val height = maxOf(rowHeight, 450f + 2f * kotlin.math.abs(offsetY))
    return ClassicGlowGeometry(width + 600f, height, 300f, height / 2f + offsetY)
}
