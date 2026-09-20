package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTimingCorrectionTest {
    @Test
    fun `voice activity spans are queryable by absolute line window`() {
        val map = VoiceActivityMap(
            sourceIdentity = "song:size:duration",
            analyzerVersion = "v1",
            sampleRate = 44_100,
            hopMs = 10L,
            durationMs = 30_000L,
            spans = listOf(
                VoiceActivitySpan(1_000L, 3_000L, 0.9f),
                VoiceActivitySpan(7_000L, 8_000L, 0.7f),
            ),
        )

        assertEquals(1, map.spansWithin(2_500L, 6_000L).size)
        assertTrue(map.isUsable)
    }

    @Test
    fun `correction accepts timeline-only changes and can roll back`() {
        val original = LyricData(
            lines = listOf(
                LyricLine(timeStamp = 1_000L, endTime = 20_000L, text = "Hello"),
            ),
        )
        val corrected = original.copy(
            lines = listOf(
                original.lines.single().copy(timeStamp = 1_100L, endTime = 3_700L),
            ),
        )
        val correction = LyricTimingCorrection(
            correctionId = "correction-1",
            sourceIdentity = "song:size:duration",
            originalLyricHash = "sha256:lyrics",
            analyzerVersion = "v1",
            mode = LyricTimingCorrectionMode.LINE_ACTIVITY_TRIM,
            status = LyricTimingCorrectionStatus.PREVIEW,
            original = original,
            corrected = corrected,
            changes = listOf(
                LyricTimingChange(0, 1_000L, 20_000L, 1_100L, 3_700L, 0.92f),
            ),
        )

        assertTrue(correction.changed)
        assertEquals(original, correction.rollback())
        assertEquals(LyricTimingCorrectionStatus.ACCEPTED, correction.accept().status)
    }

    @Test
    fun `correction rejects content changes`() {
        val original = LyricData(lines = listOf(LyricLine(1_000L, "Hello")))
        val changedText = original.copy(
            lines = listOf(LyricLine(1_000L, "Goodbye")),
        )

        assertFalse(original.isTimelineCompatibleWith(changedText))
    }

    @Test
    fun `correction permits rebuilding stale imported word grouping`() {
        val original = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 1_000L,
                    endTime = 2_000L,
                    text = "聖人の調律",
                    words = listOf(LyricWord("聖人の", 1_000L, 1_500L, 500L)),
                ),
            ),
        )
        val corrected = original.copy(
            lines = listOf(
                original.lines.single().copy(
                    words = listOf(
                        LyricWord("聖", 1_050L, 1_200L, 150L),
                        LyricWord("人", 1_200L, 1_350L, 150L),
                        LyricWord("の", 1_350L, 1_500L, 150L),
                        LyricWord("調", 1_500L, 1_700L, 200L),
                        LyricWord("律", 1_700L, 1_900L, 200L),
                    ),
                ),
            ),
        )

        assertTrue(original.isTimelineCompatibleWith(corrected))
    }

    @Test
    fun `correction still rejects rebuilt words that alter visible text`() {
        val original = LyricData(
            lines = listOf(LyricLine(timeStamp = 1_000L, endTime = 2_000L, text = "聖人")),
        )
        val corrected = original.copy(
            lines = listOf(
                original.lines.single().copy(
                    words = listOf(LyricWord("成人", 1_100L, 1_900L, 800L)),
                ),
            ),
        )

        assertFalse(original.isTimelineCompatibleWith(corrected))
    }
}
