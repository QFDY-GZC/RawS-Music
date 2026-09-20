package com.rawsmusic.module.player.devicecontrol

import com.rawsmusic.module.player.devicecontrol.bluetooth.BluetoothAudioRouteSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Keeps DeviceControlManager bound to the physical device that currently owns audio output. */
class DeviceControlRouteCoordinator(
    private val scope: CoroutineScope,
    private val manager: DeviceControlManager,
    private val resolver: CurrentAudioDeviceControlResolver,
    private val usbExclusiveActive: StateFlow<Boolean>,
    private val bluetoothRoute: StateFlow<BluetoothAudioRouteSnapshot?>,
) {
    private val mutex = Mutex()
    private var routeJob: Job? = null
    private var usbRetryJob: Job? = null
    private var boundStableId: String? = null

    fun start() {
        if (routeJob?.isActive == true) return
        routeJob = scope.launch {
            combine(usbExclusiveActive, bluetoothRoute) { usbActive, route -> usbActive to route }
                .collect { (usbActive, route) -> reconcile(usbActive, route, forceRefresh = false) }
        }
        // USB AudioControl becomes queryable only after native/libusb initialization. The active
        // flag and engine-ready moment are not the same event, so retry only while USB is active.
        usbRetryJob = scope.launch {
            while (isActive) {
                delay(1_000L)
                if (usbExclusiveActive.value) {
                    reconcile(usbExclusiveActive.value, bluetoothRoute.value, forceRefresh = false)
                }
            }
        }
    }

    /**
     * Ensure the current physical route is bound without forcing a hardware refresh.
     *
     * This is the UI-entry path: if the playback/background service already owns a live
     * DeviceControlSession for the same stable device, the existing snapshot/session is reused
     * with zero control-plane I/O. A new bind still happens after process/service recreation or a
     * real route change because [boundStableId] will no longer match.
     */
    suspend fun ensureBound() {
        reconcile(usbExclusiveActive.value, bluetoothRoute.value, forceRefresh = false)
    }

    /** Explicit user/permission refresh; this is allowed to perform device reads. */
    suspend fun refresh() {
        reconcile(usbExclusiveActive.value, bluetoothRoute.value, forceRefresh = true)
    }

    fun close() {
        routeJob?.cancel()
        usbRetryJob?.cancel()
        routeJob = null
        usbRetryJob = null
        boundStableId = null
    }

    private suspend fun reconcile(
        usbActive: Boolean,
        route: BluetoothAudioRouteSnapshot?,
        forceRefresh: Boolean,
    ) = mutex.withLock {
        when (val result = resolver.resolve(route, usbActive)) {
            is CurrentAudioDeviceControlResolver.Result.Resolved -> {
                if (boundStableId != result.device.stableId) {
                    boundStableId = result.device.stableId
                    manager.bind(result.device)
                } else if (forceRefresh) {
                    // Permission may have been granted while a fixed PERMISSION_REQUIRED session
                    // was bound. Re-open backends instead of refreshing that placeholder session.
                    if (manager.snapshot.value.probeState == DeviceControlSnapshot.ProbeState.PERMISSION_REQUIRED) {
                        manager.bind(result.device)
                    } else {
                        manager.refresh()
                    }
                }
            }
            is CurrentAudioDeviceControlResolver.Result.Unresolved,
            CurrentAudioDeviceControlResolver.Result.None -> {
                if (boundStableId != null || manager.snapshot.value.device != null) {
                    boundStableId = null
                    manager.bind(null)
                }
            }
        }
    }
}
