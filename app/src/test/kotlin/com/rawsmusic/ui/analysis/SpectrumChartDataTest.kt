package com.rawsmusic.ui.analysis

import com.rawsmusic.core.common.spectrum.AudioSpectrumAnalysis
import org.junit.Assert.*
import org.junit.Test

class SpectrumChartDataTest {
    @Test fun estimatedExtentAloneIsNotACutoffMarker() {
        assertNull(recording().copy(cutoffHz = 150f).chartCutoff(null))
    }

    @Test fun cutoffMarkerRequiresSupportedInRangeEdge() {
        val a = recording().copy(cutoffHz = 150f)
        val edge = SpectrumBandEdge(100f, 30f, 8, 10, SpectrumEvidence.Moderate, false)
        assertEquals(100f, a.chartCutoff(edge)!!, 0f)
        assertNull(a.chartCutoff(edge.copy(evidence = SpectrumEvidence.Weak)))
        assertNull(a.chartCutoff(edge.copy(frequencyHz = Float.NaN)))
        assertNull(a.chartCutoff(edge.copy(frequencyHz = a.maxFrequencyHz)))
    }

    private fun recording() = AudioSpectrumAnalysis(400, 2, 1024, 1000, 0f, 0f,
        -3f, -3f, -18f, -18f, floatArrayOf(-120f, 0f, -120f), 1, 3,
        floatArrayOf(-18f), floatArrayOf(-18f), byteArrayOf(0, -1, 0), byteArrayOf(0, 0, 0))

    @Test fun betweenCachedBinsInterpolatesPowerInsteadOfJumpingToOneBin() {
        assertEquals(-3.0103f, recording().waterfallDb(0, 50f, SpectrumChannel.Left), 0.001f)
    }

    @Test fun lowFrequencyMovesContinuouslyWithoutInventingNewResolution() {
        val a = recording()
        val before = a.waterfallDb(0, 49f, SpectrumChannel.Left)
        val after = a.waterfallDb(0, 51f, SpectrumChannel.Left)
        assertTrue(after > before)
        assertTrue(after - before < 0.2f)
    }

    @Test fun stereoInterpolationCombinesChannelsInPowerDomain() {
        assertEquals(-6.0206f, recording().waterfallDb(0, 50f, SpectrumChannel.Stereo), 0.001f)
    }

    @Test fun boundsAndMonoRemainSafe() {
        val a = recording()
        assertEquals(-120f, a.waterfallDb(0, -100f, SpectrumChannel.Left), 0.001f)
        assertEquals(-120f, a.waterfallDb(0, 1000f, SpectrumChannel.Left), 0.001f)
        assertEquals(0f, a.copy(channels = 1).waterfallDb(0, 100f, SpectrumChannel.Right), 0.001f)
    }
}
