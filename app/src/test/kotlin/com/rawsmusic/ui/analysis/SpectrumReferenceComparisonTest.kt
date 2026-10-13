package com.rawsmusic.ui.analysis

import com.rawsmusic.transcode.AudioTranscodePcmDigest
import com.rawsmusic.transcode.AudioTranscodeProbe
import org.junit.Assert.assertEquals
import org.junit.Test

class SpectrumReferenceComparisonTest {
    private fun probe(rate: Int = 96_000, bits: Int = 24, channels: Int = 2,
        dsd: Boolean = false, meaningful: Boolean = true) = AudioTranscodeProbe(
        sampleRateHz = rate, bitDepth = bits, channels = channels, durationMs = 61_000,
        bitRate = 4_608_000, codecId = 86028, isDsdSource = dsd,
        bitDepthMeaningful = meaningful
    )

    @Test fun exactDecodedPcmAndFrameCountProduceReferenceMatch() {
        val digest = AudioTranscodePcmDigest("a".repeat(64), 5_856_000)
        assertEquals(SpectrumReferenceResult.Match,
            compareReferenceDigests(probe(), probe(), digest, digest))
    }

    @Test fun differentFrameCountCannotBeCalledMatching() {
        val source = AudioTranscodePcmDigest("a".repeat(64), 5_856_000)
        val reference = source.copy(frames = 5_856_001)
        assertEquals(SpectrumReferenceResult.Different,
            compareReferenceDigests(probe(), probe(), source, reference))
    }

    @Test fun differentDecodedHashCannotBeCalledMatching() {
        val source = AudioTranscodePcmDigest("a".repeat(64), 5_856_000)
        val reference = AudioTranscodePcmDigest("b".repeat(64), 5_856_000)
        assertEquals(SpectrumReferenceResult.Different,
            compareReferenceDigests(probe(), probe(), source, reference))
    }

    @Test fun incompatiblePcmFormatsCannotBeCalledMatching() {
        val digest = AudioTranscodePcmDigest("a".repeat(64), 5_856_000)
        assertEquals(SpectrumReferenceResult.Unsupported,
            compareReferenceDigests(probe(), probe(rate = 44_100), digest, digest))
        assertEquals(SpectrumReferenceResult.Unsupported,
            compareReferenceDigests(probe(), probe(channels = 1), digest, digest))
        assertEquals(SpectrumReferenceResult.Unsupported,
            compareReferenceDigests(probe(), probe(bits = 16), digest, digest))
    }

    @Test fun dsdAndUnknownPrecisionCannotEstablishPcmEquivalence() {
        val digest = AudioTranscodePcmDigest("a".repeat(64), 5_856_000)
        assertEquals(SpectrumReferenceResult.Unsupported,
            compareReferenceDigests(probe(dsd = true), probe(), digest, digest))
        assertEquals(SpectrumReferenceResult.Unsupported,
            compareReferenceDigests(probe(meaningful = false), probe(), digest, digest))
    }
}
