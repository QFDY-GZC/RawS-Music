package com.rawsmusic.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiSpleeterActivityParserTest {
    @Test
    fun convertsActivityEnvelopeIntoSeparatedSpans() {
        val values = FloatArray(200) { 0.01f }
        for (index in 5..12) values[index] = 1.0f
        for (index in 62..70) values[index] = 1.0f

        val map = AiSpleeterActivityParser.parse(
            raw = "OK|44100|441|2000|${values.joinToString(",")}",
            sourceIdentity = "audio-a",
            analyzerVersion = "spleeter.2stem.fp16:1.0.0",
        )

        assertEquals(44100, map.sampleRate)
        assertEquals(10L, map.hopMs)
        assertEquals(2000L, map.durationMs)
        assertEquals(2, map.spans.size)
        assertEquals(0L, map.spans[0].startMs)
        assertTrue(map.spans[0].endMs < map.spans[1].startMs)
        assertTrue(map.spans.all { it.confidence in 0f..1f })
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsMissingActivityFrames() {
        AiSpleeterActivityParser.parse(
            raw = "OK|44100|441|1000|",
            sourceIdentity = "audio-a",
            analyzerVersion = "spleeter.2stem.fp16:1.0.0",
        )
    }
}
