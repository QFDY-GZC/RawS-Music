package com.rawsmusic.module.player.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Owns the USB Feature Unit volume runtime.
 *
 * The controller decides which volume route is active; this class owns the device-scoped raw
 * volume, serialized control lane, reconnect restore and the safety hold around transport changes.
 */
internal class UsbHardwareVolumeCoordinator(
    private val context: Context,
    private val engine: UsbAudioEngine,
    private val scope: CoroutineScope,
    private val transportMutex: Mutex,
    private val isReleased: () -> Boolean,
    private val isTransportTransitioning: () -> Boolean,
    private val isRecovering: () -> Boolean,
    private val currentDevice: () -> UsbDevice?,
    private val isHardwareRouteActive: () -> Boolean,
) {
    private val commands = Channel<UsbHardwareVolumeCommand>(Channel.CONFLATED)

    @Volatile
    private var safeCommandHoldUntilMs = 0L

    @Volatile
    private var initializedHandle = 0L

    @Volatile
    private var initializedDeviceKey: String? = null

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
            if (command.reason.startsWith("system_volume_changed")) {
                AppLogger.w(
                    TAG,
                    "Discard system-volume bridge command in USB hardware route " +
                        "reason=${command.reason} ui=${command.uiVolume}",
                )
                continue
            }

            val holdUntil = safeCommandHoldUntilMs
            if (!command.userInitiated &&
                (holdUntil == Long.MAX_VALUE || SystemClock.elapsedRealtime() < holdUntil)
            ) {
                AppLogger.w(
                    TAG,
                    "Discard automatic USB hardware-volume command during safety hold " +
                        "reason=${command.reason} holdUntil=$holdUntil",
                )
                continue
            }

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
                } else if (command.adjustDirection != 0) {
                    adjustNativeAndPersist(command.adjustDirection, command.reason)
                } else {
                    setUiAndPersist(command.uiVolume, command.reason)
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

    fun clearPendingCommands() {
        while (commands.tryReceive().isSuccess) Unit
    }

    fun resetInitialization() {
        initializedHandle = 0L
        initializedDeviceKey = null
        safeCommandHoldUntilMs = 0L
        clearPendingCommands()
    }

    fun prepareForAuthorization() {
        resetInitialization()
        safeCommandHoldUntilMs = Long.MAX_VALUE
    }

    fun close() {
        commands.close()
        commandJob.cancel()
    }

    fun currentStep(): Int {
        val persistedStep = AppPreferences.Player.usbHardwareVolumeStep
            .coerceIn(0, UsbHardwareVolumeMath.MAX_STEP)
        val persistedLinear = AppPreferences.Player.usbHardwareVolume.coerceIn(0f, 1f)
        val linearStep = UsbHardwareVolumeMath.uiToStep(persistedLinear)
        val persistedUi = UsbHardwareVolumeMath.stepToUi(persistedStep)
        val legacyDefaultStep = persistedStep == 25
        val appearsOutOfSync = kotlin.math.abs(persistedUi - persistedLinear) > 0.20f
        val shouldPreferLinear =
            (legacyDefaultStep && persistedLinear > 0.45f) ||
                (appearsOutOfSync && persistedLinear > 0.10f)
        if (!shouldPreferLinear) return persistedStep

        AppPreferences.Player.usbHardwareVolumeStep = linearStep
        AppLogger.w(
            TAG,
            "Reconciled USB HW volume pref: oldStep=$persistedStep oldUi=$persistedUi " +
                "storedLinear=$persistedLinear newStep=$linearStep",
        )
        return linearStep
    }

    fun nextStepFromDevice(direction: Int, reason: String): Int {
        val handle = engine.currentHandle
        if (handle == 0L || !isHardwareRouteActive() || !engine.nativeCanControlVolume(handle)) {
            return UsbHardwareVolumeMath.clampStep(currentStep() + direction.sign())
        }
        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val resRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        val currentRaw = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        if (minRaw >= maxRaw || currentRaw !in minRaw..maxRaw) {
            return UsbHardwareVolumeMath.clampStep(currentStep() + direction.sign())
        }

        val alignedCurrent = UsbHardwareVolumeMath.quantizeRaw(currentRaw, minRaw, maxRaw, resRaw)
        val targetRaw = (alignedCurrent + direction.sign() * resRaw).coerceIn(minRaw, maxRaw)
        val currentStep = syncPreferencesFromRaw(
            alignedCurrent,
            minRaw,
            maxRaw,
            "read_before_adjust:$reason",
        )
        val targetStep = UsbHardwareVolumeMath.uiToStep(
            UsbHardwareVolumeMath.rawToUi(targetRaw, minRaw, maxRaw),
        )
        AppLogger.i(
            TAG,
            "USB HW adjust from device raw: direction=$direction currentRaw=$alignedCurrent " +
                "targetRaw=$targetRaw res=$resRaw currentStep=$currentStep targetStep=$targetStep reason=$reason",
        )
        return targetStep
    }

    fun enqueueAdjustment(direction: Int, reason: String): Int {
        val device = currentDevice() ?: return UsbAudioEngine.ERR_NOT_INITIALIZED
        val handle = engine.currentHandle
        if (handle == 0L) return UsbAudioEngine.ERR_NOT_INITIALIZED
        if (!engine.nativeCanControlVolume(handle)) return -2
        val command = UsbHardwareVolumeCommand(
            deviceKey = UsbHardwareVolumeStore.deviceKey(device),
            uiVolume = AppPreferences.Player.usbHardwareVolume,
            reason = reason,
            userInitiated = true,
            adjustDirection = direction.sign(),
        )
        val accepted = commands.trySend(command).isSuccess
        AppLogger.i(
            TAG,
            "USB HW native step queued: direction=${command.adjustDirection} accepted=$accepted reason=$reason",
        )
        return if (accepted) 0 else -3
    }

    fun setStep(step: Int, reason: String): Int {
        val boundedStep = UsbHardwareVolumeMath.clampStep(step)
        val db = UsbHardwareVolumeMath.stepToDb(boundedStep)
        val uiVolume = UsbHardwareVolumeMath.stepToUi(boundedStep)
        if (reason.startsWith("system_volume_changed") && isHardwareRouteActive()) {
            AppLogger.w(TAG, "Ignore system-volume bridge in USB hardware route: reason=$reason ui=$uiVolume")
            return 0
        }

        AppPreferences.Player.usbHardwareVolumeStep = boundedStep
        AppPreferences.Player.usbHardwareVolume = uiVolume
        if (isHardwareRouteActive()) AppPreferences.Player.volume = uiVolume

        val device = currentDevice()
        val handle = engine.currentHandle
        if (device == null || handle == 0L) {
            AppLogger.w(TAG, "setUsbHardwareVolumeStep: target saved but no live device/handle " +
                "step=$boundedStep db=$db reason=$reason")
            return UsbAudioEngine.ERR_NOT_INITIALIZED
        }
        if (!engine.nativeCanControlVolume(handle)) {
            AppLogger.w(TAG, "setUsbHardwareVolumeStep: native cannot control volume step=$boundedStep db=$db reason=$reason")
            return -2
        }

        val command = UsbHardwareVolumeCommand(
            deviceKey = UsbHardwareVolumeStore.deviceKey(device),
            uiVolume = uiVolume,
            reason = reason,
            userInitiated = reason == "setUserVolume" ||
                reason.startsWith("ui_button") ||
                reason.startsWith("media_session"),
        )
        val accepted = commands.trySend(command).isSuccess
        AppLogger.i(
            TAG,
            "USB HW volume command queued: step=$boundedStep nominalDb=${db}dB ui=$uiVolume " +
                "accepted=$accepted reason=$reason",
        )
        return if (accepted) 0 else -3
    }

    fun initializeForHandle(device: UsbDevice, reason: String): Boolean {
        val handle = engine.currentHandle
        val deviceKey = UsbHardwareVolumeStore.deviceKey(device)
        if (handle == 0L) {
            AppLogger.e(TAG, "Hardware volume init rejected: no native handle reason=$reason")
            return false
        }
        if (!engine.nativeCanControlVolume(handle)) {
            AppLogger.e(TAG, "Hardware volume init rejected: controller unavailable reason=$reason")
            return false
        }

        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val resRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        if (minRaw >= maxRaw) {
            AppLogger.e(TAG, "Hardware volume init invalid range=$minRaw..$maxRaw reason=$reason")
            return false
        }

        val currentRawBeforeInit = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        val excessiveThreshold = maxRaw - resRaw
        if (currentRawBeforeInit != Int.MIN_VALUE && currentRawBeforeInit >= excessiveThreshold) {
            AppLogger.w(TAG, "DAC hardware volume is at/near maximum before initialization: " +
                "current=$currentRawBeforeInit max=$maxRaw res=$resRaw reason=$reason")
        }

        val stored = UsbHardwareVolumeStore.read(device)?.takeIf { it.isCompatible(minRaw, maxRaw) }
        val hardwareResetToMaximum = currentRawBeforeInit != Int.MIN_VALUE &&
            currentRawBeforeInit >= excessiveThreshold && (stored?.raw?.let { it < excessiveThreshold } ?: true)

        // A native handle is a new Feature Unit session even when the physical DAC key is
        // unchanged. Do not reuse the old-session decision: the device may have reset its
        // hardware volume while the application was rebuilding the stream.
        if (initializedDeviceKey == deviceKey && initializedHandle == handle && !hardwareResetToMaximum) {
            if (currentRawBeforeInit != Int.MIN_VALUE) {
                val expectedRaw = stored?.raw
                val allowedDelta = (resRaw * 2).coerceAtLeast(256)
                val unsafeWithoutExpected = expectedRaw == null && currentRawBeforeInit >= excessiveThreshold
                val unexpected = expectedRaw != null &&
                    kotlin.math.abs(currentRawBeforeInit - expectedRaw) > allowedDelta
                if (unsafeWithoutExpected || unexpected) {
                    AppLogger.e(TAG, "Same-DAC handle rebuild detected unsafe hardware-volume reset; " +
                        "refuse ISO start without SET_CUR: current=$currentRawBeforeInit expected=$expectedRaw " +
                        "max=$maxRaw res=$resRaw reason=$reason")
                    return false
                }
                if (currentRawBeforeInit in minRaw..maxRaw) {
                    syncPreferencesFromRaw(currentRawBeforeInit, minRaw, maxRaw, "same_dac_handle_rebuild:$reason")
                }
            }
            initializedHandle = handle
            AppLogger.i(TAG, "Reused initialized DAC hardware-volume session without Feature Unit write: " +
                "deviceKey=$deviceKey currentRaw=$currentRawBeforeInit reason=$reason")
            return true
        }

        if (hardwareResetToMaximum && initializedDeviceKey == deviceKey) {
            AppLogger.w(TAG, "Same-DAC handle rebuild reports a maximum hardware volume; " +
                "reapplying the safety-capped value before ISO start: current=$currentRawBeforeInit " +
                "max=$maxRaw res=$resRaw reason=$reason")
        }

        val safeRaw = if (stored == null) {
            UsbHardwareVolumeMath.conservativeSafeRaw(minRaw, maxRaw, resRaw) ?: run {
                AppLogger.e(TAG, "DAC has no automatically safe <= -30dB hardware step; " +
                    "keep software volume: range=$minRaw..$maxRaw res=$resRaw reason=$reason")
                return false
            }
        } else null
        val targetBaseRaw = stored?.raw ?: safeRaw ?: return false
        val targetRaw = UsbHardwareVolumeMath.quantizeRaw(targetBaseRaw, minRaw, maxRaw, resRaw)
        val targetReason = if (stored != null) "device_restore:$reason" else "new_device_safe:$reason"
        if (stored != null) {
            AppLogger.i(TAG, "Restoring device hardware raw=${stored.raw} range=$minRaw..$maxRaw reason=$reason")
        } else {
            AppLogger.w(TAG, "No compatible device hardware volume history; applying -30dB initial safety " +
                "before ISO start reason=$reason")
        }

        UsbHardwareVolumeStore.markSessionActive(context, device)
        // Always use the uncached write for a fresh native handle. The per-handle cache is not
        // authoritative after reconnect/re-authorization and can otherwise leave the DAC at 0 dB.
        val result = engine.nativeSetHardwareVolumeRawNoCache(
            handle,
            targetRaw,
            if (hardwareResetToMaximum) "reattach_force:$targetReason" else "init_direct:$targetReason",
        )
        if (result != 0) {
            AppLogger.e(TAG, "Hardware volume initialization write failed result=$result reason=$reason")
            return false
        }

        val readbackRaw = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        if (readbackRaw != Int.MIN_VALUE) {
            val allowedDelta = (resRaw * 2).coerceAtLeast(256)
            if (kotlin.math.abs(readbackRaw - targetRaw) > allowedDelta) {
                AppLogger.e(TAG, "Hardware volume initialization readback mismatch; refuse ISO start: " +
                    "target=$targetRaw readback=$readbackRaw allowed=$allowedDelta reason=$reason")
                return false
            }
        }
        val persistedRaw = readbackRaw.takeIf { it in minRaw..maxRaw } ?: targetRaw
        UsbHardwareVolumeStore.write(device, persistedRaw, minRaw, maxRaw, resRaw, "initialize:$reason")
        syncPreferencesFromRaw(persistedRaw, minRaw, maxRaw, "initialize:$reason")
        initializedHandle = handle
        initializedDeviceKey = deviceKey
        clearPendingCommands()
        safeCommandHoldUntilMs = SystemClock.elapsedRealtime() + 3_000L
        AppLogger.i(TAG, "Hardware-volume initialization completed at raw=$persistedRaw; commandHoldMs=3000")
        return true
    }

    fun setUiAndPersist(uiVolume: Float, reason: String): Int {
        val handle = engine.currentHandle
        if (handle == 0L) return UsbAudioEngine.ERR_NOT_INITIALIZED
        if (!engine.nativeCanControlVolume(handle)) return -2
        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val resRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        if (minRaw >= maxRaw) return -3
        val targetRaw = UsbHardwareVolumeMath.uiToRaw(uiVolume, minRaw, maxRaw, resRaw)
        AppLogger.i(
            TAG,
            "HW_VOL_TRACE slider_input ui=$uiVolume targetRaw=$targetRaw " +
                "range=$minRaw..$maxRaw res=$resRaw reason=$reason",
        )
        // A DAC may reset or ignore its Feature Unit value while the native
        // cache still contains the requested value. User writes must always
        // reach the device instead of being removed by that stale cache.
        val result = engine.nativeSetHardwareVolumeRawNoCache(handle, targetRaw, "explicit_user:$reason")
        if (result == 0) {
            val readback = verifyHardwareWrite(targetRaw, minRaw, maxRaw, resRaw, reason)
            if (readback == Int.MIN_VALUE) return UsbAudioEngine.ERR_HARDWARE_VOLUME_WRITE_UNCONFIRMED
            currentDevice()?.let { device ->
                UsbHardwareVolumeStore.write(device, readback, minRaw, maxRaw, resRaw, reason)
                syncPreferencesFromRaw(readback, minRaw, maxRaw, "explicit_user:$reason")
            }
        }
        return result
    }

    private fun verifyHardwareWrite(
        targetRaw: Int,
        minRaw: Int,
        maxRaw: Int,
        resRaw: Int,
        reason: String,
    ): Int {
        var readback = Int.MIN_VALUE
        repeat(5) { attempt ->
            if (attempt > 0) Thread.sleep(8L shl (attempt - 1))
            readback = engine.nativeGetHardwareVolumeCurrentRaw(engine.currentHandle)
            val matches = readback in minRaw..maxRaw &&
                kotlin.math.abs(readback - targetRaw) <= (resRaw * 2).coerceAtLeast(256)
            AppLogger.i(
                TAG,
                "HW_VOL_TRACE slider_verify attempt=${attempt + 1} readback=$readback " +
                    "target=$targetRaw matches=$matches reason=$reason",
            )
            if (matches) return readback
        }
        AppLogger.e(
            TAG,
            "HW_VOL_TRACE slider_unconfirmed target=$targetRaw readback=$readback " +
                "reason=$reason; keeping hardware route",
        )
        return Int.MIN_VALUE
    }

    fun adjustNativeAndPersist(direction: Int, reason: String): Int {
        val handle = engine.currentHandle
        val device = currentDevice() ?: return UsbAudioEngine.ERR_NOT_INITIALIZED
        if (handle == 0L) return UsbAudioEngine.ERR_NOT_INITIALIZED
        if (!engine.nativeCanControlVolume(handle)) return -2
        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val deviceResRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        val rawBefore = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        if (minRaw >= maxRaw || rawBefore !in minRaw..maxRaw) {
            AppLogger.w(
                TAG,
                "HW_VOL_TRACE step_rejected invalid current raw=$rawBefore " +
                    "range=$minRaw..$maxRaw res=$deviceResRaw reason=$reason",
            )
            return UsbAudioEngine.ERR_HARDWARE_VOLUME_WRITE_UNCONFIRMED
        }

        // Keep the old user-facing hardware step: 1 dB = 256 raw units. Some DACs report a
        // finer 0.5 dB Feature Unit resolution, but accepting that reported resolution here
        // makes the volume-key path asymmetric: one direction may be ignored while sliders,
        // which write a full application step, still work.
        val appStepRaw = APP_STEP_RAW
        val targetRaw = UsbHardwareVolumeMath.quantizeRaw(
            raw = rawBefore + direction.sign() * appStepRaw,
            minRaw = minRaw,
            maxRaw = maxRaw,
            resRaw = deviceResRaw,
        )
        AppLogger.i(
            TAG,
            "HW_VOL_TRACE step_input direction=${direction.sign()} rawBefore=$rawBefore " +
                "targetRaw=$targetRaw appStepRaw=$appStepRaw deviceResRaw=$deviceResRaw " +
                "range=$minRaw..$maxRaw reason=$reason",
        )

        if (targetRaw == rawBefore) {
            AppLogger.i(TAG, "HW_VOL_TRACE step_at_boundary raw=$rawBefore direction=${direction.sign()} reason=$reason")
            return 0
        }

        val result = engine.nativeSetHardwareVolumeRawNoCache(
            handle,
            targetRaw,
            "step_direct:$reason",
        )
        if (result != 0) {
            AppLogger.w(
                TAG,
                "USB hardware step SET_CUR failed result=$result direction=${direction.sign()} " +
                    "targetRaw=$targetRaw reason=$reason",
            )
            return result
        }

        val readback = verifyHardwareWrite(targetRaw, minRaw, maxRaw, deviceResRaw, "step:$reason")
        if (readback == Int.MIN_VALUE || readback == rawBefore) {
            AppLogger.e(
                TAG,
                "HW_VOL_TRACE step_unconfirmed before=$rawBefore target=$targetRaw " +
                    "readback=$readback reason=$reason; hardware route kept",
            )
            return UsbAudioEngine.ERR_HARDWARE_VOLUME_WRITE_UNCONFIRMED
        }

        UsbHardwareVolumeStore.write(device, readback, minRaw, maxRaw, deviceResRaw, reason)
        syncPreferencesFromRaw(readback, minRaw, maxRaw, "direct_step:$reason")
        AppLogger.i(
            TAG,
            "USB HW direct step applied: direction=${direction.sign()} raw=$readback " +
                "db=${readback / 256.0f} reason=$reason",
        )
        return 0
    }

    fun readDisplayedStep(reason: String): Int? {
        val handle = engine.currentHandle
        if (handle == 0L || !isHardwareRouteActive() || !engine.nativeCanControlVolume(handle)) return null
        val raw = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        if (raw == Int.MIN_VALUE || minRaw >= maxRaw || raw !in minRaw..maxRaw) {
            AppLogger.w(TAG, "readDisplayedUsbHardwareVolumeStep fallback to preference: " +
                "raw=$raw range=$minRaw..$maxRaw reason=$reason")
            return currentStep()
        }
        return syncPreferencesFromRaw(raw, minRaw, maxRaw, reason)
    }

    fun syncPreferencesFromRawForController(
        raw: Int,
        minRaw: Int,
        maxRaw: Int,
        reason: String,
    ): Int = syncPreferencesFromRaw(raw, minRaw, maxRaw, reason)

    fun seedStepFromUiVolume(): Int {
        readDisplayedStep("seed_remote_volume")?.let { return it }
        val persistedLinear = AppPreferences.Player.usbHardwareVolume.coerceIn(0f, 1f)
        val fallbackLinear = if (persistedLinear > 0.0001f) persistedLinear else AppPreferences.Player.volume.coerceIn(0f, 1f)
        val step = UsbHardwareVolumeMath.uiToStep(fallbackLinear)
        AppPreferences.Player.usbHardwareVolumeStep = step
        AppPreferences.Player.usbHardwareVolume = fallbackLinear
        AppLogger.i(TAG, "seedUsbHardwareVolumeStepFromUiVolume: fallbackLinear=$fallbackLinear step=$step")
        return step
    }

    fun getVolumeDb(): Float {
        val handle = engine.currentHandle
        if (handle == 0L || !isHardwareRouteActive()) return 0f
        val raw = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        return if (raw == Int.MIN_VALUE) 0f else raw / 256.0f
    }

    fun canControl(): Boolean {
        val handle = engine.currentHandle
        return handle != 0L && runCatching { engine.nativeCanControlVolume(handle) }.getOrDefault(false)
    }

    private fun syncPreferencesFromRaw(raw: Int, minRaw: Int, maxRaw: Int, reason: String): Int {
        val uiVolume = UsbHardwareVolumeMath.rawToUi(raw, minRaw, maxRaw)
        val step = UsbHardwareVolumeMath.uiToStep(uiVolume)
        AppPreferences.Player.usbHardwareVolumeStep = step
        AppPreferences.Player.usbHardwareVolume = uiVolume
        if (isHardwareRouteActive()) AppPreferences.Player.volume = uiVolume
        AppLogger.i(TAG, "Synced USB hardware UI from device raw: raw=$raw range=$minRaw..$maxRaw " +
            "ui=$uiVolume step=$step reason=$reason")
        return step
    }

    private fun Int.sign(): Int = if (this > 0) 1 else -1

    companion object {
        private const val TAG = "UsbHardwareVolumeCoordinator"
        private const val APP_STEP_RAW = 256
    }
}
