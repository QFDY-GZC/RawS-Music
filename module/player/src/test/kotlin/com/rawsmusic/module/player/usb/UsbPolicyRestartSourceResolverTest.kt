package com.rawsmusic.module.player.usb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UsbPolicyRestartSourceResolverTest {
    @Test
    fun metadataWinsForOrdinaryPcm() {
        val resolved = UsbPolicyRestartSourceResolver.resolve(
            inputs(metadataRate = 96_000, metadataBits = 24, metadataChannels = 2),
        )

        assertEquals(96_000, resolved.sampleRate)
        assertEquals(24, resolved.bitsPerSample)
        assertEquals(2, resolved.channels)
        assertFalse(resolved.inferredDsd)
    }

    @Test
    fun probeCanIdentifyDsdWhenMetadataIsIncomplete() {
        val resolved = UsbPolicyRestartSourceResolver.resolve(
            inputs(probedRate = 5_644_800, probedBits = 1, runtimeBits = 32),
        )

        assertEquals(5_644_800, resolved.sampleRate)
        assertEquals(1, resolved.bitsPerSample)
        assertTrue(resolved.inferredDsd)
    }

    @Test
    fun runtimeAndDefaultsFillMissingPcmFields() {
        val resolved = UsbPolicyRestartSourceResolver.resolve(
            inputs(runtimeRate = 192_000, runtimeBits = 32, runtimeChannels = 0),
        )

        assertEquals(192_000, resolved.sampleRate)
        assertEquals(32, resolved.bitsPerSample)
        assertEquals(2, resolved.channels)
    }

    private fun inputs(
        metadataRate: Int = 0,
        metadataBits: Int = 0,
        metadataChannels: Int = 0,
        probedRate: Int = 0,
        probedBits: Int = 0,
        probedChannels: Int = 0,
        runtimeRate: Int = 0,
        runtimeBits: Int = 0,
        runtimeChannels: Int = 0,
    ) = UsbPolicyRestartSourceInputs(
        sourcePath = "/music/test.flac",
        metadataSampleRate = metadataRate,
        metadataBitsPerSample = metadataBits,
        metadataChannels = metadataChannels,
        sourceLooksLikeDsd = false,
        probedSampleRate = probedRate,
        probedBitsPerSample = probedBits,
        probedChannels = probedChannels,
        runtimeSampleRate = runtimeRate,
        runtimeBitsPerSample = runtimeBits,
        runtimeChannels = runtimeChannels,
    )
}
