package com.rawsmusic.core.ui.widget.virtuallist

import kotlin.math.cos
import kotlin.math.roundToLong

internal fun shouldAdvanceVirtualListText(updateInternalScene: Boolean, marqueeEnabled: Boolean): Boolean =
    updateInternalScene || marqueeEnabled

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
               enabled: Boolean, visible: Boolean, layoutChanged: Boolean,
               preserveProgress: Boolean = false): Float {
        if (!enabled || distance <= 10f || pixelsPerSecond <= 0f) {
            reset()
            return offset
        }
        speed = pixelsPerSecond
        if (preserveProgress && active && overflow > 0f &&
            (layoutChanged || overflow != distance)) {
            // Sample the old leg before changing its endpoint. Retarget at the same eased
            // fraction so geometry updates neither jump the offset nor restart the hold/clock.
            advance(now)
            overflow = distance
            offset = offset.coerceIn(0f, overflow)
            if (phase == 1 || phase == 3) {
                val fraction = fractionAt(now)
                val target = if (phase == 1) overflow else 0f
                from = if (fraction < 1f) (offset - target * fraction) / (1f - fraction) else offset
            }
        } else if (layoutChanged || overflow != distance || overflow == 0f) {
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
        advance(now)
        return offset
    }

    private fun fractionAt(now: Long): Float {
        val linear = ((now - start).toFloat() / duration.coerceAtLeast(1L)).coerceIn(0f, 1f)
        return if (overflow > 50f) ((1.0 - cos(Math.PI * linear)) * 0.5).toFloat() else linear
    }

    private fun advance(now: Long) {
        if (now - start >= duration) {
            if (phase == 1) offset = overflow
            if (phase == 3) offset = 0f
            phase = (phase + 1) % 4
            startPhase(now, 3000L)
        }
        if (phase == 1 || phase == 3) {
            val fraction = fractionAt(now)
            val target = if (phase == 1) overflow else 0f
            offset = from + (target - from) * fraction
        }
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
