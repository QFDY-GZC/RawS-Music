package com.rawsmusic.module.player

import android.hardware.usb.UsbDevice
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbDsdModeConfig
import com.rawsmusic.module.player.usb.UsbDsdTransport
import com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities
import com.rawsmusic.module.player.usb.UsbLearnedPolicyStore
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.usb.UsbPcmFormatRequest
import com.rawsmusic.module.player.usb.UsbPcmFormatCapability
import com.rawsmusic.module.player.usb.UsbPcmOutputMode
import com.rawsmusic.module.player.usb.UsbRecoveryPlan

internal data class UsbFeedbackModelDecision(
    val noFeedback: Boolean,
    val reason: String,
    val format: UsbPcmFormatCapability?
)

/**
 * Builds the effective USB output profile without owning the playback session.
 * Keeping this policy object separate prevents profile decisions from being mixed
 * with permission, renderer, and real-time feeder lifecycle code.
 */
internal class PlayerUsbOutputProfileBuilder(
    private val tag: String,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val currentDsdMode: () -> UsbDsdModeConfig?,
        val currentDsdRate: () -> Int,
        val resolvePcmFormatRequest: () -> UsbPcmFormatRequest,
        val currentDevice: () -> UsbDevice?,
        val pendingRecoveryPlan: () -> UsbRecoveryPlan?,
        val capabilities: () -> UsbDeviceAudioCapabilities?,
        val engineCapabilities: () -> UsbDeviceAudioCapabilities?,
        val isHardwareVolumeValidated: () -> Boolean,
        val decideFeedbackModel: (
            UsbDeviceAudioCapabilities?,
            UsbPcmFormatRequest,
            com.rawsmusic.module.player.usb.UsbLearnedPolicy?,
            UsbRecoveryPlan?
        ) -> UsbFeedbackModelDecision,
        val currentSongIsDsdSource: () -> Boolean,
        val markHardwareVolumeValidated: () -> Unit,
        val stickyHardwareVolumeValidated: () -> Boolean,
    )

    fun build(exclusive: Boolean): UsbOutputProfile {
        val effectiveDsdMode = callbacks.currentDsdMode()
        val effectiveDsdActive = effectiveDsdMode != null
        val bitPerfect = AppPreferences.Player.bitPerfectEnabled &&
            exclusive &&
            !effectiveDsdActive
        if (exclusive && effectiveDsdActive && AppPreferences.Player.bitPerfectEnabled) {
            AppLogger.w(tag, "USB profile: PCM→DSD/DSD transport overrides PCM bit-perfect for this session")
        }

        val formatRequest = callbacks.resolvePcmFormatRequest()
        val learned = runCatching {
            callbacks.currentDevice()?.let { device ->
                UsbLearnedPolicyStore.readForPlayback(
                    device.vendorId,
                    device.productId,
                    null,
                    effectiveDsdActive,
                    callbacks.currentDsdRate(),
                    effectiveDsdMode?.transport?.prefValue ?: UsbDsdTransport.NATIVE.prefValue,
                )
            }
        }.getOrNull()
        val pendingPlan = callbacks.pendingRecoveryPlan()?.takeIf { it.requiresProfileRestart }
        val caps = callbacks.capabilities() ?: callbacks.engineCapabilities()
        val feedbackModel = callbacks.decideFeedbackModel(caps, formatRequest, learned, pendingPlan)
        val useLastGoodFallback = pendingPlan?.preferLastGoodProfile == true
        val learnedNoClockSet = learned?.noClockSet == true ||
            (useLastGoodFallback && learned?.lastGoodNoClockSet == true)
        val learnedNoFeatureUnit = learned?.noFeatureUnit == true ||
            (useLastGoodFallback && learned?.lastGoodNoFeatureUnit == true)
        val learnedSafeAlt = learned?.preferSafeAlt == true ||
            (useLastGoodFallback && learned?.lastGoodPreferSafeAlt == true)

        if (exclusive && feedbackModel.noFeedback) {
            val format = feedbackModel.format
            AppLogger.w(
                tag,
                "USB stream config: noFeedback=true reason=${feedbackModel.reason} " +
                    "fmt=${format?.sampleRate}/${format?.validBits}/${format?.subslotBytes} " +
                    "iface=${format?.interfaceNumber} alt=${format?.altSetting} " +
                    "out=0x${(format?.outEndpoint ?: 0).toString(16)} " +
                    "fb=0x${(format?.feedbackEndpoint ?: 0).toString(16)} " +
                    "outSync=${format?.outSync} fbUsage=${format?.feedbackUsage}",
            )
        }

        val usbVolumeMode = AppPreferences.Player.usbVolumeMode
        val hardwareRequested = exclusive &&
            usbVolumeMode == 1 &&
            AppPreferences.Player.hardwareFeatureUnitEnabled
        val fixedDigitalVolume = exclusive && usbVolumeMode == 2
        val nativeHardwareValidated = callbacks.isHardwareVolumeValidated()
        val hardwareValidated = if (hardwareRequested) {
            // The native validation state is supplied through the callback-owned capability
            // path. This fallback remains false until the engine confirms the Feature Unit.
            nativeHardwareValidated || (exclusive && callbacks.stickyHardwareVolumeValidated())
        } else {
            nativeHardwareValidated
        }
        if (exclusive && hardwareRequested && nativeHardwareValidated) {
            callbacks.markHardwareVolumeValidated()
        }

        return UsbOutputProfile(
            exclusive = exclusive,
            bitPerfect = bitPerfect,
            hardwareVolumeRequested = hardwareRequested,
            hardwareVolumeValidated = hardwareValidated,
            targetSampleRate = AppPreferences.Player.usbTargetSampleRate,
            targetBitDepth = formatRequest.targetValidBits,
            targetSubslotBytes = formatRequest.targetSubslotBytes,
            pcmOutputMode = formatRequest.mode,
            dsdConversionEnabled = effectiveDsdActive,
            dsdDoPEnabled = effectiveDsdMode?.transport == UsbDsdTransport.DOP,
            dsdSourceDirect = callbacks.currentSongIsDsdSource() && effectiveDsdActive,
            safeMode = AppPreferences.Player.usbSafeExclusiveMode,
            noClockSet = AppPreferences.Player.usbDisableDacClockInfo ||
                learnedNoClockSet || pendingPlan?.disableClockSet == true,
            noFeedback = feedbackModel.noFeedback,
            noFeatureUnit = learnedNoFeatureUnit || pendingPlan?.disableFeatureUnit == true,
            force1msPacket = AppPreferences.Player.usbForce1MsPacket ||
                learned?.force1msPacket == true || pendingPlan?.force1msPacket == true,
            preferSafeAlt = AppPreferences.Player.usbSafeExclusiveMode ||
                learnedSafeAlt || pendingPlan?.preferSafeAlt == true,
            forceSoftwareVolume = false,
            fixedDigitalVolume = fixedDigitalVolume,
            lastGoodAlt = learned?.lastGoodAlt ?: 0,
            lastGoodSampleRate = learned?.lastGoodSampleRate ?: 0,
            lastGoodBitDepth = learned?.lastGoodBitDepth ?: 0,
            lastGoodSubslot = learned?.lastGoodSubslot ?: 0,
            lastGoodFeedbackEndpoint = learned?.lastGoodFeedbackEndpoint ?: 0,
        )
    }
}
