package com.rawsmusic.module.player

import android.hardware.usb.UsbDevice
import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isDsdSourceFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities
import com.rawsmusic.module.player.usb.UsbDsdModeConfig
import com.rawsmusic.module.player.usb.UsbDsdTransport
import com.rawsmusic.module.player.usb.UsbLearnedPolicyStore
import com.rawsmusic.module.player.usb.UsbPcmFormatRequest
import com.rawsmusic.module.player.usb.UsbPcmOutputMode
import com.rawsmusic.module.player.usb.UsbPcmFormatRequestPolicy
import com.rawsmusic.module.player.usb.UsbPolicyRestartSource
import com.rawsmusic.module.player.usb.UsbPolicyRestartSourceInputs
import com.rawsmusic.module.player.usb.UsbPolicyRestartSourceResolver
import com.rawsmusic.module.player.usb.buildSupportedDsdSourceDirectModeConfig
import com.rawsmusic.module.player.usb.buildUsbDsdModeConfig
import com.rawsmusic.module.player.usb.dsdMultiplierFromSourceRate
import com.rawsmusic.module.player.usb.normalizeDsdSourceRateHz
import com.rawsmusic.module.player.usb.normalizeProbedDsdSourceRateHz

/** Resolves user USB format preferences without owning the USB stream lifecycle. */
internal class PlayerUsbFormatPolicyCoordinator(
    private val currentSong: () -> AudioFile?,
    private val currentCapabilities: () -> UsbDeviceAudioCapabilities?,
    private val setCapabilities: (UsbDeviceAudioCapabilities?) -> Unit,
    private val usbEngine: UsbAudioEngine,
    private val logInfo: (String) -> Unit,
    private val logWarning: (String) -> Unit,
) {
    fun resolvePcmFormatRequest(): UsbPcmFormatRequest =
        UsbPcmFormatRequestPolicy.fromModeId(AppPreferences.Player.usbPcmOutputMode)

    fun currentSongIsDsdSource(): Boolean {
        val song = currentSong() ?: return false
        return song.isDsdSourceFile() || song.bitsPerSample == 1 || song.sampleRate >= DSD64_RATE_HZ
    }

    fun currentSongDsdSourceRate(): Int {
        val song = currentSong()
        val metadataRate = song?.sampleRate?.takeIf { it > 0 }
        if (metadataRate != null) return normalizeDsdSourceRateHz(metadataRate)
        val probedRate = song?.path
            ?.let { runCatching { FFmpegBridge.probeSampleRate(it) }.getOrDefault(0) }
            ?.takeIf { it > 0 }
        return probedRate?.let(::normalizeProbedDsdSourceRateHz) ?: DSD64_RATE_HZ
    }

    fun currentPcmSourceRateForDsd(): Int {
        val song = currentSong()
        val path = song?.path?.takeIf { it.isNotBlank() }
        val metadataRate = song?.sampleRate?.takeIf { it > 0 && it < DSD64_RATE_HZ }
        if (metadataRate != null) return metadataRate
        val probedRate = path
            ?.let { runCatching { FFmpegBridge.probeSampleRate(it) }.getOrDefault(0) }
            ?.takeIf { it > 0 && it < DSD64_RATE_HZ }
        if (probedRate != null) return probedRate
        return usbEngine.currentSampleRate.takeIf { it > 0 } ?: DEFAULT_PCM_RATE_HZ
    }

    fun resolvePolicyRestartSource(): UsbPolicyRestartSource {
        val song = currentSong()
        val sourcePath = song?.path?.takeIf { it.isNotBlank() }
        val probedRate = sourcePath
            ?.let { runCatching { FFmpegBridge.probeSampleRate(it) }.getOrDefault(0) } ?: 0
        val probedBits = sourcePath
            ?.let { runCatching { FFmpegBridge.probeBitsPerSample(it) }.getOrDefault(0) } ?: 0
        val probedChannels = sourcePath
            ?.let { runCatching { FFmpegBridge.probeChannelCount(it) }.getOrDefault(0) } ?: 0
        val resolved = UsbPolicyRestartSourceResolver.resolve(
            UsbPolicyRestartSourceInputs(
                sourcePath = sourcePath,
                metadataSampleRate = song?.sampleRate ?: 0,
                metadataBitsPerSample = song?.bitsPerSample ?: 0,
                metadataChannels = song?.channelCount ?: 0,
                sourceLooksLikeDsd = song?.isDsdSourceFile() == true,
                probedSampleRate = probedRate,
                probedBitsPerSample = probedBits,
                probedChannels = probedChannels,
                runtimeSampleRate = usbEngine.currentSampleRate,
                runtimeBitsPerSample = usbEngine.currentBits,
                runtimeChannels = usbEngine.currentChannels,
            ),
        )
        AppLogger.i(
            TAG,
            "resolveUsbPolicyRestartSource: path=$sourcePath inferredDsd=${resolved.inferredDsd} " +
                "metadata=${song?.sampleRate ?: 0}/${song?.bitsPerSample ?: 0}/${song?.channelCount ?: 0} " +
                "probed=$probedRate/$probedBits/$probedChannels " +
                "runtime=${usbEngine.currentSampleRate}/${usbEngine.currentBits}/${usbEngine.currentChannels} " +
                "resolved=${resolved.sampleRate}/${resolved.bitsPerSample}/${resolved.channels}",
        )
        return resolved
    }

    fun currentEffectiveDsdMode(): UsbDsdModeConfig? {
        val capabilities = currentCapabilities() ?: usbEngine.getDeviceCapabilities()
        val requestedTransport = UsbDsdTransport.fromPref(AppPreferences.Player.usbDsdTransportMode)
        return if (currentSongIsDsdSource()) {
            buildSupportedDsdSourceDirectModeConfig(
                sourceDsdRateHz = currentSongDsdSourceRate(),
                requestedTransport = requestedTransport,
                capabilities = capabilities,
            )
        } else {
            buildUsbDsdModeConfig(
                enabled = AppPreferences.Player.dsdConversionEnabled,
                multiplier = AppPreferences.Player.dsdRate,
                transport = UsbDsdTransport.NATIVE,
                sourceRateHz = currentPcmSourceRateForDsd(),
                sourceIsAlreadyDsd = false,
            )
        }
    }

    fun currentEffectiveDsdRate(): Int = if (currentSongIsDsdSource()) {
        dsdMultiplierFromSourceRate(currentSongDsdSourceRate())
    } else {
        AppPreferences.Player.dsdRate
    }

    fun refreshCapabilities(reason: String) {
        val capabilities = usbEngine.getDeviceCapabilities()
        setCapabilities(capabilities)
        AppLogger.i(
            TAG,
            "USB capabilities refreshed: reason=$reason rates=${capabilities?.supportedSampleRates} " +
                "modes=${capabilities?.supportedPcmModes}",
        )
        reconcileOutputSettingsForOwner(capabilities)
    }

    fun learnedPolicyKey(device: UsbDevice): String {
        val effectiveDsdMode = currentEffectiveDsdMode()
        val sourceDsd = currentSongIsDsdSource()
        val pcmToDsd = effectiveDsdMode != null && !sourceDsd
        return UsbLearnedPolicyStore.keyOfTransport(
            device.vendorId,
            device.productId,
            null,
            dsdEnabled = effectiveDsdMode != null || pcmToDsd,
            dsdRate = currentEffectiveDsdRate(),
            dsdTransportMode = effectiveDsdMode?.transport?.prefValue ?: UsbDsdTransport.NATIVE.prefValue,
        )
    }

    fun reconcileOutputSettingsForOwner(capabilities: UsbDeviceAudioCapabilities?) {
        if (capabilities == null) return
        val selectedRate = AppPreferences.Player.usbTargetSampleRate
        if (selectedRate != 0 && selectedRate !in capabilities.supportedSampleRates) {
            logWarning("Selected USB sample rate unsupported: $selectedRate, fallback to AUTO")
            AppPreferences.Player.usbTargetSampleRate = 0
        }
        val selectedMode = UsbPcmOutputMode.fromId(AppPreferences.Player.usbPcmOutputMode)
        if (selectedMode != UsbPcmOutputMode.AUTO && selectedMode !in capabilities.supportedPcmModes) {
            logWarning("Selected USB PCM mode unsupported: $selectedMode, fallback to AUTO")
            AppPreferences.Player.usbPcmOutputMode = UsbPcmOutputMode.AUTO.id
        }
        if (AppPreferences.Player.dsdConversionEnabled) {
            val dsdRate = AppPreferences.Player.dsdRate
            val exactNativeSupported = capabilities.supportsNativeDsd(dsdRate)
            if (!exactNativeSupported && !capabilities.hasAnyNativeDsdDescriptor) {
                logWarning(
                    "PCM->DSD Native DSD not confirmed by current capability snapshot: DSD$dsdRate; " +
                        "preserve user preference and let runtime/native re-verify on playback",
                )
            } else if (!exactNativeSupported) {
                logWarning(
                    "PCM->DSD Native DSD rate DSD$dsdRate not explicitly confirmed by descriptors; " +
                        "Native DSD descriptor exists, keep conversion enabled and let native init verify",
                )
            } else if (UsbDsdTransport.fromPref(AppPreferences.Player.usbDsdTransportMode) != UsbDsdTransport.NATIVE) {
                logInfo("PCM->DSD uses Native DSD transport; preserving DoP preference only for DSD source files")
            }
        }
    }

    private companion object {
        const val TAG = "PlayerController"
        const val DSD64_RATE_HZ = 2_822_400
        const val DEFAULT_PCM_RATE_HZ = 44_100
    }
}
