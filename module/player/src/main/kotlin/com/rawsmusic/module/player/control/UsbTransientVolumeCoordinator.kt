package com.rawsmusic.module.player.control

import kotlin.math.pow

/**
 * Owns USB critical-startup timing and transient software-gain protection.
 *
 * Hardware Feature Unit volume is deliberately excluded from transient playback boundaries.
 * It may only be initialized once for one physical DAC attachment session or changed by an explicit user
 * volume action. Track switch, seek, pause, resume and first-data callbacks must not write it.
 */
class UsbTransientVolumeCoordinator(
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val elapsedRealtimeMs: () -> Long,
        val isExclusiveActive: () -> Boolean,
        val isHardwareRouteActive: () -> Boolean,
        val routeDescription: () -> String,
        val setSoftwareGain: (Float) -> Unit,
        val logInfo: (String) -> Unit,
    )

    val safeDb: Int = -32
    val safeLinear: Float = 10.0.pow(safeDb / 20.0).toFloat()

    @Volatile
    private var criticalStartupUntilMs: Long = 0L

    fun enterCriticalStartup(reason: String, durationMs: Long = 2_500L) {
        criticalStartupUntilMs = callbacks.elapsedRealtimeMs() + durationMs.coerceAtLeast(0L)
        callbacks.logInfo("enterUsbCriticalStartup: reason=$reason ms=$durationMs")
    }

    fun isCriticalStartup(): Boolean = callbacks.elapsedRealtimeMs() < criticalStartupUntilMs

    fun applyNoDataSafety(reason: String) {
        if (!callbacks.isExclusiveActive()) return
        val hardwareRoute = callbacks.isHardwareRouteActive()
        callbacks.logInfo(
            "applyUsbNoDataSafetyVolume: reason=$reason safeDb=$safeDb safeLinear=$safeLinear " +
                "hardwareRoute=$hardwareRoute volumePath=${callbacks.routeDescription()}"
        )
        if (hardwareRoute) {
            callbacks.logInfo(
                "Feature Unit unchanged at transient boundary: reason=$reason " +
                    "(device volume is initialized once per physical DAC session)"
            )
            return
        }
        callbacks.setSoftwareGain(safeLinear)
    }
}
