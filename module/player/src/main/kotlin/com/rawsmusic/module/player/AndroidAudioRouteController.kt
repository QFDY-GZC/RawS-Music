package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.atomic.AtomicReference

/**
 * Owns Android AudioPolicy route changes for non-USB-exclusive playback.
 *
 * FfmpegAudioPlayer still owns decoding, AudioTrack construction, and playback state.
 * This controller only decides when Android/Native output should be retargeted or rebuilt
 * after wired / Bluetooth / USB Android route changes.
 */
internal class AndroidAudioRouteController(
    private val context: Context,
    private val isUsbExclusiveMode: () -> Boolean,
    private val isRouteMutable: () -> Boolean,
    private val stateDescription: () -> String,
    private val isReleased: () -> Boolean,
    private val nativeAudioEngineProvider: () -> NativeAudioEngine?,
    private val audioTrackProvider: () -> AudioTrack?,
    private val recreateAudioTrackInline: (forceSco: Boolean, forcedDevice: AudioDeviceInfo?) -> AudioTrack?,
    private val requestNativeOutputRebuild: (reason: String, forcedDeviceId: Int) -> Unit,
    private val wakePlaybackLoop: () -> Unit,
    private val onAndroidUsbAudioRouteAdded: () -> Unit
) {
    companion object {
        private const val TAG = "AndroidAudioRouteController"
        private const val ROUTE_REPAIR_THROTTLE_MS = 450L
        private const val ROUTE_REPAIR_DELAY_MS = 180L
    }

    @Volatile
    private var suppressExternalRouteForUsbCutover = false

    @Volatile
    private var audioDeviceCallbackRegistered = false

    @Volatile
    private var lastAndroidRouteRepairMs: Long = 0L

    private data class RouteRebuild(val deviceId: Int, val reason: String, val readyAtMs: Long)
    private val pendingRouteRebuild = AtomicReference<RouteRebuild?>(null)

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            AppLogger.w(
                TAG,
                "=== audioDeviceCallback: onAudioDevicesAdded, state=${stateDescription()}, " +
                    "usbExclusive=${isUsbExclusiveMode()} devices=${describeAudioDevices(addedDevices)} ==="
            )
            if (isUsbExclusiveMode()) return
            if (!isRouteMutable()) return

            val externalRouteChanged = addedDevices?.any { it.isSink && it.isExternalRouteDevice() } == true
            val usbAudioRouteChanged = addedDevices?.any { it.isSink && it.isUsbAudioRouteDevice() } == true
            if (externalRouteChanged) {
                if (usbAudioRouteChanged) {
                    onAndroidUsbAudioRouteAdded()
                }
                if (suppressExternalRouteForUsbCutover) {
                    AppLogger.w(
                        TAG,
                        "AudioDeviceCallback external-route retarget suppressed for USB exclusive cutover: " +
                            "devices=${describeAudioDevices(addedDevices)}"
                    )
                    return
                }
                routeAndroidOutputToExternalDevice("audio_device_added_external", addedDevices)
                return
            }

            // Microphones/virtual device notifications must not clear a live media preference.
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
            AppLogger.w(
                TAG,
                "=== audioDeviceCallback: onAudioDevicesRemoved, state=${stateDescription()}, " +
                    "usbExclusive=${isUsbExclusiveMode()} devices=${describeAudioDevices(removedDevices)} ==="
            )
            if (isUsbExclusiveMode()) return
            if (!isRouteMutable()) return
            if (android.os.Build.VERSION.SDK_INT < 23) return
            if (removedDevices?.any { it.isSink && it.isExternalRouteDevice() } != true) return

            val activeTrack = audioTrackProvider()
            val activeDevice = try { activeTrack?.routedDevice ?: activeTrack?.preferredDevice } catch (_: Exception) { null }
            if (activeDevice != null && activeDevice.isExternalRouteDevice() &&
                removedDevices.none { it.id == activeDevice.id } && findOutputDeviceById(activeDevice.id) != null
            ) return

            try {
                val hasBluetoothRemoval = removedDevices?.any { it.isBluetoothRouteDevice() } == true
                val hasExternalRemoval = removedDevices?.any { it.isExternalRouteDevice() } == true

                if (nativeAudioEngineProvider() != null) {
                    val ok = retargetNativeOutputDevice("audio_device_removed")
                    if (!ok) {
                        requestNativeOutputRebuild("audio_device_removed_native_fallback", 0)
                    }
                    if (hasExternalRemoval) {
                        repairAndroidOutputRoute("audio_device_removed_external_native", forceRebuild = false)
                    }
                } else if (hasBluetoothRemoval || hasExternalRemoval) {
                    AppLogger.i(
                        TAG,
                        "External audio device removed; rebuilding/clearing route to restore speaker. " +
                            "bluetooth=$hasBluetoothRemoval external=$hasExternalRemoval"
                    )
                    repairAndroidOutputRoute("audio_device_removed_external", forceRebuild = true)
                } else {
                    applyPreferredDeviceToAudioTrack("audio_device_removed")
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "audioDeviceCallback remove handling failed", e)
            }
        }
    }

    fun registerAudioDeviceCallback() {
        if (audioDeviceCallbackRegistered) return
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            try {
                val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.registerAudioDeviceCallback(audioDeviceCallback, null)
                audioDeviceCallbackRegistered = true
                AppLogger.d(TAG, "AudioDeviceCallback registered")
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to register AudioDeviceCallback: ${e.message}")
            }
        }
    }

    fun unregisterAudioDeviceCallback() {
        pendingRouteRebuild.set(null)
        if (!audioDeviceCallbackRegistered) return
        try {
            val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            am.unregisterAudioDeviceCallback(audioDeviceCallback)
            audioDeviceCallbackRegistered = false
            AppLogger.d(TAG, "AudioDeviceCallback unregistered")
        } catch (_: Exception) {
        }
    }

    fun setSuppressExternalRouteForUsbCutover(enabled: Boolean, reason: String) {
        suppressExternalRouteForUsbCutover = enabled
        AppLogger.i(TAG, "suppressAndroidExternalRouteForUsbCutover=$enabled reason=$reason")
    }

    fun preferredNativeOutputDeviceId(): Int {
        if (android.os.Build.VERSION.SDK_INT < 23) return 0
        return try {
            AudioOutputManager.getPreferredDeviceForDirect(context)?.id ?: 0
        } catch (_: Exception) {
            0
        }
    }

    fun retargetNativeOutputDevice(reason: String, forcedDeviceId: Int = 0): Boolean {
        val engine = nativeAudioEngineProvider() ?: return false
        if (isUsbExclusiveMode()) return false
        val deviceId = if (forcedDeviceId > 0) forcedDeviceId else preferredNativeOutputDeviceId()
        val ok = engine.setOutputDevice(deviceId)
        val device = findOutputDeviceById(deviceId)
        AppLogger.i(
            TAG,
            "Native output retarget: reason=$reason deviceId=$deviceId " +
                "device=${device?.shortRouteName() ?: "default"} ok=$ok actualMode=${engine.actualMode}"
        )
        return ok
    }

    fun repairAndroidOutputRoute(reason: String, forceRebuild: Boolean) {
        if (isUsbExclusiveMode()) return
        if (!isRouteMutable()) return
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastAndroidRouteRepairMs < ROUTE_REPAIR_THROTTLE_MS) {
            AppLogger.i(TAG, "Android output route repair suppressed: reason=$reason since=${now - lastAndroidRouteRepairMs}ms")
            return
        }
        lastAndroidRouteRepairMs = now
        AppLogger.w(
            TAG,
            "Android output route repair: reason=$reason forceRebuild=$forceRebuild " +
                "state=${stateDescription()} native=${nativeAudioEngineProvider() != null}"
        )

        // The executor is occupied by the long-lived write loop. Publish a command for that
        // loop instead of queuing a repair behind it or rebuilding concurrently with write().
        if (nativeAudioEngineProvider() != null) {
            requestNativeOutputRebuild(reason, 0)
        } else {
            armAndroidAudioTrackRouteRebuild(reason, 0, ROUTE_REPAIR_DELAY_MS)
        }
    }

    fun consumePendingAudioTrackRouteRebuild(): AudioTrack? {
        val pending = pendingRouteRebuild.get() ?: return audioTrackProvider()
        if (android.os.SystemClock.uptimeMillis() < pending.readyAtMs) return audioTrackProvider()
        if (!pendingRouteRebuild.compareAndSet(pending, null)) return audioTrackProvider()
        val reason = pending.reason
        val deviceId = pending.deviceId
        if (isUsbExclusiveMode() || isReleased()) return audioTrackProvider()
        if (!isRouteMutable()) return audioTrackProvider()
        val forcedDevice = findOutputDeviceById(deviceId)
        if (deviceId > 0 && forcedDevice == null) {
            AppLogger.w(TAG, "Android AudioTrack route rebuild skipped: external device gone reason=$reason deviceId=$deviceId")
            return audioTrackProvider()
        }
        AppLogger.w(TAG, "Android AudioTrack route rebuild executing inline: reason=$reason device=${forcedDevice?.shortRouteName() ?: "system"}")
        return recreateAudioTrackInline(
            forcedDevice?.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            forcedDevice
        ) ?: audioTrackProvider()
    }

    private fun findOutputDeviceById(deviceId: Int): AudioDeviceInfo? {
        if (android.os.Build.VERSION.SDK_INT < 23 || deviceId <= 0) return null
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.id == deviceId }
        } catch (_: Exception) {
            null
        }
    }

    fun deviceForRecovery(forcedDevice: AudioDeviceInfo?): AudioDeviceInfo? {
        if (android.os.Build.VERSION.SDK_INT < 23) return null
        return try {
            val devices = (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager)
                .getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            val track = audioTrackProvider()
            val id = recoveryOutputDeviceId(forcedDevice?.id ?: 0, track?.preferredDevice?.id ?: 0,
                track?.routedDevice?.id ?: 0, devices.map { it.id }.toSet())
            devices.firstOrNull { it.id == id }
        } catch (_: Exception) { null }
    }

    fun applyPreferredDeviceToAudioTrack(
        reason: String,
        useScoAttributes: Boolean = false,
        allowDirectPreferredDevice: Boolean = true,
        forceSpeaker: Boolean = false,
        forcedDevice: AudioDeviceInfo? = null,
        trackOverride: AudioTrack? = null
    ): Boolean {
        if (android.os.Build.VERSION.SDK_INT < 23) return false
        val track = trackOverride ?: audioTrackProvider() ?: return false
        return try {
            val device = when {
                forceSpeaker -> builtInSpeakerDevice()
                forcedDevice != null -> forcedDevice
                useScoAttributes -> bluetoothScoDevice()
                allowDirectPreferredDevice && AudioOutputManager.getCurrentOutputMode(context) == AudioOutputMode.DIRECT ->
                    AudioOutputManager.getPreferredDeviceForDirect(context)
                else -> null
            }
            val accepted = track.setPreferredDevice(device)
            val actual = track.routedDevice
            AppLogger.i(TAG, "AudioTrack route request: reason=$reason requested=${device?.shortRouteName() ?: "system"} accepted=$accepted actual=${actual?.shortRouteName() ?: "not_routed"}")
            accepted
        } catch (e: Exception) {
            AppLogger.w(TAG, "AudioTrack preferredDevice update failed: reason=$reason", e)
            false
        }
    }

    private fun routeAndroidOutputToExternalDevice(reason: String, devices: Array<out AudioDeviceInfo>?) {
        if (isUsbExclusiveMode()) return
        if (!isRouteMutable()) return
        val external = selectBestExternalOutputDevice(devices)
            ?: selectBestExternalOutputDevice(null)
        if (external == null) {
            AppLogger.w(TAG, "Android external route requested but no external output device found: reason=$reason")
            repairAndroidOutputRoute("${reason}_fallback_default", forceRebuild = false)
            return
        }
        AppLogger.w(
            TAG,
            "Android output route to external device: reason=$reason device=${external.shortRouteName()} " +
                "state=${stateDescription()} native=${nativeAudioEngineProvider() != null}"
        )

        // Do not enqueue external-device retargeting on the playback executor. During normal
        // AudioTrack playback the executor is occupied by the long-running streaming write loop.
        if (nativeAudioEngineProvider() != null) {
            val engine = nativeAudioEngineProvider()
            if (engine?.actualMode == AudioOutputMode.DIRECT && external.isBluetoothRouteDevice()) {
                AppLogger.w(
                    TAG,
                    "DIRECT Bluetooth route change requires output rebuild: reason=$reason " +
                        "device=${external.shortRouteName()}"
                )
                requestNativeOutputRebuild("${reason}_external_direct_bluetooth", external.id)
                return
            }
            val ok = retargetNativeOutputDevice("${reason}_external", forcedDeviceId = external.id)
            if (!ok) {
                // OpenSL ES cannot retarget an already-created stream. Recreate the native
                // output on the active write thread so decoder/ring/position ownership stays intact.
                requestNativeOutputRebuild("${reason}_external_native_fallback", external.id)
            }
            return
        }

        // Set preferredDevice immediately so AudioPolicy sees the intent, then arm an inline
        // rebuild that the active streaming loop will execute on its own worker thread.
        applyPreferredDeviceToAudioTrack("${reason}_external_immediate", forcedDevice = external)
        armAndroidAudioTrackRouteRebuild(reason = "${reason}_external", forcedDeviceId = external.id)
    }

    private fun armAndroidAudioTrackRouteRebuild(reason: String, forcedDeviceId: Int, delayMs: Long = 0L) {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        pendingRouteRebuild.set(RouteRebuild(forcedDeviceId, reason, android.os.SystemClock.uptimeMillis() + delayMs))
        wakePlaybackLoop()
        AppLogger.w(TAG, "Android AudioTrack route rebuild armed: reason=$reason deviceId=$forcedDeviceId state=${stateDescription()}")
    }

    private fun bluetoothScoDevice(): AudioDeviceInfo? {
        if (android.os.Build.VERSION.SDK_INT < 23) return null
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO }
        } catch (_: Exception) {
            null
        }
    }

    private fun builtInSpeakerDevice(): AudioDeviceInfo? {
        if (android.os.Build.VERSION.SDK_INT < 23) return null
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } catch (_: Exception) {
            null
        }
    }

    private fun selectBestExternalOutputDevice(devices: Array<out AudioDeviceInfo>? = null): AudioDeviceInfo? {
        if (android.os.Build.VERSION.SDK_INT < 23) return null
        return try {
            val candidates = if (devices.isNullOrEmpty()) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).asList()
            } else {
                devices.asList()
            }
            candidates
                .filter { it.isSink && it.isExternalRouteDevice() }
                .maxWithOrNull(compareBy<AudioDeviceInfo> { externalRoutePriority(it) }.thenBy { it.id })
        } catch (_: Exception) {
            null
        }
    }

    private fun externalRoutePriority(device: AudioDeviceInfo): Int {
        return when (device.type) {
            AudioDeviceInfo.TYPE_USB_HEADSET,
            AudioDeviceInfo.TYPE_USB_DEVICE,
            AudioDeviceInfo.TYPE_USB_ACCESSORY -> 100
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> 90
            AudioDeviceInfo.TYPE_LINE_ANALOG,
            AudioDeviceInfo.TYPE_LINE_DIGITAL,
            AudioDeviceInfo.TYPE_AUX_LINE -> 80
            AudioDeviceInfo.TYPE_HDMI,
            AudioDeviceInfo.TYPE_HDMI_ARC -> 70
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
            26, 27 -> 60
            else -> if (device.isExternalRouteDevice()) 50 else 0
        }
    }

    private fun describeAudioDevices(devices: Array<out AudioDeviceInfo>?): String {
        if (devices.isNullOrEmpty()) return "[]"
        return devices.joinToString(prefix = "[", postfix = "]") { it.shortRouteName() }
    }
}

internal fun AudioDeviceInfo.isBluetoothRouteDevice(): Boolean {
    return type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
        type == 23 || // TYPE_HEARING_AID
        type == 26 || // TYPE_BLE_HEADSET
        type == 27    // TYPE_BLE_SPEAKER
}

internal fun AudioDeviceInfo.isUsbAudioRouteDevice(): Boolean {
    return type == AudioDeviceInfo.TYPE_USB_HEADSET ||
        type == AudioDeviceInfo.TYPE_USB_DEVICE ||
        type == AudioDeviceInfo.TYPE_USB_ACCESSORY
}

internal fun AudioDeviceInfo.isExternalRouteDevice(): Boolean {
    return when (type) {
        AudioDeviceInfo.TYPE_WIRED_HEADSET,
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
        AudioDeviceInfo.TYPE_USB_DEVICE,
        AudioDeviceInfo.TYPE_USB_ACCESSORY,
        AudioDeviceInfo.TYPE_AUX_LINE,
        AudioDeviceInfo.TYPE_HDMI,
        AudioDeviceInfo.TYPE_HDMI_ARC,
        AudioDeviceInfo.TYPE_DOCK,
        AudioDeviceInfo.TYPE_LINE_ANALOG,
        AudioDeviceInfo.TYPE_LINE_DIGITAL -> true
        else -> isBluetoothRouteDevice()
    }
}

internal fun AudioDeviceInfo.shortRouteName(): String {
    return "${productName ?: "unknown"}/type=$type/id=$id"
}
