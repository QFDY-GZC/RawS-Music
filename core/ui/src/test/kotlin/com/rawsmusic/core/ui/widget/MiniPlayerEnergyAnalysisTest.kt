package com.rawsmusic.core.ui.widget

import org.junit.Assert.assertTrue
import org.junit.Test

class MiniPlayerEnergyAnalysisTest {
    @Test
    fun highEnergyIsWaveformDrivenAndTempoIsEstimatedSeparately() {
        val sampleHz = 10f
        val durationSeconds = 180
        val peaks = FloatArray((sampleHz * durationSeconds).toInt()) { index ->
            val seconds = index / sampleHz
            val beat = if (((seconds * 2f) % 1f) < 0.12f) 0.42f else 0.06f // 120 BPM
            val climax = if (seconds in 70f..105f) 0.38f else 0f
            (beat + climax).coerceIn(0f, 1f)
        }

        val profile = requireNotNull(
            analyzeMiniPlayerEnergy(peaks, durationSeconds * 1000L)
        )
        assertTrue(profile.bpm in 110f..130f)
        assertTrue(profile.energyAt(0.50f) > profile.energyAt(0.20f))
    }
}
