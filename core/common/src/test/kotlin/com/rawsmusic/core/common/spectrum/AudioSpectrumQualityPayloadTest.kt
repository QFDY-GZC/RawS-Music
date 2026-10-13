package com.rawsmusic.core.common.spectrum

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class AudioSpectrumQualityPayloadTest {
    private fun sample() = AudioSpectrumAnalysis(
        sampleRate = 48000, channels = 2, fftSize = 1024, durationMs = 15000,
        cutoffHz = 19000f, cutoffConfidence = 0.5f,
        leftPeakDb = -1f, rightPeakDb = -1f, leftRmsDb = -15f, rightRmsDb = -15f,
        averageSpectrumDb = FloatArray(16) { -45f }, waterfallFrames = 1,
        waterfallBins = 16, leftLevelsDb = floatArrayOf(-15f), rightLevelsDb = floatArrayOf(-15f),
        leftWaterfall = ByteArray(16), rightWaterfall = ByteArray(16),
        decodedFrameCount = 720000, nearRailFrameCount = 15000,
        sustainedRailFrameCount = 12000, sustainedRailRunCount = 24,
        nonzeroSampleCount = 1_400_000, aligned16SampleCount = 1_399_000,
        aligned24SampleCount = 1_399_800,
        qualityFlags = 15, integratedLufs = -14.2f, loudnessRangeLu = 8.1f, truePeakDb = 0.3f,
        leftDcOffset = 0.001f, rightDcOffset = -0.001f, stereoCorrelation = 0.7f,
        midRmsDb = -16f, sideRmsDb = -23f, silentFrameCount = 10000,
        longestSilentFrameCount = 4000, hardRailFrameCount = 700
    )

    @Test fun sampleEvidenceSurvivesPersistentPayloadRoundTrip() {
        val original = sample()
        val payload = AudioSpectrumAnalysisCache.encodePayload(original)
        assertEquals(5, ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).getInt(4))
        val restored = AudioSpectrumAnalysisCache.parsePayload(payload)
        assertEquals(original.decodedFrameCount, restored.decodedFrameCount)
        assertEquals(original.nearRailFrameCount, restored.nearRailFrameCount)
        assertEquals(original.sustainedRailFrameCount, restored.sustainedRailFrameCount)
        assertEquals(original.sustainedRailRunCount, restored.sustainedRailRunCount)
        assertEquals(original.nonzeroSampleCount, restored.nonzeroSampleCount)
        assertEquals(original.aligned16SampleCount, restored.aligned16SampleCount)
        assertEquals(original.aligned24SampleCount, restored.aligned24SampleCount)
        assertEquals(original.qualityFlags, restored.qualityFlags)
        assertEquals(original.integratedLufs, restored.integratedLufs)
        assertEquals(original.loudnessRangeLu, restored.loudnessRangeLu)
        assertEquals(original.truePeakDb, restored.truePeakDb)
        assertEquals(original.leftDcOffset, restored.leftDcOffset)
        assertEquals(original.rightDcOffset, restored.rightDcOffset)
        assertEquals(original.stereoCorrelation, restored.stereoCorrelation)
        assertEquals(original.midRmsDb, restored.midRmsDb)
        assertEquals(original.sideRmsDb, restored.sideRmsDb)
        assertEquals(original.silentFrameCount, restored.silentFrameCount)
        assertEquals(original.longestSilentFrameCount, restored.longestSilentFrameCount)
        assertEquals(original.hardRailFrameCount, restored.hardRailFrameCount)
    }

    @Test fun truncatedSampleEvidenceCannotBeInterpretedAsHealthy() {
        val payload = AudioSpectrumAnalysisCache.encodePayload(sample())
        assertThrows(IllegalArgumentException::class.java) {
            AudioSpectrumAnalysisCache.parsePayload(payload.copyOfRange(0, 80))
        }
    }

    @Test fun invalidCountsAndClaimedMeasurementCannotBeAccepted() {
        for (a in listOf(sample().copy(longestSilentFrameCount = 999999),
            sample().copy(stereoCorrelation = 2f), sample().copy(integratedLufs = Float.NaN))) {
            assertThrows(IllegalArgumentException::class.java) {
                AudioSpectrumAnalysisCache.parsePayload(AudioSpectrumAnalysisCache.encodePayload(a))
            }
        }
    }

    @Test fun monoPayloadAcceptsUnavailableRightChannelMeasurement() {
        val mono = sample().copy(channels = 1, nonzeroSampleCount = 700000,
            aligned16SampleCount = 699000, aligned24SampleCount = 699800,
            rightDcOffset = Float.NaN, stereoCorrelation = Float.NaN)
        val restored = AudioSpectrumAnalysisCache.parsePayload(AudioSpectrumAnalysisCache.encodePayload(mono))
        assertEquals(1, restored.channels)
        assertEquals(mono.leftDcOffset, restored.leftDcOffset)
        assertEquals(true, restored.rightDcOffset.isNaN())
    }

    @Test fun rawTruePeakBelowFftDisplayFloorIsStillValid() {
        val quiet = sample().copy(truePeakDb = -180f, qualityFlags = 9)
        val restored = AudioSpectrumAnalysisCache.parsePayload(AudioSpectrumAnalysisCache.encodePayload(quiet))
        assertEquals(-180f, restored.truePeakDb, 0f)
    }

    @Test fun stereoRmsCombinesPowerRatherThanAveragingDecibels() {
        assertEquals(-3.0103f, sample().copy(leftRmsDb = 0f, rightRmsDb = -120f).stereoAverageDb, 0.001f)
        assertEquals(-15f, sample().copy(channels = 1, leftRmsDb = -15f, rightRmsDb = -120f).stereoAverageDb, 0f)
    }
}
