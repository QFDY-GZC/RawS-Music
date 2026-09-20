package com.rawsmusic.module.player.usb

import org.junit.Assert.assertEquals
import org.junit.Test

class UsbAudioFormatPolicyTest {
    @Test fun packed24InFourByteSubslotKeepsSourceValidBits() {
        val config = UsbAudioFormatPolicy.selectConfigForFormat(
            sampleRate = 96_000,
            bits = 24,
            subslot = 4,
            channels = 2,
            sourceBits = 24,
        )
        val resolved = requireNotNull(config)
        assertEquals(24, resolved.bits)
        assertEquals(4, resolved.subslot)
        assertEquals(24, resolved.sourceBits)
    }

    @Test fun processed32To24StillReportsOriginalSourcePrecision() {
        val config = UsbAudioFormatPolicy.selectConfigForFormat(
            sampleRate = 48_000,
            bits = 24,
            subslot = 4,
            channels = 2,
            sourceBits = 32,
        )
        val resolved = requireNotNull(config)
        assertEquals(32, resolved.sourceBits)
    }
}
