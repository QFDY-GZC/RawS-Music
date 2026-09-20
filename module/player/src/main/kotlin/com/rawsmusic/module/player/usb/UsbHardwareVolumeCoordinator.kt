package com.rawsmusic.module.player.usb

import android.content.Context
import android.hardware.usb.UsbDevice
import android.os.SystemClock
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
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
    data class LiveVolumeSnapshot(
        val handle: Long,
        val raw: Int,
        val minRaw: Int,
        val maxRaw: Int,
        val resolutionRaw: Int,
    ) {
        val db: Float get() = raw / 256.0f
    }

    private data class BoundaryFadeState(
        val handle: Long,
        val userRaw: Int,
        val floorRaw: Int,
        val deviceMinRaw: Int,
        val deviceMaxRaw: Int,
        val resolutionRaw: Int,
        val durationMs: Int,
        val generation: Long,
    )

    @Volatile
    private var safeCommandHoldUntilMs = 0L

    @Volatile
    private var initializedHandle = 0L

    @Volatile
    private var initializedDeviceKey: String? = null

    private val boundaryFadeLock = Any()

    @Volatile
    private var boundaryFadeGeneration = 0L

    @Volatile
    private var boundaryFadeState: BoundaryFadeState? = null

    private var boundaryRestoreJob: Job? = null

    private val commandPump = UsbHardwareVolumeCommandPump(
        scope = scope,
        engine = engine,
        transportMutex = transportMutex,
        isReleased = isReleased,
        isTransportTransitioning = isTransportTransitioning,
        isRecovering = isRecovering,
        currentDevice = currentDevice,
        isHardwareRouteActive = isHardwareRouteActive,
        shouldAccept = { command ->
            if (command.reason.startsWith("system_volume_changed")) {
                AppLogger.w(
                    TAG,
                    "Discard system-volume bridge command in USB hardware route " +
                        "reason=${command.reason} ui=${command.uiVolume}",
                )
                false
            } else {
                val holdUntil = safeCommandHoldUntilMs
                if (!command.userInitiated &&
                    (holdUntil == Long.MAX_VALUE || SystemClock.elapsedRealtime() < holdUntil)
                ) {
                    AppLogger.w(
                        TAG,
                        "Discard automatic USB hardware-volume command during safety hold " +
                            "reason=${command.reason} holdUntil=$holdUntil",
                    )
                    false
                } else {
                    true
                }
            }
        },
        execute = { command ->
            if (command.adjustDirection != 0) {
                adjustNativeAndPersist(command.adjustDirection, command.reason)
            } else {
                setUiAndPersist(command.uiVolume, command.reason)
            }
        },
    )

    fun clearPendingCommands() {
        commandPump.clearPending()
    }

    fun resetInitialization() {
        cancelBoundaryFade("reset_initialization")
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
        cancelBoundaryFade("close")
        commandPump.close()
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

    fun enqueueAdjustment(direction: Int, reason: String): Int {
        cancelBoundaryFade("user_adjust:$reason")
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
        val accepted = commandPump.enqueue(command)
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
        AppLogger.i(TAG, "USB HW step input: step=$boundedStep nominalDb=${db}dB ui=$uiVolume reason=$reason")
        return setUiVolume(uiVolume, reason, updateLegacyStep = false).also {
            AppPreferences.Player.usbHardwareVolumeStep = boundedStep
        }
    }

    /**
     * Direct normalized hardware-volume path. Unlike setStep(), this keeps the
     * caller's 0..1 value intact until it is mapped into the DAC-reported raw
     * MIN..MAX range by setUiAndPersist().
     */
    fun setUiVolume(uiVolume: Float, reason: String): Int {
        return setUiVolume(uiVolume, reason, updateLegacyStep = true)
    }

    private fun setUiVolume(uiVolume: Float, reason: String, updateLegacyStep: Boolean): Int {
        if (
            reason == "setUserVolume" ||
            reason.startsWith("ui_button") ||
            reason.startsWith("media_session")
        ) {
            cancelBoundaryFade("user_set:$reason")
        }
        val normalized = uiVolume.coerceIn(0f, 1f)
        if (reason.startsWith("system_volume_changed") && isHardwareRouteActive()) {
            AppLogger.w(TAG, "Ignore system-volume bridge in USB hardware route: reason=$reason ui=$normalized")
            return 0
        }

        if (updateLegacyStep) {
            AppPreferences.Player.usbHardwareVolumeStep = UsbHardwareVolumeMath.uiToStep(normalized)
        }
        AppPreferences.Player.usbHardwareVolume = normalized
        if (isHardwareRouteActive()) AppPreferences.Player.volume = normalized

        val device = currentDevice()
        val handle = engine.currentHandle
        if (device == null || handle == 0L) {
            AppLogger.w(TAG, "setUsbHardwareVolume: target saved but no live device/handle " +
                "ui=$normalized reason=$reason")
            return UsbAudioEngine.ERR_NOT_INITIALIZED
        }
        if (!engine.nativeCanControlVolume(handle)) {
            AppLogger.w(TAG, "setUsbHardwareVolume: native cannot control volume ui=$normalized reason=$reason")
            return -2
        }

        val command = UsbHardwareVolumeCommand(
            deviceKey = UsbHardwareVolumeStore.deviceKey(device),
            uiVolume = normalized,
            reason = reason,
            userInitiated = reason == "setUserVolume" ||
                reason.startsWith("ui_button") ||
                reason.startsWith("media_session"),
        )
        val accepted = commandPump.enqueue(command)
        AppLogger.i(TAG, "USB HW volume command queued: ui=$normalized accepted=$accepted reason=$reason")
        return if (accepted) 0 else -3
    }

    /**
     * Best-effort Feature Unit attenuation around one track boundary.
     *
     * Native exposes this only for validated UAC2 master-volume routes with asynchronous SET_CUR.
     * Temporary raw values are never persisted as the user's volume. The USB transport-silence
     * barrier remains authoritative; this layer only lowers DAC output while that cut is crossed.
     */
    suspend fun fadeOutForTrackBoundary(durationMs: Int, reason: String): Boolean {
        val requestedDuration = durationMs.coerceAtLeast(0)
        if (requestedDuration <= 0 || !isHardwareRouteActive()) return false
        val handle = engine.currentHandle
        if (handle == 0L || !engine.nativeCanUseHardwareBoundaryFade(handle)) return false

        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val resolutionRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        if (minRaw >= maxRaw) return false

        val storedRaw = currentDevice()
            ?.let { UsbHardwareVolumeStore.read(it) }
            ?.raw
            ?.takeIf { it in minRaw..maxRaw }
        val currentRaw = engine.nativeGetHardwareVolumeCurrentRaw(handle)
            .takeIf { it in minRaw..maxRaw }
            ?: storedRaw
            ?: return false

        val desiredFloor = (currentRaw - BOUNDARY_ATTENUATION_DB * 256).coerceAtLeast(minRaw)
        val floorRaw = UsbHardwareVolumeMath.quantizeRaw(
            desiredFloor,
            minRaw,
            maxRaw,
            resolutionRaw,
        )
        if (floorRaw >= currentRaw - resolutionRaw) return false

        val boundedDuration = requestedDuration.coerceIn(MIN_BOUNDARY_FADE_MS, MAX_BOUNDARY_FADE_MS)
        val generation = synchronized(boundaryFadeLock) {
            boundaryRestoreJob?.cancel()
            boundaryRestoreJob = null
            boundaryFadeGeneration += 1L
            val nextGeneration = boundaryFadeGeneration
            boundaryFadeState = BoundaryFadeState(
                handle = handle,
                userRaw = currentRaw,
                floorRaw = floorRaw,
                deviceMinRaw = minRaw,
                deviceMaxRaw = maxRaw,
                resolutionRaw = resolutionRaw,
                durationMs = boundedDuration,
                generation = nextGeneration,
            )
            nextGeneration
        }

        // Manual track switching already runs inside PlayerController's transportMutex.
        // Kotlin Mutex is non-reentrant: trying to lock it again here deadlocks the PLAY event
        // after the UI has already previewed the target song. That leaves the old decoder audible
        // and permanently blocks every later PLAY command (player page, MiniPlayer and HOME
        // carousel all share the same serialized event lane). Keep the outgoing hardware ramp on
        // the caller-owned transport transaction; the asynchronous restore below still acquires
        // transportMutex on its own after the new-track boundary has been committed.
        val completed = runHardwareRamp(
            handle = handle,
            fromRaw = currentRaw,
            toRaw = floorRaw,
            deviceMinRaw = minRaw,
            deviceMaxRaw = maxRaw,
            resolutionRaw = resolutionRaw,
            durationMs = boundedDuration,
            generation = generation,
            reason = "track_out:$reason",
        )
        if (!completed) {
            synchronized(boundaryFadeLock) {
                if (boundaryFadeState?.generation == generation) boundaryFadeState = null
            }
            // A BUSY result means the previous async SET_CUR callback missed the tight boundary
            // deadline. Let that callback retire, then restore the exact user raw with the normal
            // verified transaction so a failed transition can never strand the DAC attenuated.
            delay(BOUNDARY_ABORT_DRAIN_MS)
            runCatching {
                engine.setHardwareVolumeRawVerified(
                    handle = handle,
                    raw = currentRaw,
                    reason = "boundary_abort_restore:$reason",
                )
            }
            return false
        }

        // One event-loop turn lets the final async SET_CUR reach the DAC before the PCM cut.
        delay(BOUNDARY_SETTLE_MS)
        AppLogger.i(
            TAG,
            "USB HW boundary fade-out complete handle=0x${handle.toString(16)} " +
                "user=${currentRaw / 256.0f}dB floor=${floorRaw / 256.0f}dB " +
                "durationMs=$boundedDuration generation=$generation reason=$reason",
        )
        return true
    }

    /** Restore the user's hardware volume after the next track begins feeding the live stream. */
    fun restoreAfterTrackBoundary(reason: String) {
        val state = boundaryFadeState ?: return
        synchronized(boundaryFadeLock) {
            if (boundaryFadeState?.generation != state.generation) return
            boundaryRestoreJob?.cancel()
            boundaryRestoreJob = scope.launch(Dispatchers.IO) {
                try {
                    if (
                        engine.currentHandle != state.handle ||
                        !isHardwareRouteActive() ||
                        !engine.nativeCanUseHardwareBoundaryFade(state.handle)
                    ) {
                        AppLogger.i(
                            TAG,
                            "USB HW boundary restore skipped after session change " +
                                "oldHandle=0x${state.handle.toString(16)} " +
                                "newHandle=0x${engine.currentHandle.toString(16)} reason=$reason",
                        )
                        return@launch
                    }

                    delay(BOUNDARY_SETTLE_MS)
                    val restored = transportMutex.withLock {
                        runHardwareRamp(
                            handle = state.handle,
                            fromRaw = state.floorRaw,
                            toRaw = state.userRaw,
                            deviceMinRaw = state.deviceMinRaw,
                            deviceMaxRaw = state.deviceMaxRaw,
                            resolutionRaw = state.resolutionRaw,
                            durationMs = state.durationMs,
                            generation = state.generation,
                            reason = "track_in:$reason",
                        )
                    }
                    if (!restored) return@launch

                    // Intermediate fade points are fire-and-forget async writes. Verify only the
                    // final restored user value, keeping readback traffic out of the ramp itself.
                    val verified = engine.setHardwareVolumeRawVerified(
                        handle = state.handle,
                        raw = state.userRaw,
                        reason = "boundary_restore_verify:$reason",
                    )
                    AppLogger.i(
                        TAG,
                        "USB HW boundary fade-in complete verified=${verified.confirmed} " +
                            "observed=${verified.observedRaw} user=${state.userRaw / 256.0f}dB " +
                            "generation=${state.generation} reason=$reason",
                    )
                } finally {
                    synchronized(boundaryFadeLock) {
                        if (boundaryFadeState?.generation == state.generation) {
                            boundaryFadeState = null
                            boundaryRestoreJob = null
                        }
                    }
                }
            }
        }
    }

    private suspend fun runHardwareRamp(
        handle: Long,
        fromRaw: Int,
        toRaw: Int,
        deviceMinRaw: Int,
        deviceMaxRaw: Int,
        resolutionRaw: Int,
        durationMs: Int,
        generation: Long,
        reason: String,
    ): Boolean {
        val pointCount = (durationMs / TARGET_BOUNDARY_POINT_MS)
            .coerceIn(MIN_BOUNDARY_POINTS, MAX_BOUNDARY_POINTS)
        val intervalMs = (durationMs / pointCount).coerceAtLeast(MIN_BOUNDARY_POINT_INTERVAL_MS)
        var lastRaw = fromRaw
        for (point in 1..pointCount) {
            if (boundaryFadeGeneration != generation || engine.currentHandle != handle) return false
            val fraction = point.toDouble() / pointCount.toDouble()
            val requested = (fromRaw + (toRaw - fromRaw) * fraction).toInt()
            val targetRaw = UsbHardwareVolumeMath.quantizeRaw(
                requested,
                deviceMinRaw,
                deviceMaxRaw,
                resolutionRaw,
            )
            if (targetRaw != lastRaw) {
                val result = engine.nativeSetHardwareBoundaryVolumeRaw(
                    handle,
                    targetRaw,
                    "boundary_ramp:$reason:$point/$pointCount",
                )
                if (result != 0) {
                    AppLogger.w(
                        TAG,
                        "USB HW boundary ramp write failed result=$result point=$point/$pointCount " +
                            "target=$targetRaw generation=$generation reason=$reason",
                    )
                    return false
                }
                lastRaw = targetRaw
            }
            if (point < pointCount) delay(intervalMs.toLong())
        }
        return true
    }

    private fun cancelBoundaryFade(reason: String) {
        val oldState = synchronized(boundaryFadeLock) {
            boundaryFadeGeneration += 1L
            boundaryRestoreJob?.cancel()
            boundaryRestoreJob = null
            boundaryFadeState.also { boundaryFadeState = null }
        }
        if (oldState != null) {
            AppLogger.i(
                TAG,
                "USB HW boundary fade cancelled generation=${oldState.generation} reason=$reason",
            )
        }
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
                AppLogger.e(TAG, "DAC has no automatically safe <= -32dB hardware step; " +
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
            AppLogger.w(TAG, "No compatible device hardware volume history; applying -32dB initial safety " +
                "before ISO start reason=$reason")
        }

        UsbHardwareVolumeStore.markSessionActive(context, device)
        // Native owns SET_CUR + readback sequencing. Before nativeStart its async gate is closed,
        // so this remains a synchronous safety write while Kotlin only owns device persistence.
        val initResult = engine.setHardwareVolumeRawVerified(
            handle = handle,
            raw = targetRaw,
            reason = if (hardwareResetToMaximum) "reattach_force:$targetReason" else "init_direct:$targetReason",
        )
        if (!initResult.confirmed) {
            AppLogger.e(TAG, "Hardware volume initialization transaction failed " +
                "status=${initResult.status} observed=${initResult.observedRaw} reason=$reason")
            return false
        }

        val persistedRaw = initResult.observedRaw?.takeIf { it in minRaw..maxRaw } ?: targetRaw
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
        cancelBoundaryFade("execute_user_set:$reason")
        val handle = engine.currentHandle
        if (handle == 0L) return UsbAudioEngine.ERR_NOT_INITIALIZED
        if (!engine.nativeCanControlVolume(handle)) return -2

        val normalized = uiVolume.coerceIn(0f, 1f)
        val result = engine.setHardwareVolumeNormalizedVerified(
            handle = handle,
            normalized = normalized,
            reason = "explicit_user:$reason",
        )
        val observedRaw = result.observedRaw
        AppLogger.i(
            TAG,
            "HW_VOL_TRACE slider_native ui=$normalized status=${result.status} " +
                "observed=$observedRaw reason=$reason",
        )
        if (!result.confirmed || observedRaw == null) return result.status

        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val resRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        if (minRaw >= maxRaw || observedRaw !in minRaw..maxRaw) {
            return UsbAudioEngine.ERR_HARDWARE_VOLUME_WRITE_UNCONFIRMED
        }
        currentDevice()?.let { device ->
            UsbHardwareVolumeStore.write(device, observedRaw, minRaw, maxRaw, resRaw, reason)
            syncPreferencesFromRaw(observedRaw, minRaw, maxRaw, "explicit_user:$reason")
        }
        return 0
    }

    fun adjustNativeAndPersist(direction: Int, reason: String): Int {
        cancelBoundaryFade("execute_user_adjust:$reason")
        val handle = engine.currentHandle
        val device = currentDevice() ?: return UsbAudioEngine.ERR_NOT_INITIALIZED
        if (handle == 0L) return UsbAudioEngine.ERR_NOT_INITIALIZED
        if (!engine.nativeCanControlVolume(handle)) return -2

        val result = engine.adjustHardwareVolumeVerified(
            handle = handle,
            direction = direction.sign(),
            appStepRaw = APP_STEP_RAW,
            reason = "step_direct:$reason",
        )
        val observedRaw = result.observedRaw
        AppLogger.i(
            TAG,
            "HW_VOL_TRACE step_native direction=${direction.sign()} appStepRaw=$APP_STEP_RAW " +
                "status=${result.status} observed=$observedRaw reason=$reason",
        )
        if (!result.confirmed || observedRaw == null) return result.status

        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val deviceResRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        if (minRaw >= maxRaw || observedRaw !in minRaw..maxRaw) {
            return UsbAudioEngine.ERR_HARDWARE_VOLUME_WRITE_UNCONFIRMED
        }
        UsbHardwareVolumeStore.write(device, observedRaw, minRaw, maxRaw, deviceResRaw, reason)
        syncPreferencesFromRaw(observedRaw, minRaw, maxRaw, "direct_step:$reason")
        AppLogger.i(
            TAG,
            "USB HW native step applied: direction=${direction.sign()} raw=$observedRaw " +
                "db=${observedRaw / 256.0f} reason=$reason",
        )
        return 0
    }

    fun readDisplayedStep(reason: String): Int? {
        if (boundaryFadeState != null) {
            // The transition attenuation is not a user-volume change. Keep MediaSession/UI on the
            // persisted user value instead of publishing the temporary Feature Unit raw.
            return currentStep()
        }
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
        boundaryFadeState?.let { return it.userRaw / 256.0f }
        val handle = engine.currentHandle
        if (handle == 0L || !isHardwareRouteActive()) return 0f
        val raw = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        return if (raw == Int.MIN_VALUE) 0f else raw / 256.0f
    }

    fun captureLiveVolume(reason: String): LiveVolumeSnapshot? {
        boundaryFadeState?.let { state ->
            return LiveVolumeSnapshot(
                handle = state.handle,
                raw = state.userRaw,
                minRaw = state.deviceMinRaw,
                maxRaw = state.deviceMaxRaw,
                resolutionRaw = state.resolutionRaw,
            )
        }
        val handle = engine.currentHandle
        if (handle == 0L || !engine.nativeCanControlVolume(handle)) return null
        val minRaw = engine.nativeGetHardwareVolumeMinRaw(handle)
        val maxRaw = engine.nativeGetHardwareVolumeMaxRaw(handle)
        val resolutionRaw = engine.nativeGetHardwareVolumeResRaw(handle).coerceAtLeast(1)
        if (minRaw >= maxRaw) return null
        val nativeRaw = engine.nativeGetHardwareVolumeCurrentRaw(handle)
        val storedRaw = currentDevice()
            ?.let { UsbHardwareVolumeStore.read(it) }
            ?.raw
            ?.takeIf { it in minRaw..maxRaw }
        val raw = nativeRaw.takeIf { it != Int.MIN_VALUE && it in minRaw..maxRaw } ?: storedRaw
        if (raw == null) {
            AppLogger.w(
                TAG,
                "captureLiveVolume failed nativeRaw=$nativeRaw storedRaw=$storedRaw " +
                    "range=$minRaw..$maxRaw reason=$reason",
            )
            return null
        }
        AppLogger.i(
            TAG,
            "captureLiveVolume raw=$raw db=${raw / 256.0f} range=$minRaw..$maxRaw " +
                "res=$resolutionRaw reason=$reason",
        )
        return LiveVolumeSnapshot(handle, raw, minRaw, maxRaw, resolutionRaw)
    }

    /**
     * Release the physical Feature Unit only after the software owner is ready (or transport is
     * stopped). This write is intentionally not persisted: [snapshot.raw] remains the user's DAC
     * hardware-volume preference for the next time hardware ownership is selected.
     */
    fun releaseToUnityVerified(snapshot: LiveVolumeSnapshot, reason: String): Boolean {
        if (engine.currentHandle != snapshot.handle || !engine.nativeCanControlVolume(snapshot.handle)) {
            AppLogger.w(
                TAG,
                "releaseToUnityVerified rejected stale/unavailable handle old=0x${snapshot.handle.toString(16)} " +
                    "live=0x${engine.currentHandle.toString(16)} reason=$reason",
            )
            return false
        }
        val unityRaw = UsbHardwareVolumeMath.quantizeRaw(
            raw = 0.coerceIn(snapshot.minRaw, snapshot.maxRaw),
            minRaw = snapshot.minRaw,
            maxRaw = snapshot.maxRaw,
            resRaw = snapshot.resolutionRaw,
        )
        val result = engine.setHardwareVolumeRawVerified(
            handle = snapshot.handle,
            raw = unityRaw,
            reason = "hw_to_sw_release:$reason",
        )
        val ok = result.confirmed && result.observedRaw != null
        AppLogger.i(
            TAG,
            "releaseToUnityVerified target=$unityRaw db=${unityRaw / 256.0f} status=${result.status} " +
                "confirmed=${result.confirmed} observed=${result.observedRaw} reason=$reason",
        )
        return ok
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
        private const val BOUNDARY_ATTENUATION_DB = 48
        private const val MIN_BOUNDARY_FADE_MS = 40
        private const val MAX_BOUNDARY_FADE_MS = 600
        private const val TARGET_BOUNDARY_POINT_MS = 24
        private const val MIN_BOUNDARY_POINTS = 3
        private const val MAX_BOUNDARY_POINTS = 12
        private const val MIN_BOUNDARY_POINT_INTERVAL_MS = 12
        private const val BOUNDARY_SETTLE_MS = 8L
        private const val BOUNDARY_ABORT_DRAIN_MS = 40L
    }
}
