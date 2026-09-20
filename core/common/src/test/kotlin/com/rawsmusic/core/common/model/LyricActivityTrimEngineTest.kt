package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricActivityTrimEngineTest {
    @Test
    fun trimsLongVocalTailWithoutChangingLyricContent() {
        val original = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 1_000L,
                    endTime = 20_000L,
                    text = "Sung line",
                    translation = "Translated",
                    words = listOf(LyricWord("Sung line", 1_000L, 4_000L)),
                )
            )
        )
        val activity = VoiceActivityMap(
            sourceIdentity = "song-a",
            analyzerVersion = "v1",
            sampleRate = 44_100,
            hopMs = 10L,
            durationMs = 20_000L,
            spans = listOf(VoiceActivitySpan(1_050L, 4_200L, 0.9f)),
        )

        val preview = original.previewActivityTrim(
            activityMap = activity,
            sourceIdentity = "song-a",
            originalLyricHash = "hash",
            correctionId = "correction",
        )

        assertEquals(LyricTimingCorrectionStatus.PREVIEW, preview.status)
        assertEquals(4_700L, preview.corrected.lines.single().endTime)
        assertEquals("Sung line", preview.corrected.lines.single().text)
        assertEquals("Translated", preview.corrected.lines.single().translation)
        assertEquals(1_000L, preview.original.lines.single().timeStamp)
        assertTrue(preview.changes.single().confidence > 0.8f)
    }

    @Test
    fun fallsBackWhenThereIsNoConfidentActivity() {
        val original = LyricData(lines = listOf(LyricLine(1_000L, "Line", endTime = 5_000L)))
        val activity = VoiceActivityMap(
            sourceIdentity = "song-a",
            analyzerVersion = "v1",
            sampleRate = 44_100,
            hopMs = 10L,
            durationMs = 5_000L,
            spans = listOf(VoiceActivitySpan(1_000L, 2_000L, 0.2f)),
        )

        val preview = original.previewActivityTrim(
            activityMap = activity,
            sourceIdentity = "song-a",
            originalLyricHash = "hash",
            correctionId = "correction",
        )

        assertEquals(LyricTimingCorrectionStatus.FALLBACK, preview.status)
        assertEquals(original, preview.corrected)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsActivityFromAnotherSource() {
        LyricData(lines = listOf(LyricLine(1_000L, "Line", endTime = 5_000L)))
            .previewActivityTrim(
                activityMap = VoiceActivityMap(
                    sourceIdentity = "song-b",
                    analyzerVersion = "v1",
                    sampleRate = 44_100,
                    hopMs = 10L,
                    durationMs = 5_000L,
                    spans = listOf(VoiceActivitySpan(1_000L, 2_000L, 0.9f)),
                ),
                sourceIdentity = "song-a",
                originalLyricHash = "hash",
                correctionId = "correction",
            )
    }
}
