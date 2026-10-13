package com.rawsmusic.module.scanner

import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddedCueSheetResolverTest {
    private val cue = """
        REM DATE 2009
        PERFORMER "Franco Corelli"
        TITLE "Icon: Franco Corelli"
        FILE "CDImage.wav" WAVE
          TRACK 01 AUDIO
            INDEX 01 00:00:00
          TRACK 02 AUDIO
            INDEX 01 03:37:22
    """.trimIndent()

    @Test
    fun taglibRawPropertyCueSheetWins() {
        assertEquals(
            cue,
            EmbeddedCueSheetResolver.resolve(
                tagLibProperties = mapOf("cuesheet" to listOf(cue)),
                ffmpegInfo = emptyMap(),
            ),
        )
    }

    @Test
    fun ffmpegStreamRawCueSheetIsAccepted() {
        assertEquals(
            cue,
            EmbeddedCueSheetResolver.resolve(
                tagLibProperties = emptyMap(),
                ffmpegInfo = mapOf("stream_0_raw_tag_cuesheet" to cue),
            ),
        )
    }
}
