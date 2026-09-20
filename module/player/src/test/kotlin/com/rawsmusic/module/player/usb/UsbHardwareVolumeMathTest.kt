package com.rawsmusic.module.player.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbHardwareVolumeMathTest {
    @Test fun newDeviceSafeDefaultUsesMinus32DbCandidate() {
        assertEquals(-8_192, UsbHardwareVolumeMath.conservativeSafeRaw(-25_600, 0, 256))
        assertEquals(-8_192, UsbHardwareVolumeMath.conservativeSafeRaw(-8_192, 0, 256))
    }

    @Test fun newDeviceSafeDefaultRefusesRangesThatCannotReachMinus32Db() {
        assertEquals(null, UsbHardwareVolumeMath.conservativeSafeRaw(-7_680, 0, 256))
    }

}
