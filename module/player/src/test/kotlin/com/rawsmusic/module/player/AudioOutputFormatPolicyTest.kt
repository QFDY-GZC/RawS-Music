package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AudioOutputFormatPolicyTest {
    @Test
    fun rawDsdUsesOneBytePerChannelDecoderContainer() {
        assertEquals(1, AudioOutputFormatPolicy.decoderBytesPerSample(1))
    }

    @Test
    fun pcmKeepsS16AndS32DecoderContainers() {
        assertEquals(2, AudioOutputFormatPolicy.decoderBytesPerSample(0))
        assertEquals(2, AudioOutputFormatPolicy.decoderBytesPerSample(16))
        assertEquals(4, AudioOutputFormatPolicy.decoderBytesPerSample(24))
        assertEquals(4, AudioOutputFormatPolicy.decoderBytesPerSample(32))
    }
}
