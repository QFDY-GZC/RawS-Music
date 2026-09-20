package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Test

class DecoderGaplessAuditTest {
    private val metadata = EncodedGaplessMetadata(
        codec = EncodedGaplessMetadata.Codec.MP3_LAME,
        sourceSampleRate = 44100,
        encodedFrames = 115200L,
        declaredDelayFrames = 576,
        declaredPaddingFrames = 1573,
        expectedAudibleFrames = 113051L,
        source = "test",
    )

    @Test
    fun `recognizes FFmpeg decoder managed trim`() {
        val audit = DecoderGaplessAudit(metadata, outputSampleRate = 44100, outputFrameSize = 4)
        audit.recordDecodedBytes((113051L * 4L).toInt())
        val result = audit.finish()
        assertEquals(DecoderGaplessVerification.DECODER_TRIMMED, result.verification)
    }

    @Test
    fun `recognizes untrimmed encoded frame count`() {
        val audit = DecoderGaplessAudit(metadata, outputSampleRate = 44100, outputFrameSize = 4)
        audit.recordDecodedBytes((115200L * 4L).toInt())
        val result = audit.finish()
        assertEquals(DecoderGaplessVerification.DECODER_UNTRIMMED, result.verification)
    }

    @Test
    fun `seek invalidates whole track frame audit`() {
        val audit = DecoderGaplessAudit(metadata, outputSampleRate = 44100, outputFrameSize = 4)
        audit.recordDecodedBytes(4096)
        audit.invalidate("seek")
        assertEquals(DecoderGaplessVerification.INVALIDATED, audit.finish().verification)
    }
}
