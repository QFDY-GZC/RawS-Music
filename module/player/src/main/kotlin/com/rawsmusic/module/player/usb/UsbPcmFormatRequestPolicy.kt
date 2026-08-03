package com.rawsmusic.module.player.usb

/** Immutable PCM output request selected from the user's USB output preference. */
internal data class UsbPcmFormatRequest(
    val mode: UsbPcmOutputMode,
    val targetValidBits: Int,
    val targetSubslotBytes: Int
)

/** Maps persisted output mode ids to the native USB PCM container request. */
internal object UsbPcmFormatRequestPolicy {
    fun fromModeId(modeId: Int): UsbPcmFormatRequest {
        val mode = UsbPcmOutputMode.fromId(modeId)
        return when (mode) {
            UsbPcmOutputMode.AUTO -> UsbPcmFormatRequest(mode, 0, 0)
            UsbPcmOutputMode.PCM_16 -> UsbPcmFormatRequest(mode, 16, 2)
            UsbPcmOutputMode.PCM_24_PACKED -> UsbPcmFormatRequest(mode, 24, 3)
            UsbPcmOutputMode.PCM_24_IN_32 -> UsbPcmFormatRequest(mode, 24, 4)
            UsbPcmOutputMode.PCM_32 -> UsbPcmFormatRequest(mode, 32, 4)
        }
    }
}
