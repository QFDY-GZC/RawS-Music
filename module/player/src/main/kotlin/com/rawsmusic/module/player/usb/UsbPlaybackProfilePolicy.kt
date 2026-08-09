package com.rawsmusic.module.player.usb

import com.rawsmusic.module.player.AudioOutputManager

/**
 * Pure playback-profile planning for the USB route.
 *
 * This class deliberately does not open a device, touch Feature Unit volume,
 * or mutate native streaming state. It only converts the current preferences
 * and source/DSD intent into the values required by the USB session opener.
 */
internal data class UsbPlaybackProfilePlan(
    val dsdMode: UsbDsdModeConfig?,
    val desiredDsdSessionKey: String?,
    val pcmDsdActive: Boolean,
    val dsdTransportActive: Boolean,
    val sourceBitsForUsb: Int,
    val strictBitPerfectForUsb: Boolean,
    val deviceSampleRate: Int,
    val deviceBits: Int,
    val deviceSubslot: Int,
)

internal object UsbPlaybackProfilePolicy {
    fun plan(
        sampleRate: Int,
        sourceBits: Int,
        requestedTargetRate: Int,
        requestedTargetBits: Int,
        pcmMode: UsbPcmOutputMode,
        bitPerfect: Boolean,
        sourceIsDsd: Boolean,
        sourceDsdMode: UsbDsdModeConfig?,
        pcmToDsdMode: UsbDsdModeConfig?,
        dsdPcmFallbackRate: Int,
        targetBitDepth: ((Int, Int) -> Int)? = null,
        targetSubslot: ((Int, Int) -> Int)? = null,
    ): UsbPlaybackProfilePlan {
        val dsdMode = sourceDsdMode ?: pcmToDsdMode
        val desiredDsdSessionKey = when {
            dsdMode == null -> null
            sourceIsDsd -> "SOURCE:${dsdMode.multiplier}:${dsdMode.transport}:${dsdMode.deviceSampleRate}"
            else -> "P2D:${dsdMode.multiplier}:${dsdMode.transport}:${dsdMode.deviceSampleRate}"
        }
        val pcmDsdActive = pcmToDsdMode != null
        val dsdTransportActive = dsdMode != null
        val sourceBitsForUsb = sourceBits.coerceAtMost(32)
        val strictBitPerfectForUsb = bitPerfect && sourceBits <= 32 && !pcmDsdActive
        val deviceSampleRate = when {
            dsdMode != null -> dsdMode.deviceSampleRate
            sourceIsDsd -> dsdPcmFallbackRate
            strictBitPerfectForUsb || dsdTransportActive || requestedTargetRate <= 0 -> sampleRate
            else -> requestedTargetRate
        }

        val requestedFormat = when {
            dsdMode != null -> Pair(dsdMode.deviceBits, dsdMode.deviceSubslot)
            strictBitPerfectForUsb || pcmDsdActive -> Pair(
                sourceBitsForUsb,
                UsbAudioFormatPolicy.preferredSubslotFor(
                    sourceBits = sourceBitsForUsb,
                    requestedTargetBits = requestedTargetBits,
                    pcmMode = pcmMode,
                    strictBitPerfect = strictBitPerfectForUsb,
                    pcmDsdActive = pcmDsdActive,
                ),
            )
            pcmMode == UsbPcmOutputMode.PCM_16 -> Pair(16, 2)
            pcmMode == UsbPcmOutputMode.PCM_24_PACKED -> Pair(24, 3)
            pcmMode == UsbPcmOutputMode.PCM_24_IN_32 -> Pair(24, 4)
            pcmMode == UsbPcmOutputMode.PCM_32 -> Pair(32, 4)
            requestedTargetBits <= 0 -> Pair(
                sourceBitsForUsb,
                UsbAudioFormatPolicy.preferredSubslotFor(
                    sourceBits = sourceBitsForUsb,
                    requestedTargetBits = requestedTargetBits,
                    pcmMode = pcmMode,
                    strictBitPerfect = strictBitPerfectForUsb,
                    pcmDsdActive = pcmDsdActive,
                ),
            )
            else -> {
                val resolveBits = requireNotNull(targetBitDepth) { "targetBitDepth is required for a fixed target depth" }
                val resolveSubslot = requireNotNull(targetSubslot) { "targetSubslot is required for a fixed target depth" }
                Pair(
                    resolveBits(requestedTargetBits, sourceBitsForUsb).coerceAtMost(32),
                    resolveSubslot(requestedTargetBits, sourceBitsForUsb).coerceAtMost(4),
                )
            }
        }

        return UsbPlaybackProfilePlan(
            dsdMode = dsdMode,
            desiredDsdSessionKey = desiredDsdSessionKey,
            pcmDsdActive = pcmDsdActive,
            dsdTransportActive = dsdTransportActive,
            sourceBitsForUsb = sourceBitsForUsb,
            strictBitPerfectForUsb = strictBitPerfectForUsb,
            deviceSampleRate = deviceSampleRate,
            deviceBits = requestedFormat.first.coerceAtMost(32),
            deviceSubslot = requestedFormat.second.coerceAtMost(4),
        )
    }
}
