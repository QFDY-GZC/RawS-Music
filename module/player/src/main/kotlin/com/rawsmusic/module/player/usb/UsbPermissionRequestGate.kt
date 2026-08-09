package com.rawsmusic.module.player.usb

/** Pure cooldown state for Android USB permission requests. */
internal class UsbPermissionRequestGate(
    private val deniedCooldownMs: Long = 3_000L,
) {
    private var lastRequestAtMs = 0L
    private var deniedDeviceId = -1

    fun markRequested(nowMs: Long) {
        lastRequestAtMs = nowMs
    }

    fun markGranted() {
        deniedDeviceId = -1
    }

    fun markDenied(deviceId: Int, nowMs: Long = lastRequestAtMs) {
        deniedDeviceId = deviceId
        lastRequestAtMs = nowMs
    }

    fun isFreshRequest(nowMs: Long, windowMs: Long): Boolean =
        nowMs - lastRequestAtMs < windowMs

    fun isDeniedCoolingDown(deviceId: Int, nowMs: Long): Boolean =
        deviceId == deniedDeviceId && nowMs - lastRequestAtMs < deniedCooldownMs
}
