package com.rawsmusic.module.player.usb

import android.app.PendingIntent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Owns Android USB permission orchestration.
 *
 * Permission is an Android framework concern, so it intentionally remains in Kotlin. The
 * coordinator itself does not claim interfaces or enter native audio; after a fresh grant it
 * invokes the manager hook on the serialized transport owner so the Java UsbDeviceConnection may
 * be primed before a later playback cutover.
 */
internal class UsbPermissionCoordinator(
    private val usbManager: UsbManager,
    private val permissionGate: UsbPermissionRequestGate,
    private val transportOwner: UsbTransportCommandQueue,
    private val pendingIntentFactory: (UsbDevice) -> PendingIntent,
    private val currentDevice: () -> UsbDevice?,
    private val setCurrentDevice: (UsbDevice) -> Unit,
    private val currentState: () -> UsbExclusiveManager.State,
    private val setState: (UsbExclusiveManager.State) -> Unit,
    private val setError: (String?) -> Unit,
    private val onFreshGrantBeforeResume: (UsbDevice) -> Unit,
    private val onPermissionResolved: (UsbDevice, Boolean) -> Unit,
) {
    private val callbackLock = Any()
    private var pendingCallback: ((Boolean) -> Unit)? = null

    fun handleTransportResult(device: UsbDevice?, granted: Boolean) {
        val callback = synchronized(callbackLock) {
            pendingCallback.also { pendingCallback = null }
        }
        AppLogger.d(
            "UsbPermissionCoordinator",
            "Permission result on Transport: device=${device?.deviceName}, granted=$granted"
        )
        if (granted && device != null) {
            setCurrentDevice(device)
            permissionGate.markGranted()
            setState(UsbExclusiveManager.State.READY)
            setError(null)
            onFreshGrantBeforeResume(device)
            callback?.invoke(true)
            onPermissionResolved(device, true)
        } else {
            setState(UsbExclusiveManager.State.ERROR)
            setError("USB 权限被拒绝")
            if (device != null) {
                permissionGate.markDenied(device.deviceId, System.currentTimeMillis())
                onPermissionResolved(device, false)
            }
            callback?.invoke(false)
        }
    }

    fun requestSafely(device: UsbDevice) {
        val now = System.currentTimeMillis()
        if (setStateIfRequestIsFresh(device, now)) return
        if (permissionGate.isDeniedCoolingDown(device.deviceId, now)) {
            AppLogger.d(TAG, "Permission request cooldown for ${device.deviceName}, skipping")
            return
        }

        setCurrentDevice(device)
        if (usbManager.hasPermission(device)) {
            permissionGate.markGranted()
            setState(UsbExclusiveManager.State.READY)
            setError(null)
            transportOwner.post("permission-already-granted") {
                onPermissionResolved(device, true)
            }
            return
        }

        setState(UsbExclusiveManager.State.REQUESTING_PERMISSION)
        permissionGate.markRequested(now)
        synchronized(callbackLock) {
            pendingCallback = { granted ->
                if (!granted) {
                    setState(UsbExclusiveManager.State.ERROR)
                    setError("USB 权限被拒绝")
                }
            }
        }
        AppLogger.d(TAG, "Requesting USB permission for ${device.deviceName}")
        usbManager.requestPermission(device, pendingIntentFactory(device))
    }

    suspend fun request(device: UsbDevice, force: Boolean = false): Boolean {
        if (usbManager.hasPermission(device)) {
            AppLogger.d(TAG, "Already have permission for ${device.deviceName}")
            setCurrentDevice(device)
            permissionGate.markGranted()
            setState(UsbExclusiveManager.State.READY)
            return true
        }

        val now = System.currentTimeMillis()
        if (!force && permissionGate.isDeniedCoolingDown(device.deviceId, now)) {
            AppLogger.d(TAG, "Permission request cooldown for ${device.deviceName}, skipping")
            return false
        }
        setState(UsbExclusiveManager.State.REQUESTING_PERMISSION)
        permissionGate.markRequested(now)
        return suspendCancellableCoroutine { continuation ->
            val callback: (Boolean) -> Unit = { granted ->
                if (granted) {
                    setCurrentDevice(device)
                    permissionGate.markGranted()
                    setState(UsbExclusiveManager.State.READY)
                } else {
                    setState(UsbExclusiveManager.State.ERROR)
                    setError("USB 权限被拒绝")
                    permissionGate.markDenied(device.deviceId)
                }
                if (continuation.isActive) continuation.resume(granted)
            }
            synchronized(callbackLock) {
                pendingCallback = callback
            }
            continuation.invokeOnCancellation {
                synchronized(callbackLock) {
                    if (pendingCallback === callback) pendingCallback = null
                }
            }
            usbManager.requestPermission(device, pendingIntentFactory(device))
        }
    }

    private fun setStateIfRequestIsFresh(device: UsbDevice, now: Long): Boolean {
        if (currentState() != UsbExclusiveManager.State.REQUESTING_PERMISSION ||
            currentDevice()?.deviceId != device.deviceId
        ) return false
        // A stale request is allowed to retry, matching the pre-split manager
        // behavior while keeping the duplicate-dialog guard stateful.
        if (permissionGate.isFreshRequest(now, REQUEST_FRESHNESS_MS)) {
            AppLogger.d(TAG, "Already requesting permission for ${device.deviceName}, skip duplicate")
            return true
        }
        AppLogger.w(TAG, "USB permission request appears stale, retrying for ${device.deviceName}")
        return false
    }

    private companion object {
        const val TAG = "UsbPermissionCoordinator"
        const val REQUEST_FRESHNESS_MS = 1_500L
    }
}
