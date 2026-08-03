package com.rawsmusic.ui.settings

import android.os.SystemClock

/**
 * Prevents the Activity below a settings page from arming its own predictive-back callback while
 * the platform is still completing the settings Activity's cross-Activity back gesture.
 */
object SettingsBackHandoffRuntime {
    private const val HANDOFF_GUARD_MS = 360L

    @Volatile
    private var blockedUntilUptimeMs: Long = 0L

    fun noteSettingsFinish(nowUptimeMs: Long = SystemClock.uptimeMillis()) {
        blockedUntilUptimeMs = maxOf(blockedUntilUptimeMs, nowUptimeMs + HANDOFF_GUARD_MS)
    }

    fun isMainSceneBackBlocked(nowUptimeMs: Long = SystemClock.uptimeMillis()): Boolean =
        nowUptimeMs < blockedUntilUptimeMs

    fun remainingBlockMs(nowUptimeMs: Long = SystemClock.uptimeMillis()): Long =
        (blockedUntilUptimeMs - nowUptimeMs).coerceAtLeast(0L)
}
