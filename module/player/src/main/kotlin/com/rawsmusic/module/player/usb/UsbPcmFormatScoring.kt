package com.rawsmusic.module.player.usb

/** Keeps USB PCM capability ranking out of the playback controller. */
internal object UsbPcmFormatScoring {
    fun score(
        format: UsbPcmFormatCapability,
        targetRate: Int,
        targetBits: Int,
        targetSubslot: Int,
        targetChannels: Int,
        pcmMode: UsbPcmOutputMode
    ): Int {
        var score = 0
        if (!format.isPcm || format.isRawData) return Int.MIN_VALUE / 4
        if (targetRate > 0) {
            if (format.sampleRate == targetRate) score += 4000
            else score -= kotlin.math.abs(format.sampleRate - targetRate) / 10
        }
        if (targetChannels > 0) {
            if (format.channels == targetChannels) score += 900
            else if (format.channels >= targetChannels) score += 200
            else score -= 2000
        }
        if (targetBits > 0) {
            when {
                format.validBits == targetBits -> score += 1400
                format.validBits > targetBits -> score += 250
                else -> score -= 1000
            }
        }
        if (targetSubslot > 0) {
            if (format.subslotBytes == targetSubslot) score += 800 else score -= 250
        }
        when (pcmMode) {
            UsbPcmOutputMode.AUTO -> Unit
            UsbPcmOutputMode.PCM_16 -> if (format.validBits == 16 && format.subslotBytes == 2) score += 1500 else score -= 2500
            UsbPcmOutputMode.PCM_24_PACKED -> if (format.validBits == 24 && format.subslotBytes == 3) score += 1800 else score -= 2500
            UsbPcmOutputMode.PCM_24_IN_32 -> if (format.validBits >= 24 && format.subslotBytes == 4) score += 1500 else score -= 2000
            UsbPcmOutputMode.PCM_32 -> if (format.validBits == 32 && format.subslotBytes == 4) score += 1500 else score -= 2000
        }
        if (format.capacityRatioPermille >= 1400) score += 250
        if (format.feedbackEndpoint != 0 && format.feedbackUsage == 1) score += 180
        if (format.feedbackEndpoint != 0 && format.feedbackUsage != 1) score -= 700
        if (format.outSync == 2) score += 120
        return score
    }

    fun choose(
        formats: List<UsbPcmFormatCapability>,
        targetRate: Int,
        targetBits: Int,
        targetSubslot: Int,
        targetChannels: Int,
        pcmMode: UsbPcmOutputMode
    ): UsbPcmFormatCapability? = formats.maxByOrNull {
        score(it, targetRate, targetBits, targetSubslot, targetChannels, pcmMode)
    }
}
