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
    @Test
    fun parsesCachedPcmInputDiagnosticsForInAppExport() {
        val stats = requireNotNull(
            UsbRuntimeStatsParser.parseStats(
                "app=384000 usb=288000 expected=288000 pcmDiag=1 pcmProto=1 " +
                    "pcmSrcFrame=8 pcmDstFrame=6 pcmAdapter=S32_TO_S24 pcmResample=0 " +
                    "pcmSamples=256 pcmNonSilent=250 pcmLowZero=248 pcmSignTop=12 " +
                    "pcmFirst16=00112233445566778899AABBCCDDEEFF"
            )
        )
        assertTrue(stats.pcmInputDiagReady)
        assertEquals(1, stats.pcmProtocol)
        assertEquals(8, stats.pcmSourceFrameBytes)
        assertEquals(6, stats.pcmDeviceFrameBytes)
        assertEquals("S32_TO_S24", stats.pcmAdapter)
        assertFalse(stats.pcmNeedsResample)
        assertEquals(256, stats.pcmSamples)
        assertEquals(250, stats.pcmNonSilent)
        assertEquals(248, stats.pcmLowZero)
        assertEquals(12, stats.pcmSignExtendedTop)
        assertEquals("00112233445566778899AABBCCDDEEFF", stats.pcmFirst16Hex)
    }

}
