package com.rawsmusic.separation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiVocalActivityMapParserTest {
    @Test
    fun parsesVersionedMapAndKeepsOrderedSpans() {
        val map = AiVocalActivityMapParser.parse(
            json = """
                {
                  "schemaVersion": 1,
                  "sourceIdentity": "source-a",
                  "analyzerVersion": "v1",
                  "sampleRate": 44100,
                  "hopMs": 10,
                  "durationMs": 2000,
                  "spans": [
                    {"startMs": 120, "endMs": 440, "confidence": 0.8},
                    {"startMs": 900, "endMs": 1200, "confidence": 0.4}
                  ]
                }
            """.trimIndent(),
            expectedSourceIdentity = "source-a",
            expectedAnalyzerVersion = "v1",
        )

        assertEquals(44100, map.sampleRate)
        assertEquals(2, map.spans.size)
        assertEquals(120L, map.spans.first().startMs)
        assertTrue(map.isUsable)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsSourceIdentityMismatch() {
        AiVocalActivityMapParser.parse(
            json = """
                {
                  "schemaVersion": 1,
                  "sourceIdentity": "source-a",
                  "analyzerVersion": "v1",
                  "sampleRate": 44100,
                  "hopMs": 10,
                  "durationMs": 2000,
                  "spans": []
                }
            """.trimIndent(),
            expectedSourceIdentity = "source-b",
            expectedAnalyzerVersion = "v1",
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsInvalidConfidence() {
        AiVocalActivityMapParser.parse(
            json = """
                {
                  "schemaVersion": 1,
                  "sourceIdentity": "source-a",
                  "analyzerVersion": "v1",
                  "sampleRate": 44100,
                  "hopMs": 10,
                  "durationMs": 2000,
                  "spans": [{"startMs": 0, "endMs": 200, "confidence": 1.5}]
                }
            """.trimIndent(),
            expectedSourceIdentity = "source-a",
            expectedAnalyzerVersion = "v1",
        )
    }
}
