package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File

class EncodedGaplessMetadataTest {
    @Test
    fun `parses Xing LAME delay and padding`() {
        val file = File.createTempFile("gapless_lame", ".mp3")
        try {
            val frame = ByteArray(417)
            frame[0] = 0xFF.toByte()
            frame[1] = 0xFB.toByte() // MPEG1 Layer III, no CRC.
            frame[2] = 0x90.toByte() // 128 kbps, 44.1 kHz.
            frame[3] = 0x40.toByte() // joint stereo.
            val xing = 4 + 32
            "Xing".toByteArray(Charsets.ISO_8859_1).copyInto(frame, xing)
            frame[xing + 7] = 0x01 // flags: frame count.
            val frameCount = 100L
            frame[xing + 8] = ((frameCount ushr 24) and 0xFF).toByte()
            frame[xing + 9] = ((frameCount ushr 16) and 0xFF).toByte()
            frame[xing + 10] = ((frameCount ushr 8) and 0xFF).toByte()
            frame[xing + 11] = (frameCount and 0xFF).toByte()
            val lame = xing + 12
            "LAME3.100".toByteArray(Charsets.ISO_8859_1).copyInto(frame, lame)
            val delay = 576
            val padding = 1573
            frame[lame + 21] = (delay ushr 4).toByte()
            frame[lame + 22] = (((delay and 0x0F) shl 4) or (padding ushr 8)).toByte()
            frame[lame + 23] = padding.toByte()
            file.writeBytes(frame)

            val result = EncodedGaplessMetadataProbe.parseMp3(file.absolutePath)
            assertNotNull(result)
            requireNotNull(result)
            assertEquals(EncodedGaplessMetadata.Codec.MP3_LAME, result.codec)
            assertEquals(44100, result.sourceSampleRate)
            assertEquals(115200L, result.encodedFrames)
            assertEquals(delay, result.declaredDelayFrames)
            assertEquals(padding, result.declaredPaddingFrames)
            assertEquals(113051L, result.expectedAudibleFrames)
        } finally {
            file.delete()
        }
    }

    @Test
    fun `parses iTunSMPB audible sample count`() {
        val result = EncodedGaplessMetadataProbe.parseItunSmpbValue(
            raw = " 00000000 00000840 000001F4 0000000000010000 00000000",
            sourceSampleRate = 44100,
        )
        assertNotNull(result)
        requireNotNull(result)
        assertEquals(2112, result.declaredDelayFrames)
        assertEquals(500, result.declaredPaddingFrames)
        assertEquals(65536L, result.expectedAudibleFrames)
        assertEquals(68148L, result.encodedFrames)
    }

    @Test
    fun `rejects malformed iTunSMPB`() {
        assertNull(EncodedGaplessMetadataProbe.parseItunSmpbValue("bad", 44100))
    }
}
