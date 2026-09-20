package com.rawsmusic.module.player.usb

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UsbNativeSessionPolicyTest {
    @Test
    fun serializesStableSchema() {
        val values = UsbNativeSessionPolicy(
            exclusive = true,
            bitPerfect = true,
            hardwareVolumeRequested = true,
            pcmOutputMode = UsbPcmOutputMode.PCM_24_PACKED,
            dsdConversionEnabled = true,
            dsdRate = 256,
            dsdConversionType = 2,
            dsdDitherEnabled = true,
            dsdDoPEnabled = true,
            force1msPacket = true,
            noClockSet = true,
            noFeedback = true,
            preferSafeAlt = true,
            safeMode = true,
            lastGoodAlt = 2,
            lastGoodSampleRate = 48_000,
            lastGoodValidBits = 24,
            lastGoodSubslotBytes = 3,
        ).toNativeIntArray()

        assertEquals(UsbNativeSessionPolicy.NATIVE_FIELD_COUNT, values.size)
        assertEquals(UsbNativeSessionPolicy.SCHEMA_VERSION, values[0])
        assertEquals(1, values[1])
        assertEquals(1, values[2])
        assertEquals(1, values[3])
        assertEquals(UsbPcmOutputMode.PCM_24_PACKED.id, values[4])
        assertEquals(256, values[6])
        assertEquals(1, values[14])
        assertEquals(2, values[20])
        assertEquals(48_000, values[21])
        assertEquals(24, values[22])
        assertEquals(3, values[23])
    }

    @Test
    fun sourceDsdDirectIsRawFixedFromNativeInitEvenWhenUserBitPerfectIsOff() {
        assertTrue(
            resolveUsbNativeSessionBitPerfect(
                requestedBitPerfect = false,
                fixedDigitalVolume = false,
                sourceDsdDirect = true,
            )
        )
    }

    @Test
    fun mutablePcmAndPcmToDsdDoNotBecomeBitPerfectByAccident() {
        assertFalse(
            resolveUsbNativeSessionBitPerfect(
                requestedBitPerfect = false,
                fixedDigitalVolume = false,
                sourceDsdDirect = false,
            )
        )
    }

    @Test
    fun explicitBitPerfectOrFixedDigitalStillOwnsRawContract() {
        assertTrue(resolveUsbNativeSessionBitPerfect(true, false, false))
        assertTrue(resolveUsbNativeSessionBitPerfect(false, true, false))
    }
}
