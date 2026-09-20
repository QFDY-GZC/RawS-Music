package com.rawsmusic.module.player.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbVolumeOwnershipPolicyTest {
    @Test fun bitPerfectWithoutHardwareVolumeIsFixedUnity() {
        assertEquals(
            UsbVolumePath.Fixed,
            UsbVolumeOwnershipPolicy.resolve(
                bitPerfect = true,
                hardwareVolumeEffective = false,
                dsdConversionEnabled = false,
                dsdSourceDirect = false,
                fixedDigitalVolume = false,
            ),
        )
    }

    @Test fun bitPerfectWithValidatedFeatureUnitUsesHardwareVolume() {
        assertEquals(
            UsbVolumePath.HardwareUserVolume,
            UsbVolumeOwnershipPolicy.resolve(
                bitPerfect = true,
                hardwareVolumeEffective = true,
                dsdConversionEnabled = false,
                dsdSourceDirect = false,
                fixedDigitalVolume = false,
            ),
        )
    }

    @Test fun processedPcmWithoutFeatureUnitKeepsSoftwareVolume() {
        assertEquals(
            UsbVolumePath.Software,
            UsbVolumeOwnershipPolicy.resolve(
                bitPerfect = false,
                hardwareVolumeEffective = false,
                dsdConversionEnabled = false,
                dsdSourceDirect = false,
                fixedDigitalVolume = false,
            ),
        )
    }
}
