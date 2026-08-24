package com.rawsmusic.core.ui.widget.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.setValue
import com.rawsmusic.module.data.prefs.AppPreferences

object LongTextMotionState {
    var enabled by mutableStateOf(AppPreferences.UI.animateLongLabels)
        private set

    var enabledEverywhere by mutableStateOf(AppPreferences.UI.animateLongLabelsEverywhere)
        private set

    /**
     * One phase for all visible marquee clients.  Reference does not give every text view its
     * own animation clock; keeping one phase also prevents a hidden list from continuing to
     * schedule independent frame callbacks.
     */
    var marqueeElapsedMs by mutableLongStateOf(0L)
        private set

    /** Only the zero/non-zero edge is relevant to the app-level frame clock. */
    var hasActiveMarqueeUsers by mutableStateOf(false)
        private set

    private var activeMarqueeUserCount = 0

    /**
     * Lists can stay composed below the player sheet. They must stop registering marquee
     * clients when they are no longer the foreground scene.
     */
    val LocalListMarqueeVisibility = staticCompositionLocalOf { true }

    private var lastClockNanos = 0L

    fun acquireMarquee() {
        activeMarqueeUserCount += 1
        if (activeMarqueeUserCount == 1) hasActiveMarqueeUsers = true
    }

    fun releaseMarquee() {
        activeMarqueeUserCount = (activeMarqueeUserCount - 1).coerceAtLeast(0)
        if (activeMarqueeUserCount == 0) hasActiveMarqueeUsers = false
    }

    fun updateMarqueeElapsed(valueMs: Long) {
        marqueeElapsedMs = valueMs.coerceAtLeast(0L)
    }

    fun advanceMarqueeClock(frameNanos: Long) {
        if (lastClockNanos != 0L) {
            val deltaMs = (frameNanos - lastClockNanos)
                .coerceAtLeast(0L)
                .div(1_000_000L)
            if (deltaMs > 0L) marqueeElapsedMs += deltaMs
        }
        lastClockNanos = frameNanos
    }

    fun pauseMarqueeClock() {
        lastClockNanos = 0L
    }

    fun marqueeOffset(
        elapsedMs: Long,
        overflowPx: Float,
        speedPxPerSecond: Float,
        enabled: Boolean
    ): Float {
        if (!enabled || overflowPx <= 0.5f || speedPxPerSecond <= 0f) return 0f
        val travelMs = (overflowPx / speedPxPerSecond * 1_000f).toLong().coerceAtLeast(1_000L)
        val initialDelay = 1_500L
        val endpointDelay = 3_000L
        val cycle = initialDelay + travelMs + endpointDelay + travelMs + endpointDelay
        val phase = elapsedMs % cycle
        return when {
            phase < initialDelay -> 0f
            phase < initialDelay + travelMs -> {
                overflowPx * ((phase - initialDelay).toFloat() / travelMs)
            }
            phase < initialDelay + travelMs + endpointDelay -> overflowPx
            phase < initialDelay + travelMs + endpointDelay + travelMs -> {
                val returning = phase - initialDelay - travelMs - endpointDelay
                overflowPx * (1f - returning.toFloat() / travelMs)
            }
            else -> 0f
        }
    }

    fun updateEnabled(value: Boolean) {
        enabled = value
        AppPreferences.UI.animateLongLabels = value
        if (!value) updateEnabledEverywhere(false)
    }

    fun updateEnabledEverywhere(value: Boolean) {
        enabledEverywhere = value && enabled
        AppPreferences.UI.animateLongLabelsEverywhere = enabledEverywhere
    }
}
