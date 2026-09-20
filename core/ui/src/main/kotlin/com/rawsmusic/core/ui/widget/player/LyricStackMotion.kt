package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * A frame-clock driven retargetable spring for the visual lyric stack.
 *
 * The LazyColumn is moved to its logical position once per lyric handoff. This state owns the
 * temporary screen-space compensation and, unlike Animatable.snapTo, keeps the current velocity
 * when the next line arrives before the previous handoff has settled. That is the important bit:
 * consecutive lyric changes must bend one continuous trajectory instead of stopping and starting
 * a new spring at zero velocity.
 */
@Stable
internal class LyricStackMotionState {
    var offsetPx: Float by mutableFloatStateOf(0f)
        private set

    private var velocityPxPerSecond = 0f
    private var lastFrameTimeMs = Long.MIN_VALUE
    private var dampingRatio = 1f
    private var stiffness = 440f

    var running: Boolean by mutableStateOf(false)
        private set

    fun beginTransition(deltaPx: Float, motion: LyricFollowMotion) {
        if (!deltaPx.isFinite() || deltaPx == 0f) return
        offsetPx += deltaPx
        dampingRatio = motion.dampingRatio.coerceIn(0.75f, 1.05f)
        stiffness = motion.stiffness.coerceIn(140f, 620f)
        // Keep the frame clock and velocity alive when another lyric target arrives mid-flight.
        // Resetting the timestamp here creates one empty vsync and looks like lift/pause/lift.
        if (!running) lastFrameTimeMs = Long.MIN_VALUE
        running = true
    }

    fun cancel() {
        offsetPx = 0f
        velocityPxPerSecond = 0f
        lastFrameTimeMs = Long.MIN_VALUE
        running = false
    }

    fun advance(frameTimeMs: Long) {
        if (!running) return
        val previous = lastFrameTimeMs
        lastFrameTimeMs = frameTimeMs

        // A stalled frame must not make the spring jump through the rest of its trajectory. The
        // four 8 ms substeps retain stable damping on 120 Hz displays and remain safe after a
        // short UI-thread pause.
        // On the first frame after a new target, use one display-sized step instead of publishing
        // an unchanged offset. That unchanged first frame was the visible pause between lifts.
        val deltaSeconds = if (previous == Long.MIN_VALUE) {
            1f / 120f
        } else {
            ((frameTimeMs - previous).coerceIn(1L, 32L)) / 1000f
        }
        val substepSeconds = 0.008f
        var remaining = deltaSeconds
        while (remaining > 0f) {
            val step = minOf(remaining, substepSeconds)
            val angularStiffness = sqrt(stiffness)
            val damping = 2f * dampingRatio * angularStiffness
            val acceleration = -stiffness * offsetPx - damping * velocityPxPerSecond
            velocityPxPerSecond += acceleration * step
            offsetPx += velocityPxPerSecond * step
            remaining -= step
        }

        if (abs(offsetPx) < 0.35f && abs(velocityPxPerSecond) < 8f) {
            offsetPx = 0f
            velocityPxPerSecond = 0f
            running = false
        }
    }
}

@Composable
internal fun rememberLyricStackMotionState(key: Any?): LyricStackMotionState {
    val state = remember(key) { LyricStackMotionState() }
    // Subscribe to the frame clock only while a handoff spring is moving. A permanent vsync
    // subscription kept an otherwise idle lyric page rendering at display rate.
    LaunchedEffect(state, state.running) {
        if (!state.running) return@LaunchedEffect
        while (isActive && state.running) {
            val frameTimeMs = withFrameNanos { it / 1_000_000L }
            state.advance(frameTimeMs)
        }
    }
    return state
}
