package com.rawsmusic.module.player.usb

enum class UsbPcmOutputMode(val id: Int) {
    AUTO(0),
    PCM_16(1),
    PCM_24_PACKED(2),
    PCM_24_IN_32(3),
    PCM_32(4);

    companion object {
        fun fromId(id: Int): UsbPcmOutputMode {
            return entries.firstOrNull { it.id == id } ?: AUTO
        }
    }
}
