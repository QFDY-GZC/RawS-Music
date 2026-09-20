package com.rawsmusic.module.player.usb

import com.rawsmusic.module.data.prefs.UsbBitPerfectMode
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbBitPerfectModePolicyTest {
    private val caps = UsbDeviceAudioCapabilities(
        deviceName = "fixture",
        vendorId = 0x0495,
        productId = 0x3042,
        formats = listOf(
            UsbPcmFormatCapability(48_000, 2, 16, 2, 1, 1, 0x07, 0),
            UsbPcmFormatCapability(96_000, 2, 16, 2, 1, 2, 0x07, 0),
            UsbPcmFormatCapability(48_000, 2, 24, 3, 1, 3, 0x07, 0),
        ),
    )

    @Test fun whenPossibleFallsBackForUnsupported441Family() {
        val d = UsbBitPerfectModePolicy.decidePcmTrack(
            UsbBitPerfectMode.WHEN_POSSIBLE, caps, 44_100, 16, 2, false, false,
        )
        assertFalse(d.effectiveBitPerfect)
    }

    @Test fun whenPossiblePromotesExact48k16Stereo() {
        val d = UsbBitPerfectModePolicy.decidePcmTrack(
            UsbBitPerfectMode.WHEN_POSSIBLE, caps, 48_000, 16, 2, false, false,
        )
        assertTrue(d.effectiveBitPerfect)
    }

    @Test fun whenPossibleRequiresExactBitDepth() {
        val d = UsbBitPerfectModePolicy.decidePcmTrack(
            UsbBitPerfectMode.WHEN_POSSIBLE, caps, 48_000, 32, 2, false, false,
        )
        assertFalse(d.effectiveBitPerfect)
    }


    @Test fun whenPossibleDoesNotPromoteUac1RangeWithoutClockControl() {
        val unsafeUac1 = UsbDeviceAudioCapabilities(
            deviceName = "uac1-range",
            vendorId = 0x0495,
            productId = 0x3042,
            formats = listOf(
                UsbPcmFormatCapability(
                    sampleRate = 48_000,
                    channels = 2,
                    validBits = 24,
                    subslotBytes = 3,
                    interfaceNumber = 1,
                    altSetting = 2,
                    outEndpoint = 0x07,
                    feedbackEndpoint = 0,
                    protocol = 1,
                    uac1SamplingFrequencyControl = false,
                    exactRateProvable = false,
                ),
            ),
        )
        val d = UsbBitPerfectModePolicy.decidePcmTrack(
            UsbBitPerfectMode.WHEN_POSSIBLE, unsafeUac1, 48_000, 24, 2, false, false,
        )
        assertFalse(d.effectiveBitPerfect)
        assertTrue(d.reason == "when_possible_clock_unverified")
    }

    @Test fun strictKeepsExactContractEvenBeforeCapabilitiesAreKnown() {
        val d = UsbBitPerfectModePolicy.decidePcmTrack(
            UsbBitPerfectMode.STRICT, null, 44_100, 16, 2, false, false,
        )
        assertTrue(d.effectiveBitPerfect)
    }

    @Test fun strictRefusesUnsupportedSourceGeometryInsteadOfDowngrading() {
        val d = UsbBitPerfectModePolicy.decidePcmTrack(
            UsbBitPerfectMode.STRICT, caps, 96_000, 64, 2, false, false,
        )
        assertFalse(d.effectiveBitPerfect)
        assertTrue(d.refusePlayback)
    }

    @Test fun offNeverPromotes() {
        val d = UsbBitPerfectModePolicy.decidePcmTrack(
            UsbBitPerfectMode.OFF, caps, 48_000, 16, 2, false, false,
        )
        assertFalse(d.effectiveBitPerfect)
    }
}
