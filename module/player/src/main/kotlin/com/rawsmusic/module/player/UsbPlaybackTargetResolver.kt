package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.UsbBitPerfectMode
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbDsdModeConfig
import com.rawsmusic.module.player.usb.UsbPcmSampleRatePolicy
import com.rawsmusic.module.player.usb.UsbDsdTransport
import com.rawsmusic.module.player.usb.UsbBitPerfectModePolicy
import com.rawsmusic.module.player.usb.UsbVolumeModeBitPerfectPolicy
import com.rawsmusic.module.player.usb.UsbDeviceAudioCapabilities
import com.rawsmusic.module.player.usb.UsbPcmOutputMode
import com.rawsmusic.module.player.usb.buildSupportedDsdSourceDirectModeConfig
import com.rawsmusic.module.player.usb.buildSupportedPcmToDsdModeConfig
import com.rawsmusic.module.player.usb.chooseDsdSourcePcmDecodeRate
import com.rawsmusic.module.player.usb.isLikelyDsdSource
import com.rawsmusic.module.player.usb.normalizeProbedDsdSourceRateHz

/**
 * Resolves the decoder target for USB-exclusive playback.
 *
 * This class owns source probing and target PCM/DSD decisions only.  It
 * deliberately does not own USB stream health, write watermark, recovery, or
 * native pacing policy; those remain outside Kotlin helper extraction.
 */
internal class UsbPlaybackTargetResolver(
    private val tag: String,
    private val resolvePath: (String) -> String = { it },
) {
    data class Target(
        val sampleRate: Int,
        val bitsPerSample: Int,
        val channels: Int,
        val sourceSampleRate: Int,
        val sourceBitsPerSample: Int,
        val sourceChannels: Int,
        val rawSourceBits: Int,
        val safeSourceBits: Int,
        val safeSourceChannels: Int,
        val sourceIsDsd: Boolean,
        val sourceExceedsUsbPcm: Boolean,
        val strictBitPerfect: Boolean,
        val bitPerfectPolicyFailureReason: String?,
        val sourceDsdMode: UsbDsdModeConfig?,
        val pcmToDsdMode: UsbDsdModeConfig?,
        val dsdDecodeRate: Int,
        val requestedTargetBits: Int
    )

    fun resolve(
        sourcePath: String,
        usbBitPerfectPolicyMode: UsbBitPerfectMode,
        capabilities: UsbDeviceAudioCapabilities? = null,
    ): Target {
        val probePath = resolvePath(sourcePath)
        val remoteOptions = RemotePlaybackOptionsResolver.lookup(sourcePath, probePath)
        val srcSr = remoteOptions?.let {
            FFmpegBridge.probeSampleRate(probePath, it.headers, it.userAgent)
        } ?: FFmpegBridge.probeSampleRate(probePath)
        val srcBits = remoteOptions?.let {
            FFmpegBridge.probeBitsPerSample(probePath, it.headers, it.userAgent)
        } ?: FFmpegBridge.probeBitsPerSample(probePath)
        val srcCh = remoteOptions?.let {
            FFmpegBridge.probeChannelCount(probePath, it.headers, it.userAgent)
        } ?: FFmpegBridge.probeChannelCount(probePath)
        val sourceIsDsd = isLikelyDsdSource(sourcePath, srcBits, srcSr)
        val sourceDsdRateHz = if (sourceIsDsd) normalizeProbedDsdSourceRateHz(srcSr) else 0
        val dsdTransport = UsbDsdTransport.fromPref(AppPreferences.Player.usbDsdTransportMode)
        val caps = capabilities ?: UsbAudioEngine.getDeviceCapabilities()
        val sourceDsdMode = if (sourceIsDsd) {
            buildSupportedDsdSourceDirectModeConfig(
                sourceDsdRateHz = sourceDsdRateHz,
                requestedTransport = dsdTransport,
                capabilities = caps
            )
        } else {
            null
        }
        val pcmToDsdMode = if (!sourceIsDsd) {
            buildSupportedPcmToDsdModeConfig(
                // PCM→DSD must win over PCM bit-perfect. PlayerController will
                // disable strict bit-perfect for the active USB session when a
                // DSD transport is selected.
                enabled = AppPreferences.Player.dsdConversionEnabled,
                multiplier = AppPreferences.Player.dsdRate,
                requestedTransport = dsdTransport,
                capabilities = caps,
                sourceSampleRate = srcSr
            )
        } else {
            null
        }
        val dsdMode = sourceDsdMode ?: pcmToDsdMode
        val rawSrcBits = if (srcBits > 0) srcBits else 16
        val sourceExceedsUsbPcm = rawSrcBits > 32
        val safeSrcBits = rawSrcBits.coerceAtMost(32)
        val probedSrcCh = if (srcCh > 0) srcCh else 2
        val safeSrcCh = probedSrcCh.coerceAtMost(2)
        val dsdDecodeRate = if (sourceIsDsd) chooseDsdSourcePcmDecodeRate(sourceDsdRateHz) else 0

        // Keep user intent separate from the effective state of this track.
        // WHEN_POSSIBLE enters strict mode only when the current device
        // already exposes an exact PCM geometry; otherwise this track remains on
        // exclusive USB but may resample/convert normally. STRICT keeps the exact
        // contract and lets the later USB profile selection fail rather than mutate PCM.
        val bitPerfectDecision = UsbBitPerfectModePolicy.decidePcmTrack(
            mode = usbBitPerfectPolicyMode,
            capabilities = caps,
            sourceSampleRate = srcSr,
            sourceBits = rawSrcBits,
            sourceChannels = probedSrcCh,
            sourceIsDsd = sourceIsDsd,
            pcmToDsdActive = pcmToDsdMode != null,
        )
        val strictBitPerfect = UsbVolumeModeBitPerfectPolicy.effectivePcmBitPerfect(
            candidate = bitPerfectDecision.effectiveBitPerfect,
            usbVolumeMode = AppPreferences.Player.usbVolumeMode,
        )
        val softwareVolumeSelected = UsbVolumeModeBitPerfectPolicy.softwareVolumeSelected(
            AppPreferences.Player.usbVolumeMode,
        )
        val bitPerfectReason = UsbVolumeModeBitPerfectPolicy.reason(
            candidate = bitPerfectDecision.effectiveBitPerfect || bitPerfectDecision.refusePlayback,
            usbVolumeMode = AppPreferences.Player.usbVolumeMode,
            upstreamReason = bitPerfectDecision.reason,
        )
        val refusePlaybackForBitPerfect = bitPerfectDecision.refusePlayback && !softwareVolumeSelected

        if (usbBitPerfectPolicyMode.requestsBitPerfect && !strictBitPerfect) {
            AppLogger.i(
                tag,
                "USB bit-perfect policy fallback: mode=$usbBitPerfectPolicyMode reason=$bitPerfectReason " +
                    "source=${srcSr}/${rawSrcBits}/${probedSrcCh} " +
                    "deviceRates=${caps?.supportedSampleRates.orEmpty()}"
            )
        }

        val targetRate = when {
            pcmToDsdMode != null -> srcSr.coerceAtLeast(44_100)
            dsdMode != null -> dsdMode.deviceSampleRate
            sourceIsDsd -> dsdDecodeRate
            strictBitPerfect -> srcSr
            else -> selectTargetSampleRate(srcSr, caps?.supportedSampleRates.orEmpty())
        }
        AppLogger.i(
            tag,
            "USB probe: srcSr=$srcSr srcBits=$srcBits srcCh=$srcCh sourceIsDsd=$sourceIsDsd " +
                "sourceDsdRateHz=$sourceDsdRateHz dsdDecodeRate=$dsdDecodeRate " +
                "sourceDsdMode=$sourceDsdMode pcmToDsdMode=$pcmToDsdMode " +
                "effectiveDsdMode=$dsdMode bitPerfectPolicy=$usbBitPerfectPolicyMode " +
                "effectiveBitPerfect=$strictBitPerfect reason=$bitPerfectReason"
        )

        val requestedTargetBits = AudioOutputManager.getUsbTargetBitDepth()
        val pcmOutputMode = UsbPcmOutputMode.fromId(AppPreferences.Player.usbPcmOutputMode)
        val softwarePrecisionBits = if (
            softwareVolumeSelected &&
            requestedTargetBits == AudioOutputManager.BIT_DEPTH_AUTO &&
            pcmOutputMode == UsbPcmOutputMode.AUTO &&
            !sourceIsDsd &&
            pcmToDsdMode == null &&
            !strictBitPerfect
        ) {
            selectSoftwareVolumePrecisionBits(
                sourceBits = safeSrcBits,
                sampleRate = targetRate,
                channels = safeSrcCh.coerceAtLeast(2),
                capabilities = caps,
            )
        } else {
            safeSrcBits
        }
        val targetBits = if (sourceIsDsd) {
            32
        } else if (pcmToDsdMode != null) {
            safeSrcBits
        } else if (strictBitPerfect) {
            safeSrcBits
        } else {
            AudioOutputManager.ffmpegBitsForTarget(requestedTargetBits).let { bits ->
                if (bits > 0) bits.coerceAtMost(32) else softwarePrecisionBits
            }
        }
        val targetChannels = if (strictBitPerfect) safeSrcCh else safeSrcCh.coerceAtLeast(2)
        AppLogger.i(
            tag,
            "USB target: sr=$targetRate bits=$targetBits ch=$targetChannels " +
                "targetBitsPref=$requestedTargetBits (safeSrcBits=$safeSrcBits " +
                "rawSrcBits=$rawSrcBits safeSrcCh=$safeSrcCh sourceIsDsd=$sourceIsDsd " +
                "softwarePrecisionBits=$softwarePrecisionBits pcmMode=$pcmOutputMode)"
        )

        return Target(
            sampleRate = targetRate,
            bitsPerSample = targetBits,
            channels = targetChannels,
            sourceSampleRate = srcSr,
            sourceBitsPerSample = srcBits,
            sourceChannels = srcCh,
            rawSourceBits = rawSrcBits,
            safeSourceBits = safeSrcBits,
            safeSourceChannels = safeSrcCh,
            sourceIsDsd = sourceIsDsd,
            sourceExceedsUsbPcm = sourceExceedsUsbPcm,
            strictBitPerfect = strictBitPerfect,
            bitPerfectPolicyFailureReason = bitPerfectReason.takeIf { refusePlaybackForBitPerfect },
            sourceDsdMode = sourceDsdMode,
            pcmToDsdMode = pcmToDsdMode,
            dsdDecodeRate = dsdDecodeRate,
            requestedTargetBits = requestedTargetBits
        )
    }

    /**
     * Software attenuation benefits from a wider integer container because the source PCM keeps
     * more effective resolution after gain is applied. Only AUTO format selection participates;
     * explicit bit-depth and PCM-container choices remain authoritative.
     *
     * 24-in-32 is deliberately not auto-promoted here: the decoder target carries valid bits but
     * not USB subslot width, so AUTO would reinterpret it as packed-24. True 32-bit and packed
     * 24-bit capabilities are unambiguous and safe to select.
     */
    private fun selectSoftwareVolumePrecisionBits(
        sourceBits: Int,
        sampleRate: Int,
        channels: Int,
        capabilities: UsbDeviceAudioCapabilities?,
    ): Int {
        val safeSourceBits = sourceBits.coerceIn(1, 32)
        val formats = capabilities?.pcmFormats.orEmpty().filter { format ->
            format.channels == channels &&
                (format.sampleRate <= 0 || sampleRate <= 0 || format.sampleRate == sampleRate)
        }
        if (safeSourceBits < 32 && formats.any { it.validBits == 32 && it.subslotBytes == 4 }) {
            return 32
        }
        if (safeSourceBits < 24 && formats.any { it.validBits == 24 && it.subslotBytes == 3 }) {
            return 24
        }
        return safeSourceBits
    }

    fun selectTargetSampleRate(srcSr: Int): Int =
        selectTargetSampleRate(
            srcSr = srcSr,
            advertisedRates = UsbAudioEngine.getDeviceCapabilities()?.supportedSampleRates.orEmpty(),
        )

    internal fun selectTargetSampleRate(srcSr: Int, advertisedRates: List<Int>): Int {
        val userRate = AudioOutputManager.getUsbTargetSampleRate()
        val decision = UsbPcmSampleRatePolicy.choose(
            sourceRate = srcSr,
            requestedRate = userRate,
            advertisedRates = advertisedRates,
        )
        if (decision.requestedRateRejected) {
            AppLogger.w(
                tag,
                "selectUsbTargetSampleRate: requested=${decision.requestedRate} is not advertised by " +
                    "current USB device rates=${decision.advertisedRates}; fallback=${decision.selectedRate}",
            )
        } else {
            AppLogger.i(
                tag,
                "selectUsbTargetSampleRate: srcSr=$srcSr requested=$userRate " +
                    "deviceRates=${decision.advertisedRates} -> ${decision.selectedRate}",
            )
        }
        return decision.selectedRate
    }
}
