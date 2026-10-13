package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class EncodedGaplessProbeRoutingTest {
    private val id3 = byteArrayOf(73, 68, 51, 4, 0, 0, 0, 7, 20, 118, 0, 0)

    @Test fun id3TaggedAacDoesNotRunMp3ByteByByteSyncScan() {
        for (extension in listOf("aac", "M4A", "mp4")) {
            assertEquals(EncodedGaplessMetadata.Codec.AAC_ITUNSMPB,
                EncodedGaplessMetadataProbe.probeCodec("song.$extension", id3))
        }
    }

    @Test fun actualMp3StillUsesLameProbe() {
        assertEquals(EncodedGaplessMetadata.Codec.MP3_LAME,
            EncodedGaplessMetadataProbe.probeCodec("song.mp3", id3))
        assertNull(EncodedGaplessMetadataProbe.probeCodec("song.flac", "fLaC".toByteArray()))
    }
}
