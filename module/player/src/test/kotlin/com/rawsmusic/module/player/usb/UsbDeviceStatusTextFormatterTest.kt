package com.rawsmusic.module.player.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbDeviceStatusTextFormatterTest {
    @Test
    fun formatsVendorAndProductIds() {
        assertEquals(
            "VID 1234 / PID ABCD",
            UsbDeviceStatusTextFormatter.formatVendorProductId(0x1234, 0xABCD),
        )
        assertEquals("未知", UsbDeviceStatusTextFormatter.formatVendorProductId(0, 0))
    }

    @Test
    fun formatsDsdAndOutputChain() {
        assertEquals(
            "DSD64 / 1bit / 2.8224 MHz / 2ch",
            UsbDeviceStatusTextFormatter.buildDsdFormatText(64, 2_822_400, 2),
        )
        assertEquals(
            "PCM 直通 → DAC",
            UsbDeviceStatusTextFormatter.buildOutputChainText(
                sourceIsDsd = false,
                dsdMode = null,
                bitPerfect = true,
                needsPcmAdapter = false,
            ),
        )
    }
}
