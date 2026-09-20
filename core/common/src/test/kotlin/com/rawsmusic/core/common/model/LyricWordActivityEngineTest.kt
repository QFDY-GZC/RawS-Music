package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricWordActivityEngineTest {
    @Test
    fun createsWordTimingInsideVocalWindowsWithoutChangingText() {
        val original = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 0L,
                    endTime = 10_000L,
                    text = "你好世界",
                    translation = "hello world",
                ),
            ),
        )
        val activity = activityMap(
            spans = listOf(VoiceActivitySpan(1_000L, 3_000L, 0.92f)),
            durationMs = 10_000L,
        )

        val correction = original.previewWordActivityRetiming(
            activityMap = activity,
            sourceIdentity = "song-a",
            originalLyricHash = "hash",
            correctionId = "word-correction",
        )
        val line = correction.corrected.lines.single()

        assertEquals(LyricTimingCorrectionStatus.PREVIEW, correction.status)
        assertEquals("你好世界", line.words.joinToString("") { it.text })
        assertTrue(line.words.first().begin >= 1_000L)
        assertTrue(line.words.last().end <= 3_000L)
        assertTrue(line.endTime <= 3_160L)
        assertTrue(original.isTimelineCompatibleWith(correction.corrected))
    }

    @Test
    fun remapsExistingWordsOnlyWhenTheyExtendPastActivity() {
        val original = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 0L,
                    endTime = 10_000L,
                    text = "hello world",
                    words = listOf(
                        LyricWord("hello", 0L, 5_000L),
                        LyricWord(" world", 5_000L, 10_000L),
                    ),
                ),
            ),
        )
        val activity = activityMap(
            spans = listOf(VoiceActivitySpan(2_000L, 6_000L, 0.9f)),
            durationMs = 10_000L,
        )

        val correction = original.previewWordActivityRetiming(
            activityMap = activity,
            sourceIdentity = "song-a",
            originalLyricHash = "hash",
            correctionId = "word-correction",
        )
        val words = correction.corrected.lines.single().words

        assertEquals("hello world", words.joinToString("") { it.text })
        assertTrue(words.first().begin >= 2_000L)
        assertTrue(words.last().end <= 6_000L)
    }

    @Test
    fun fallsBackWithoutConfidentActivity() {
        val original = LyricData(lines = listOf(LyricLine(1_000L, "line", endTime = 5_000L)))
        val activity = activityMap(
            spans = emptyList(),
            durationMs = 5_000L,
        )

        val correction = original.previewWordActivityRetiming(
            activityMap = activity,
            sourceIdentity = "song-a",
            originalLyricHash = "hash",
            correctionId = "word-correction",
        )

        assertEquals(LyricTimingCorrectionStatus.FALLBACK, correction.status)
        assertEquals(original, correction.corrected)
    }

    @Test
    fun usesDedicatedWordAnalyzerVersion() {
        val original = LyricData(lines = listOf(LyricLine(1_000L, "line", endTime = 5_000L)))
        val correction = original.previewWordActivityRetiming(
            activityMap = activityMap(
                spans = listOf(VoiceActivitySpan(1_000L, 2_000L, 0.9f)),
                durationMs = 5_000L,
            ),
            sourceIdentity = "song-a",
            originalLyricHash = "hash",
            correctionId = "word-correction",
        )

        assertEquals("v1:word", correction.analyzerVersion)
    }

    private fun activityMap(
        spans: List<VoiceActivitySpan>,
        durationMs: Long,
    ) = VoiceActivityMap(
        sourceIdentity = "song-a",
        analyzerVersion = "v1",
        sampleRate = 44_100,
        hopMs = 10L,
        durationMs = durationMs,
        spans = spans,
    )
}
