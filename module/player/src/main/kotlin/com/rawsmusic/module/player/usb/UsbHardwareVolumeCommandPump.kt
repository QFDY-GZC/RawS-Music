package com.rawsmusic.module.player.usb

import android.hardware.usb.UsbDevice
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serializes USB Feature Unit writes independently from volume mapping and restore logic. */
internal class UsbHardwareVolumeCommandPump(
    scope: CoroutineScope,
    private val engine: UsbAudioEngine,
    private val transportMutex: Mutex,
    private val isReleased: () -> Boolean,
    private val isTransportTransitioning: () -> Boolean,
    private val isRecovering: () -> Boolean,
    private val currentDevice: () -> UsbDevice?,
    private val isHardwareRouteActive: () -> Boolean,
    private val shouldAccept: (UsbHardwareVolumeCommand) -> Boolean,
    private val execute: (UsbHardwareVolumeCommand) -> Int,
) {
    private val commands = Channel<UsbHardwareVolumeCommand>(Channel.CONFLATED)

    private val commandJob: Job = scope.launch(Dispatchers.IO) {
        for (first in commands) {
            var command = first
            delay(24L)
            while (true) {
                val newer = commands.tryReceive().getOrNull() ?: break
                command = newer
            }
            while ((isTransportTransitioning() || isRecovering()) && isActive) {
                delay(24L)
                while (true) {
                    val newer = commands.tryReceive().getOrNull() ?: break
                    command = newer
                }
            }
            if (isReleased() || !isActive) continue

            val device = currentDevice() ?: continue
            if (UsbHardwareVolumeStore.deviceKey(device) != command.deviceKey) {
                AppLogger.w(TAG, "Discard stale USB hardware-volume command reason=${command.reason}")
                continue
            }
            if (!isHardwareRouteActive()) {
                AppLogger.i(TAG, "Skip USB hardware-volume command after route change reason=${command.reason}")
                continue
            }
            if (!shouldAccept(command)) continue

            val result = transportMutex.withLock {
                AppLogger.i(
                    TAG,
                    "HW_VOL_TRACE command type=${if (command.adjustDirection != 0) "step" else "slider"} " +
                        "direction=${command.adjustDirection} ui=${command.uiVolume} " +
                        "reason=${command.reason} handle=0x${java.lang.Long.toUnsignedString(engine.currentHandle, 16)}",
                )
                if (isTransportTransitioning() || isRecovering() || !isHardwareRouteActive()) {
                    AppLogger.i(TAG, "Skip USB hardware-volume command at transport boundary reason=${command.reason}")
                    UsbAudioEngine.ERR_NOT_INITIALIZED
                } else {
                    execute(command)
                }
            }
            if (result != 0 && result != UsbAudioEngine.ERR_NOT_INITIALIZED) {
                AppLogger.e(
                    TAG,
                    "USB hardware-volume command failed result=$result ui=${command.uiVolume} " +
                        "reason=${command.reason}",
                )
            }
            AppLogger.i(TAG, "HW_VOL_TRACE command_result result=$result reason=${command.reason}")
        }
    }

    fun enqueue(command: UsbHardwareVolumeCommand): Boolean =
        commands.trySend(command).isSuccess

    fun clearPending() {
        while (commands.tryReceive().isSuccess) Unit
    }

    fun close() {
        commands.close()
        commandJob.cancel()
    }

    companion object {
        private const val TAG = "UsbHardwareVolumeCommandPump"
    }
}
