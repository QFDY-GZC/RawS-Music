package com.rawsmusic.module.scanner.parser

import org.junit.Assert.assertEquals
import org.junit.Test

class CueParserEmbeddedFlacTest {

    @Test
    fun `embedded flac cue ignores stale FILE target and parses only declared tracks`() {
        val cue = """
            REM DATE 2009
            PERFORMER "Franco Corelli"
            TITLE "Icon: Franco Corelli"
            FILE "CDImage.wav" WAVE
              TRACK 01 AUDIO
                TITLE "Bellini track one"
                PERFORMER "Performer one"
                INDEX 01 00:00:00
              TRACK 02 AUDIO
                TITLE "Bellini track two"
                PERFORMER "Performer two"
                INDEX 01 03:37:22
        """.trimIndent()

        val parsed = CueParser.parse(cue)

        assertEquals("CDImage.wav", parsed.fileName)
        assertEquals(2, parsed.tracks.size)
        assertEquals(0L, parsed.tracks[0].startIndexMs)
        assertEquals(217_293L, parsed.tracks[0].endIndexMs)
        assertEquals(217_293L, parsed.tracks[1].startIndexMs)
        assertEquals(-1L, parsed.tracks[1].endIndexMs)
    }
}
