package com.rawsmusic.core.ui.widget.player

import kotlin.math.*

/** Exact damped-spring step: changing targets retains the displayed position and velocity. */
internal class LyricSpringState(var value: Float = 0f, var velocity: Float = 0f) {
    val running: Boolean get() = value != 0f || velocity != 0f

    fun advance(seconds: Float, stiffness: Float = LyricAmMotionSpec.STIFFNESS,
                dampingRatio: Float = LyricAmMotionSpec.DAMPING_RATIO) {
        if (!running || seconds <= 0f) return
        val w = sqrt(stiffness.toDouble())
        val z = dampingRatio.toDouble()
        val t = seconds.toDouble()
        val x = value.toDouble()
        val v = velocity.toDouble()
        val result: Pair<Double, Double> = when {
            abs(z - 1.0) < .00001 -> {
                val b = v + w * x
                val e = exp(-w * t)
                e * (x + b * t) to e * (b - w * (x + b * t))
            }
            z < 1.0 -> {
                val a = z * w
                val wd = w * sqrt(1.0 - z * z)
                val b = (v + a * x) / wd
                val c = cos(wd * t); val s = sin(wd * t); val e = exp(-a * t)
                e * (x * c + b * s) to e * ((wd * b - a * x) * c - (wd * x + a * b) * s)
            }
            else -> {
                val q = sqrt(z * z - 1.0)
                val r1 = -w * (z - q); val r2 = -w * (z + q)
                val c1 = (v - r2 * x) / (r1 - r2); val c2 = x - c1
                val e1 = exp(r1 * t); val e2 = exp(r2 * t)
                c1 * e1 + c2 * e2 to r1 * c1 * e1 + r2 * c2 * e2
            }
        }
        value = result.first.toFloat(); velocity = result.second.toFloat()
        if (abs(value) < .1f && abs(velocity) < .5f) { value = 0f; velocity = 0f }
    }
}
