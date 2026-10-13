package com.rawsmusic.core.ui.widget.player

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.sqrt
import kotlin.math.sin
import kotlin.math.cos

internal enum class ClassicConeMode { Off, Playing, Dragging }
internal class ClassicProgressFrameClock {
    private var previousNs = 0L
    fun advance(frameNs: Long): Float {
        val dt = if (previousNs == 0L) 0f else
            ((frameNs - previousNs) / 1_000_000_000f).coerceIn(0f, 0.064f)
        previousNs = frameNs
        return dt
    }
    fun reset() { previousNs = 0L }
}
internal fun classicConeMode(playing: Boolean, dragging: Boolean) = when {
    !playing -> ClassicConeMode.Off
    dragging -> ClassicConeMode.Dragging
    else -> ClassicConeMode.Playing
}

// Playback wins over dragging, as verified in IntentConicProgressRenderer.smali.
internal fun classicHeadLength(fraction: Float, playing: Boolean, dragging: Boolean): Float =
    min(fraction.coerceIn(0f, 1f), when {
        playing -> 0.2f
        dragging -> 0.1f
        else -> 0f
    })

internal class ClassicProgressSpring(initial: Float) {
    var value = initial
        private set
    var target = initial
    var velocity = 0f
        private set
    val running get() = abs(value - target) > 0.00001f || abs(velocity) > 0.0001f

    fun snap(next: Float) {
        value = next
        target = next
        velocity = 0f
    }

    fun advance(seconds: Float, response: Float, bounce: Float = 0f) {
        if (!running) { snap(target); return }
        // COUISpringForce response=2π/ω and dampingRatio=1-bounce.
        val dt = seconds.coerceIn(0f, 0.064f)
        val omega = (2.0 * PI / response.coerceAtLeast(0.01f)).toFloat()
        val displacement = value - target
        val damping = (1f - bounce).coerceIn(0.01f, 1f)
        if (damping >= 0.9999f) {
            val c = velocity + omega * displacement
            val decay = exp(-omega * dt)
            value = target + (displacement + c * dt) * decay
            velocity = (velocity - omega * c * dt) * decay
        } else {
            val dampedOmega = omega * sqrt(1f - damping * damping)
            val decay = exp(-damping * omega * dt)
            val cosine = cos(dampedOmega * dt)
            val sine = sin(dampedOmega * dt)
            val oldVelocity = velocity
            value = target + decay * (displacement * cosine +
                (oldVelocity + damping * omega * displacement) / dampedOmega * sine)
            velocity = decay * (oldVelocity * cosine -
                (damping * omega * oldVelocity + omega * omega * displacement) / dampedOmega * sine)
        }
        if (!running) snap(target)
    }
}

internal class ClassicProgressFling {
    var fraction = 0f
        private set
    var velocityPx = 0f
        private set
    var running = false
        private set
    private var widthPx = 1f
    fun start(fraction: Float, velocityPx: Float, widthPx: Float): Boolean {
        this.fraction = fraction.coerceIn(0f, 1f)
        this.widthPx = widthPx.coerceAtLeast(1f)
        this.velocityPx = velocityPx.coerceIn(-8000f, 8000f)
        running = widthPx > 1f && abs(this.velocityPx) >= 100f
        return running
    }
    fun advance(seconds: Float) {
        if (!running) return
        // Same implicit linear damping used by Oplus PhysicsEngine's body solver.
        // Substeps keep a dropped UI frame from changing the result drastically.
        var remaining = seconds.coerceIn(0f, 0.064f)
        while (remaining > 0f && running) {
            val dt = min(remaining, 1f / 120f)
            velocityPx /= 1f + 15f * dt
            val next = fraction + velocityPx * dt / widthPx
            fraction = next.coerceIn(0f, 1f)
            if (next <= 0f || next >= 1f || abs(velocityPx) < 1f) cancel()
            remaining -= dt
        }
    }
    fun cancel() { running = false; velocityPx = 0f }
}

internal class ClassicSeekHold {
    private var fraction: Float? = null
    var untilMs = 0L
        private set
    fun commit(fraction: Float, nowMs: Long) {
        this.fraction = fraction.coerceIn(0f, 1f)
        untilMs = nowMs + 1000L
    }
    fun display(reported: Float, nowMs: Long): Float {
        if (nowMs >= untilMs) clear()
        return fraction ?: reported.coerceIn(0f, 1f)
    }
    fun clear() { fraction = null; untilMs = 0L }
}
