package com.rawsmusic.module.player.usb

internal data class UsbCapabilitySnapshotResult(
    val cached: UsbDeviceAudioCapabilities?,
    val effective: UsbDeviceAudioCapabilities?,
)

/** Native capability snapshot parsing and cache semantics. */
internal object UsbCapabilitySnapshotBridge {
    fun resolve(
        cached: UsbDeviceAudioCapabilities?,
        json: String?,
    ): UsbCapabilitySnapshotResult? {
        if (json.isNullOrBlank()) return null
        val parsed = UsbCapabilityJsonParser.parse(json) ?: return UsbCapabilitySnapshotResult(cached, cached)
        val nextCached = if (parsed.formats.isNotEmpty()) {
            UsbCapabilitySnapshotMerger.merge(cached, parsed)
        } else {
            cached
        }
        return UsbCapabilitySnapshotResult(
            cached = nextCached,
            effective = UsbCapabilitySnapshotMerger.merge(nextCached, parsed),
        )
    }
}
