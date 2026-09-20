package com.rawsmusic.ai.melody

import java.io.File
import kotlin.math.PI
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RmvpeRealtimeFeatureTest {
    @Test
    fun inMemoryFrontendMatchesPcm16FileFrontend() {
        val descriptor = AiRecommendedMelodyModels.RMVPE_Q8
        val samples = FloatArray(descriptor.sampleRate) { index ->
            (0.35 * sin(2.0 * PI * 440.0 * index / descriptor.sampleRate)).toFloat()
        }
        val file = File.createTempFile("rmvpe-realtime", ".s16le")
        try {
            val bytes = ByteArray(samples.size * 2)
            samples.forEachIndexed { index, sample ->
                val value = (sample.coerceIn(-1f, 1f) * 32767f).toInt().toShort().toInt()
                bytes[index * 2] = value.toByte()
                bytes[index * 2 + 1] = (value ushr 8).toByte()
            }
            file.writeBytes(bytes)
            val extractor = RmvpeMelFeatureExtractor(descriptor)
            val frames = 96
            val fromFile = extractor.extract(file, samples.size.toLong(), 0, frames)
            val quantized = FloatArray(samples.size) { index ->
                val low = bytes[index * 2].toInt() and 0xff
                val high = bytes[index * 2 + 1].toInt() shl 8
                (low or high).toShort() / 32768f
            }
            val fromMemory = extractor.extract(quantized, 0, frames)
            assertEquals(fromFile.frameCount, fromMemory.frameCount)
            var maxDiff = 0f
            for (i in fromFile.mel.indices) {
                maxDiff = maxOf(maxDiff, kotlin.math.abs(fromFile.mel[i] - fromMemory.mel[i]))
            }
            assertTrue("maxDiff=$maxDiff", maxDiff < 1.0e-6f)
            for (i in fromFile.rms.indices) {
                assertEquals(fromFile.rms[i], fromMemory.rms[i], 1.0e-7f)
            }
        } finally {
            file.delete()
        }
    }
}
