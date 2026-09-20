package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveClimaxAnalysisTest {
    @Test
    fun beatAwareAnalysisFindsSustainedChorusInsteadOfLoudestTransient() {
        val sampleHz = 10f
        val durationSeconds = 180
        val peaks = FloatArray((sampleHz * durationSeconds).toInt()) { index ->
            val seconds = index / sampleHz
            val beat = if (((seconds * 2f) % 1f) < 0.11f) 0.18f else 0f // 120 BPM
            val verse = 0.22f
            val build = if (seconds in 62f..70f) ((seconds - 62f) / 8f) * 0.22f else 0f
            val chorus = if (seconds in 70f..102f) 0.48f else 0f
            val isolatedTransient = if (seconds in 126f..127f) 0.72f else 0f
            (verse + beat + build + chorus + isolatedTransient).coerceIn(0f, 1f)
        }

        val analysis = analyzeImmersiveClimax(peaks, durationSeconds * 1000L)
        assertTrue(analysis.resolvedBpm in 110f..130f)
        val segment = analysis.segments.single()
        val center = (segment.startFraction + segment.endFraction) * 0.5f
        assertTrue(center in 0.36f..0.62f)
        assertTrue(segment.confidence > 0.17f)
    }

    @Test
    fun metadataBpmIsUsedAsTimingGrid() {
        val sampleHz = 8f
        val durationSeconds = 160
        val peaks = FloatArray((sampleHz * durationSeconds).toInt()) { index ->
            val seconds = index / sampleHz
            val beatPeriod = 60f / 92f
            val beat = if ((seconds % beatPeriod) < 0.10f) 0.13f else 0f
            val chorus = if (seconds in 88f..118f) 0.44f else 0f
            (0.24f + beat + chorus).coerceIn(0f, 1f)
        }

        val analysis = analyzeImmersiveClimax(
            peaks = peaks,
            durationMs = durationSeconds * 1000L,
            preferredBpm = 92,
        )
        assertEquals(92f, analysis.resolvedBpm, 0.01f)
        val segment = analysis.segments.single()
        assertTrue(segment.startFraction < 0.68f)
        assertTrue(segment.endFraction > 0.55f)
    }

    @Test
    fun flatTrackDoesNotInventClimax() {
        val peaks = FloatArray(1_200) { 0.48f }
        val analysis = analyzeImmersiveClimax(
            peaks = peaks,
            durationMs = 150_000L,
            preferredBpm = 120,
        )
        assertTrue(analysis.segments.isEmpty())
    }
}
