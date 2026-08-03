package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioTrack
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.RejectedExecutionException

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
    private val runOnPlaybackExecutor: (block: () -> Unit) -> Unit,
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

    @Volatile
    private var pendingAndroidRouteRebuildDeviceId: Int = 0

    @Volatile
    private var pendingAndroidRouteRebuildReason: String? = null

    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
            AppLogger.w(
                TAG,
                "=== audioDeviceCallback: onAudioDevicesAdded, state=${stateDescription()}, " +
                    "usbExclusive=${isUsbExclusiveMode()} devices=${describeAudioDevices(addedDevices)} ==="
            )
            if (isUsbExclusiveMode()) return
            if (!isRouteMutable()) return

            val externalRouteChanged = addedDevices?.any { it.isExternalRouteDevice() } == true
            val usbAudioRouteChanged = addedDevices?.any { it.isUsbAudioRouteDevice() } == true
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

            if (nativeAudioEngineProvider() != null) {
                retargetNativeOutputDevice("audio_device_added")
            } else {
                applyPreferredDeviceToAudioTrack("audio_device_added")
            }
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

            try {
                val hasBluetoothRemoval = removedDevices?.any { it.isBluetoothRouteDevice() } == true
                val hasExternalRemoval = removedDevices?.any { it.isExternalRouteDevice() } == true

                if (nativeAudioEngineProvider() != null) {
                    retargetNativeOutputDevice("audio_device_removed")
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
        val now = System.currentTimeMillis()
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

        try {
            runOnPlaybackExecutor routeRepair@{
                try {
                    Thread.sleep(ROUTE_REPAIR_DELAY_MS)
                    if (isUsbExclusiveMode() || isReleased()) return@routeRepair
                    if (!isRouteMutable()) return@routeRepair
                    if (nativeAudioEngineProvider() != null) {
                        val ok = retargetNativeOutputDevice("${reason}_delayed")
                        if (!ok) {
                            AppLogger.w(TAG, "Native output retarget failed during route repair: reason=$reason")
                        }
                        return@routeRepair
                    }

                    // For MEDIA/AudioTrack playback, Android may leave the track bound to the
                    // removed USB/wired device. Clear explicit routing first, then rebuild when
                    // a real removal happened so AudioPolicy recreates the stream on speaker.
                    applyPreferredDeviceToAudioTrack("${reason}_clear", forceSpeaker = false)
                    val track = audioTrackProvider()
                    if (forceRebuild) {
                        recreateAudioTrackInline(false, null)
                    } else if (track == null ||
                        track.state == AudioTrack.STATE_UNINITIALIZED ||
                        track.playState == AudioTrack.PLAYSTATE_STOPPED
                    ) {
                        recreateAudioTrackInline(false, null)
                    } else if (stateDescription() == FfmpegAudioPlayer.State.PLAYING.name) {
                        try { track.play() } catch (_: Exception) {}
                    } else {
                        try { track.pause() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    AppLogger.w(TAG, "Android output route repair failed: reason=$reason", e)
                }
            }
        } catch (e: RejectedExecutionException) {
            AppLogger.w(TAG, "Android output route repair skipped: executor rejected reason=$reason", e)
        }
    }

    fun consumePendingAudioTrackRouteRebuild(): AudioTrack? {
        val reason = pendingAndroidRouteRebuildReason ?: return audioTrackProvider()
        val deviceId = pendingAndroidRouteRebuildDeviceId
        pendingAndroidRouteRebuildReason = null
        pendingAndroidRouteRebuildDeviceId = 0
        if (isUsbExclusiveMode() || isReleased()) return audioTrackProvider()
        if (!isRouteMutable()) return audioTrackProvider()
        val forcedDevice = findOutputDeviceById(deviceId) ?: selectBestExternalOutputDevice(null)
        if (forcedDevice == null) {
            AppLogger.w(TAG, "Android AudioTrack route rebuild skipped: external device gone reason=$reason deviceId=$deviceId")
            return audioTrackProvider()
        }
        AppLogger.w(TAG, "Android AudioTrack route rebuild executing inline: reason=$reason device=${forcedDevice.shortRouteName()}")
        return recreateAudioTrackInline(
            forcedDevice.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
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
            if (device != null) {
                track.preferredDevice = device
                AppLogger.i(TAG, "AudioTrack preferredDevice set: reason=$reason device=${device.shortRouteName()}")
            } else {
                track.preferredDevice = null
                AppLogger.i(TAG, "AudioTrack preferredDevice cleared: reason=$reason")
            }
            true
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
            retargetNativeOutputDevice("${reason}_external", forcedDeviceId = external.id)
            return
        }

        // Set preferredDevice immediately so AudioPolicy sees the intent, then arm an inline
        // rebuild that the active streaming loop will execute on its own worker thread.
        applyPreferredDeviceToAudioTrack("${reason}_external_immediate", forcedDevice = external)
        armAndroidAudioTrackRouteRebuild(reason = "${reason}_external", forcedDeviceId = external.id)
    }

    private fun armAndroidAudioTrackRouteRebuild(reason: String, forcedDeviceId: Int) {
        if (android.os.Build.VERSION.SDK_INT < 23) return
        pendingAndroidRouteRebuildDeviceId = forcedDeviceId
        pendingAndroidRouteRebuildReason = reason
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
