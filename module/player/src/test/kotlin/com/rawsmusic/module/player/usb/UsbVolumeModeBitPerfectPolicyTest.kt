package com.rawsmusic.module.player.usb

import com.rawsmusic.module.data.prefs.UsbBitPerfectMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbVolumeModeBitPerfectPolicyTest {
    @Test
    fun softwareVolumeDisablesEffectivePcmBitPerfect() {
        assertFalse(UsbVolumeModeBitPerfectPolicy.effectivePcmBitPerfect(true, 0))
        assertFalse(UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(UsbBitPerfectMode.STRICT, 0))
        assertEquals(
            "software_volume_selected_disables_effective_bitperfect",
            UsbVolumeModeBitPerfectPolicy.reason(true, 0, "strict_requested"),
        )
    }

    @Test
    fun hardwareAndFixedModesKeepBitPerfectEligible() {
        assertTrue(UsbVolumeModeBitPerfectPolicy.effectivePcmBitPerfect(true, 1))
        assertTrue(UsbVolumeModeBitPerfectPolicy.effectivePcmBitPerfect(true, 2))
        assertTrue(UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(UsbBitPerfectMode.STRICT, 1))
        assertTrue(UsbVolumeModeBitPerfectPolicy.shouldPreArmStrict(UsbBitPerfectMode.STRICT, 2))
        assertFalse(UsbVolumeModeBitPerfectPolicy.effectivePcmBitPerfect(false, 1))
    }
}
