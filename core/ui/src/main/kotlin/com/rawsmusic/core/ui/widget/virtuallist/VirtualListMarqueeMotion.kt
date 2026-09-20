package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.cos
import kotlin.math.roundToLong

/** Per-text lifecycle; the host still supplies the single shared frame callback. */
internal class VirtualListMarqueeMotion {
    var offset = 0f
        private set
    private var overflow = 0f
    private var speed = 0f
    private var active = false
    private var phase = 0 // initial hold, outward, end hold, return
    private var start = 0L
    private var duration = 1500L
    private var from = 0f

    fun reset() {
        offset = 0f
        overflow = 0f
        active = false
        phase = 0
        start = 0L
        duration = 1500L
        from = 0f
    }

    fun copyFrom(other: VirtualListMarqueeMotion) {
        offset = other.offset
        overflow = other.overflow
        speed = other.speed
        active = other.active
        phase = other.phase
        start = other.start
        duration = other.duration
        from = other.from
    }

    fun hide() { active = false; offset = 0f }

    fun update(now: Long, distance: Float, pixelsPerSecond: Float,
               enabled: Boolean, visible: Boolean, layoutChanged: Boolean): Float {
        if (!enabled || distance <= 10f || pixelsPerSecond <= 0f) {
            reset()
            return offset
        }
        speed = pixelsPerSecond
        if (layoutChanged || overflow != distance || overflow == 0f) {
            overflow = distance
            offset = offset.coerceIn(0f, overflow)
            phase = 0
            from = offset
            start = now
            duration = 1500L
            active = visible
        } else if (visible && !active) {
            startPhase(now, if (phase == 0 || phase == 2) 3000L else 0L)
            active = true
        }
        if (!visible) { hide(); return offset }
        if (now - start >= duration) {
            if (phase == 1) offset = overflow
            if (phase == 3) offset = 0f
            phase = (phase + 1) % 4
            startPhase(now, 3000L)
        }
        if (phase == 1 || phase == 3) {
            val linear = ((now - start).toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f)
            val fraction = if (overflow > 50f) ((1.0 - cos(Math.PI * linear)) * 0.5).toFloat() else linear
            val target = if (phase == 1) overflow else 0f
            offset = from + (target - from) * fraction
        }
        return offset
    }

    private fun startPhase(now: Long, hold: Long) {
        start = now
        from = offset
        duration = when (phase) {
            1 -> ((overflow - offset) / speed * 1000f).roundToLong().coerceAtLeast(1L)
            3 -> (offset / speed * 1000f).roundToLong().coerceAtLeast(1L)
            else -> hold
        }
    }
}
