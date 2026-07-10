package com.rawsmusic.module.player

import com.rawsmusic.core.common.ffmpeg.FFmpegBridge
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbAudioEngine
import com.rawsmusic.module.player.usb.UsbDsdModeConfig
import com.rawsmusic.module.player.usb.UsbDsdTransport
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
    private val tag: String
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
        val sourceDsdMode: UsbDsdModeConfig?,
        val pcmToDsdMode: UsbDsdModeConfig?,
        val dsdDecodeRate: Int,
        val requestedTargetBits: Int
    )

    private val supportedSampleRates = intArrayOf(
        44_100, 48_000, 88_200, 96_000, 176_400, 192_000
    )

    fun resolve(sourcePath: String, usbBitPerfectMode: Boolean): Target {
        val srcSr = FFmpegBridge.probeSampleRate(sourcePath)
        val srcBits = FFmpegBridge.probeBitsPerSample(sourcePath)
        val srcCh = FFmpegBridge.probeChannelCount(sourcePath)
        val sourceIsDsd = isLikelyDsdSource(sourcePath, srcBits, srcSr)
        val sourceDsdRateHz = if (sourceIsDsd) normalizeProbedDsdSourceRateHz(srcSr) else 0
        val dsdTransport = UsbDsdTransport.fromPref(AppPreferences.Player.usbDsdTransportMode)
        val caps = UsbAudioEngine.getDeviceCapabilities()
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
        val safeSrcCh = if (srcCh > 0) srcCh else 2
        val dsdDecodeRate = if (sourceIsDsd) chooseDsdSourcePcmDecodeRate(sourceDsdRateHz) else 0

        // 64-bit PCM/float cannot be sent to typical USB DAC PCM alt-settings.
        // Decode it to S32LE, then let the USB engine select 32-bit/subslot4.
        val strictBitPerfect = usbBitPerfectMode &&
            !sourceExceedsUsbPcm &&
            !sourceIsDsd &&
            pcmToDsdMode == null

        if (usbBitPerfectMode && sourceExceedsUsbPcm) {
            AppLogger.w(
                tag,
                "USB source ${rawSrcBits}bit exceeds USB PCM engine limit; " +
                    "disable strict bit-perfect for this track and decode to 32-bit"
            )
        }
        if (sourceIsDsd && usbBitPerfectMode) {
            AppLogger.w(
                tag,
                "USB source looks like DSD (${srcSr}Hz/${rawSrcBits}bit); " +
                    "strict PCM bit-perfect bypass disabled for this track"
            )
        }

        val targetRate = when {
            pcmToDsdMode != null -> srcSr.coerceAtLeast(44_100)
            dsdMode != null -> dsdMode.deviceSampleRate
            sourceIsDsd -> dsdDecodeRate
            strictBitPerfect -> srcSr
            else -> selectTargetSampleRate(srcSr)
        }
        AppLogger.i(
            tag,
            "USB probe: srcSr=$srcSr srcBits=$srcBits srcCh=$srcCh sourceIsDsd=$sourceIsDsd " +
                "sourceDsdRateHz=$sourceDsdRateHz dsdDecodeRate=$dsdDecodeRate " +
                "sourceDsdMode=$sourceDsdMode pcmToDsdMode=$pcmToDsdMode " +
                "effectiveDsdMode=$dsdMode bitPerfect=$usbBitPerfectMode " +
                "strictThisTrack=$strictBitPerfect"
        )

        val requestedTargetBits = AudioOutputManager.getUsbTargetBitDepth()
        val targetBits = if (sourceIsDsd) {
            32
        } else if (pcmToDsdMode != null) {
            safeSrcBits
        } else if (strictBitPerfect) {
            safeSrcBits
        } else {
            AudioOutputManager.ffmpegBitsForTarget(requestedTargetBits).let { bits ->
                if (bits > 0) bits.coerceAtMost(32) else when {
                    safeSrcBits <= 16 -> 16
                    safeSrcBits <= 24 -> 24
                    else -> 32
                }
            }
        }
        val targetChannels = if (strictBitPerfect) safeSrcCh else safeSrcCh.coerceAtLeast(2)
        AppLogger.i(
            tag,
            "USB target: sr=$targetRate bits=$targetBits ch=$targetChannels " +
                "targetBitsPref=$requestedTargetBits (safeSrcBits=$safeSrcBits " +
                "rawSrcBits=$rawSrcBits safeSrcCh=$safeSrcCh sourceIsDsd=$sourceIsDsd)"
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
            sourceDsdMode = sourceDsdMode,
            pcmToDsdMode = pcmToDsdMode,
            dsdDecodeRate = dsdDecodeRate,
            requestedTargetBits = requestedTargetBits
        )
    }

    fun selectTargetSampleRate(srcSr: Int): Int {
        val userRate = AudioOutputManager.getUsbTargetSampleRate()
        if (userRate > 0) {
            AppLogger.i(tag, "selectUsbTargetSampleRate: srcSr=$srcSr -> user=$userRate")
            return userRate
        }
        if (srcSr <= 0) return 48_000
        var best = supportedSampleRates[0]
        for (rate in supportedSampleRates) {
            if (rate >= srcSr) {
                best = rate
                break
            }
            best = rate
        }
        AppLogger.i(tag, "selectUsbTargetSampleRate: srcSr=$srcSr -> $best")
        return best
    }
}
