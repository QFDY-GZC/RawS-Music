package com.rawsmusic.module.player.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbRuntimeStatsParserTest {
    @Test
    fun parsesFallbackCompletedFieldAndPacingMode() {
        val stats = requireNotNull(
            UsbRuntimeStatsParser.parseStats(
                "app=1200 usbCompleted=1152 scheduled=1180 expected=1152 " +
                    "buf=64/256 feedback=1 pacingModeId=2 clockVerified=192000"
            )
        )
        assertEquals(1152L, stats.usbOutBytesPerSec)
        assertEquals(64L, stats.bufferUsedBytes)
        assertEquals(256L, stats.bufferCapacityBytes)
        assertEquals("FeedbackDegradedFixed", stats.pacingMode)
        assertEquals(192000, stats.clockRate)
        assertTrue(stats.clockVerified == true)
    }

    @Test
    fun audibleAcceptanceAndDiagnosticsRemainPure() {
        assertTrue(UsbRuntimeStatsParser.isAudibleAccepted("audible=yes completed=1 expected=1"))
        assertFalse(UsbRuntimeStatsParser.isAudibleAccepted("audible=no completed=0 expected=1"))
        assertEquals(
            "native audible state unavailable",
            UsbRuntimeStatsParser.buildAudibleDiagnostics(""),
        )
    }
}
