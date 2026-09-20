package com.rawsmusic.module.player.devicecontrol.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbVendorHidSemanticTest {
    @Test
    fun `consumer-control HID is not a DSP candidate`() {
        val iface = hidInterface(listOf(0x0cL))
        assertEquals(UsbVendorControlInventory.HidSemantic.CONSUMER_CONTROL, iface.hidSemantic)
        assertFalse(iface.isLikelyDspHidCandidate)
    }

    @Test
    fun `vendor usage page remains a DSP adapter candidate`() {
        val iface = hidInterface(listOf(0xff00L))
        assertEquals(UsbVendorControlInventory.HidSemantic.VENDOR_DEFINED, iface.hidSemantic)
        assertTrue(iface.isLikelyDspHidCandidate)
    }

    private fun hidInterface(usagePages: List<Long>) = UsbVendorControlInventory.Interface(
        interfaceNumber = 2,
        alternateSetting = 0,
        interfaceClass = 0x03,
        interfaceSubClass = 0,
        interfaceProtocol = 0,
        hidReportDescriptorLength = 43,
        hidReportDescriptorHex = "",
        hidUsagePages = usagePages,
        hidReports = emptyList(),
        endpoints = emptyList(),
    )
}
