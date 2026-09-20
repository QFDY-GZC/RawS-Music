package com.rawsmusic.core.common.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CtcForcedAlignmentTest {
    @Test
    fun tokenizerUsesLineTextWhenLrcOrLineLevelTtmlHasNoWordEntries() {
        val lyrics = LyricData(
            lines = listOf(
                LyricLine(timeStamp = 0L, endTime = 1_000L, text = "あい"),
                LyricLine(timeStamp = 1_000L, endTime = 2_000L, text = "", words = listOf(
                    LyricWord(text = "う", begin = 1_000L, end = 1_200L, duration = 200L),
                    LyricWord(text = "え", begin = 1_200L, end = 1_400L, duration = 200L),
                )),
            ),
        )

        val tokens = CtcLyricTokenizer.tokenize(
            lyrics = lyrics,
            vocabulary = CtcVocabulary(mapOf("あ" to 1, "い" to 2, "う" to 3, "え" to 4)),
        ).getOrThrow()

        assertEquals(listOf("あ", "い", "う", "え"), tokens.map { it.text })
        assertEquals(listOf(0, 0, 1, 1), tokens.map { it.lineIndex })
        assertEquals(listOf(0, 1, 0, 1), tokens.map { it.wordIndex })
    }

    @Test
    fun viterbiAlignmentKeepsRepeatedTokensSeparatedByBlank() {
        val scores = CtcFrameScores(
            frameCount = 6,
            tokenCount = 3,
            values = floatArrayOf(
                0f, 8f, -8f,
                0f, 8f, -8f,
                8f, -8f, -8f,
                0f, -8f, 8f,
                0f, -8f, 8f,
                8f, -8f, -8f,
            ),
        )
        val target = listOf(
            CtcTargetToken(tokenId = 1, lineIndex = 0, wordIndex = 0, text = "あ"),
            CtcTargetToken(tokenId = 2, lineIndex = 0, wordIndex = 1, text = "い"),
        )

        val aligned = CtcForcedAligner.align(
            scores = scores,
            target = target,
            policy = CtcForcedAlignmentPolicy(frameDurationMs = 10L),
        )

        assertEquals(2, aligned.size)
        assertEquals(0L, aligned[0].beginMs)
        assertEquals(20L, aligned[0].endMs)
        assertEquals(30L, aligned[1].beginMs)
        assertEquals(50L, aligned[1].endMs)
        assertTrue(aligned.all { it.confidence > 0.9f })
    }

    @Test
    fun ctcCorrectionPreservesNormalSourceBoundary() {
        val original = LyricData(
            lines = listOf(
                LyricLine(timeStamp = 1_000L, endTime = 3_000L, text = "あい"),
            ),
        )
        val corrected = original.previewCtcWordRetiming(
            alignedTokens = listOf(
                CtcAlignedToken(0, 0, 0, "あ", 1_100L, 1_400L, 0.95f),
                CtcAlignedToken(1, 0, 1, "い", 1_450L, 1_700L, 0.9f),
            ),
            sourceIdentity = "song",
            originalLyricHash = "hash",
            correctionId = "correction",
            durationMs = 10_000L,
        )

        assertEquals(LyricTimingCorrectionStatus.PREVIEW, corrected.status)
        assertEquals(860L, corrected.corrected.lines.single().timeStamp)
        assertEquals(3_000L, corrected.corrected.lines.single().endTime)
        assertEquals("あい", corrected.corrected.lines.single().text)
        assertEquals(2, corrected.corrected.lines.single().words.size)
    }

    @Test
    fun ctcCorrectionTrimsOnlyClearlyExcessiveAccompanimentTail() {
        val original = LyricData(
            lines = listOf(
                LyricLine(timeStamp = 1_000L, endTime = 10_000L, text = "あい"),
            ),
        )
        val corrected = original.previewCtcWordRetiming(
            alignedTokens = listOf(
                CtcAlignedToken(0, 0, 0, "あ", 1_100L, 1_400L, 0.95f),
                CtcAlignedToken(1, 0, 1, "い", 1_450L, 1_700L, 0.9f),
            ),
            sourceIdentity = "song",
            originalLyricHash = "hash",
            correctionId = "correction",
            durationMs = 12_000L,
        )

        assertEquals(2_700L, corrected.corrected.lines.single().endTime)
    }

    @Test
    fun effectiveFrameDurationUsesObservedSongLength() {
        val scores = CtcFrameScores(
            frameCount = 997,
            tokenCount = 4,
            values = FloatArray(997 * 4),
        )

        assertEquals(10L, CtcForcedAligner.effectiveFrameDurationMs(scores, 10_000L, 20L))
        assertEquals(20L, CtcForcedAligner.effectiveFrameDurationMs(scores, 0L, 20L))
    }

    @Test
    fun ctcCorrectionRebuildsExistingWordsAsStrictCharacterTimeline() {
        val original = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 1_000L,
                    endTime = 2_000L,
                    text = "かな",
                    words = listOf(
                        LyricWord(text = "かな", begin = 1_000L, end = 2_000L, duration = 1_000L),
                    ),
                ),
            ),
        )

        val correction = original.previewCtcWordRetiming(
            alignedTokens = listOf(
                CtcAlignedToken(0, 0, 0, "か", 1_100L, 1_500L, 0.9f),
                CtcAlignedToken(1, 0, 1, "な", 1_400L, 1_700L, 0.9f),
            ),
            sourceIdentity = "song",
            originalLyricHash = "hash",
            correctionId = "correction",
            durationMs = 3_000L,
        )

        val words = correction.corrected.lines.single().words
        assertEquals(listOf("か", "な"), words.map { it.text })
        assertTrue(words[0].end <= words[1].begin)
        assertTrue(words.all { it.end > it.begin })
        assertEquals(listOf("か", "な"), correction.wordChanges.map { it.text })
        assertEquals(listOf(1_000L, 1_500L), correction.wordChanges.map { it.originalStartMs })
        assertEquals(listOf(1_100L, 1_400L), correction.wordChanges.map { it.correctedStartMs })
        assertEquals(listOf(0.9f, 0.9f), correction.wordChanges.map { it.confidence })
    }

    @Test
    fun ctcCorrectionMarksMissingOriginalCharacterTimingInPreview() {
        val original = LyricData(
            lines = listOf(LyricLine(timeStamp = 1_000L, endTime = 2_000L, text = "星空")),
        )

        val correction = original.previewCtcWordRetiming(
            alignedTokens = listOf(
                CtcAlignedToken(0, 0, 0, "星", 1_100L, 1_300L, 0.8f),
                CtcAlignedToken(1, 0, 1, "空", 1_350L, 1_600L, 0.85f),
            ),
            sourceIdentity = "song",
            originalLyricHash = "hash",
            correctionId = "correction",
            durationMs = 3_000L,
        )

        assertEquals(2, correction.wordChanges.size)
        assertTrue(correction.wordChanges.all { it.originalStartMs == null })
        assertEquals(listOf(1_100L, 1_350L), correction.wordChanges.map { it.correctedStartMs })
    }

    @Test
    fun incompleteCtcAlignmentFallsBackWithoutMixedWordTimeline() {
        val original = LyricData(
            lines = listOf(
                LyricLine(timeStamp = 0L, endTime = 1_000L, text = "あい"),
                LyricLine(timeStamp = 1_000L, endTime = 2_000L, text = "うえ"),
            ),
        )

        val correction = original.previewCtcWordRetiming(
            alignedTokens = listOf(
                CtcAlignedToken(0, 0, 0, "あ", 100L, 300L, 0.9f),
                CtcAlignedToken(1, 0, 1, "い", 320L, 500L, 0.9f),
            ),
            sourceIdentity = "song",
            originalLyricHash = "hash",
            correctionId = "incomplete",
            durationMs = 3_000L,
        )

        assertEquals(LyricTimingCorrectionStatus.FALLBACK, correction.status)
        assertEquals(original, correction.corrected)
        assertTrue(correction.corrected.lines.all { it.words.isEmpty() })
        assertTrue(correction.wordChanges.isEmpty())
    }

    @Test
    fun phoneCorrectionAggregatesPhonesBackIntoCharacterPreview() {
        val original = LyricData(
            lines = listOf(
                LyricLine(
                    timeStamp = 1_000L,
                    endTime = 2_000L,
                    text = "聖人",
                    words = listOf(
                        LyricWord(text = "聖人", begin = 1_000L, end = 2_000L, duration = 1_000L),
                    ),
                ),
            ),
        )

        val correction = original.previewPhoneWordRetiming(
            alignedTokens = listOf(
                CtcAlignedToken(0, 0, 0, "s", 1_100L, 1_200L, 0.8f),
                CtcAlignedToken(1, 0, 0, "e", 1_200L, 1_350L, 0.9f),
                CtcAlignedToken(2, 0, 1, "j", 1_400L, 1_500L, 0.85f),
                CtcAlignedToken(3, 0, 1, "i", 1_500L, 1_700L, 0.95f),
            ),
            wordTexts = mapOf((0 to 0) to "聖", (0 to 1) to "人"),
            sourceIdentity = "song",
            originalLyricHash = "hash",
            correctionId = "phone-correction",
            durationMs = 3_000L,
            analyzerVersion = "phone-test",
        )

        assertEquals(listOf("聖", "人"), correction.corrected.lines.single().words.map { it.text })
        assertEquals(listOf("聖", "人"), correction.wordChanges.map { it.text })
        assertEquals(listOf(1_100L, 1_400L), correction.wordChanges.map { it.correctedStartMs })
        assertTrue(correction.wordChanges.all { it.correctedEndMs > it.correctedStartMs })
    }

    @Test
    fun lineWindowsKeepChineseJapaneseAndEnglishTargetsInTheirOwnTimeRanges() {
        val lyrics = LyricData(
            lines = listOf(
                LyricLine(timeStamp = 0L, text = "你好", endTime = 40L),
                LyricLine(timeStamp = 40L, text = "Hi", endTime = 80L),
                LyricLine(timeStamp = 80L, text = "さよ", endTime = 120L),
            ),
        )
        val target = listOf(
            CtcTargetToken(1, 0, 0, "你"), CtcTargetToken(2, 0, 1, "好"),
            CtcTargetToken(1, 1, 0, "H"), CtcTargetToken(2, 1, 1, "i"),
            CtcTargetToken(1, 2, 0, "さ"), CtcTargetToken(2, 2, 1, "よ"),
        )
        val values = FloatArray(12 * 3) { -8f }
        fun set(frame: Int, blank: Float, token: Int, score: Float) {
            val offset = frame * 3
            values[offset] = blank
            values[offset + token] = score
        }
        set(0, -8f, 1, 8f); set(1, -8f, 1, 8f); set(2, 8f, 1, -8f); set(3, -8f, 2, 8f)
        set(4, -8f, 1, 8f); set(5, -8f, 1, 8f); set(6, 8f, 1, -8f); set(7, -8f, 2, 8f)
        set(8, -8f, 1, 8f); set(9, -8f, 1, 8f); set(10, 8f, 1, -8f); set(11, -8f, 2, 8f)

        val aligned = CtcForcedAligner.alignByLyricLineWindows(
            scores = CtcFrameScores(12, 3, values),
            target = target,
            lyrics = lyrics,
            durationMs = 120L,
            policy = CtcForcedAlignmentPolicy(frameDurationMs = 10L),
            windowPaddingMs = 0L,
        )

        assertEquals(6, aligned.size)
        assertTrue(aligned.filter { it.lineIndex == 0 }.all { it.endMs <= 40L })
        assertTrue(aligned.filter { it.lineIndex == 1 }.all { it.beginMs >= 40L && it.endMs <= 80L })
        assertTrue(aligned.filter { it.lineIndex == 2 }.all { it.beginMs >= 80L })
    }
}
