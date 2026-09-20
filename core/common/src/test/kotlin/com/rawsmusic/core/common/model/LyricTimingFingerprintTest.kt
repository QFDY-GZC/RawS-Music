package com.rawsmusic.core.common.model

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class LyricTimingFingerprintTest {
    @Test
    fun sameTimelineProducesSameFingerprint() {
        val lyrics = LyricData(
            offset = 12L,
            lines = listOf(
                LyricLine(
                    timeStamp = 1_000L,
                    endTime = 2_000L,
                    text = "line",
                    translation = "translation",
                    words = listOf(LyricWord("line", 1_000L, 2_000L)),
                ),
            ),
        )

        assertEquals(lyrics.stableTimingFingerprint(), lyrics.copy().stableTimingFingerprint())
    }

    @Test
    fun timingAndContentChangesProduceDifferentFingerprints() {
        val lyrics = LyricData(lines = listOf(LyricLine(1_000L, "line", endTime = 2_000L)))

        assertNotEquals(
            lyrics.stableTimingFingerprint(),
            lyrics.copy(lines = listOf(LyricLine(1_100L, "line", endTime = 2_000L)))
                .stableTimingFingerprint(),
        )
        assertNotEquals(
            lyrics.stableTimingFingerprint(),
            lyrics.copy(lines = listOf(LyricLine(1_000L, "other", endTime = 2_000L)))
                .stableTimingFingerprint(),
        )
    }
}
