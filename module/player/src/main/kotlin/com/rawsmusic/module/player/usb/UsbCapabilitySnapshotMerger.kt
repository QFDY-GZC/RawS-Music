package com.rawsmusic.module.player.usb

/** Merges descriptor snapshots without owning JNI calls or playback state. */
internal object UsbCapabilitySnapshotMerger {
    fun merge(
        previous: UsbDeviceAudioCapabilities?,
        latest: UsbDeviceAudioCapabilities
    ): UsbDeviceAudioCapabilities {
        if (previous == null) return latest
        if (previous.vendorId != latest.vendorId || previous.productId != latest.productId) {
            return latest
        }

        val mergedFormats = (previous.formats + latest.formats)
            .distinctBy {
                listOf(
                    it.sampleRate,
                    it.channels,
                    it.validBits,
                    it.subslotBytes,
                    it.interfaceNumber,
                    it.altSetting,
                    it.outEndpoint,
                    it.feedbackEndpoint,
                    it.isPcm,
                    it.isRawData,
                    it.outSync,
                    it.outUsage,
                    it.feedbackUsage
                ).joinToString("|")
            }

        return UsbDeviceAudioCapabilities(
            deviceName = latest.deviceName.ifBlank { previous.deviceName },
            vendorId = latest.vendorId,
            productId = latest.productId,
            formats = mergedFormats
        )
    }
}
