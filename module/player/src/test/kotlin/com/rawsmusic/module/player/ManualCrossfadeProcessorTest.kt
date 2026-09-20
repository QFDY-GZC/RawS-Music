package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ManualCrossfadeProcessorTest {
    @Test
    fun linearEnvelopeUsesCurrentAtStartAndHalfMixAtMidpoint() {
        val processor = KotlinManualCrossfadeProcessor()
        assertTrue(processor.start(durationMs = 100, sampleRate = 1_000))
        val current = ByteArray(200)
        val pending = ByteArray(200)
        repeat(100) { frame ->
            writeS16(current, frame, 10_000)
            writeS16(pending, frame, 20_000)
        }

        val result = processor.mixInPlace(
            currentBuffer = current,
            pendingBuffer = pending,
            offset = 0,
            length = current.size,
            sampleRate = 1_000,
            frameSize = 2,
            bitsPerSample = 16,
            outputIsFloat = false,
            outputIsPacked24 = false,
        )

        assertTrue(result.completed)
        assertFalse(result.active)
        assertEquals(100L, result.processedFrames)
        assertTrue(abs(readS16(current, 0) - 10_000) <= 1)
        assertTrue(abs(readS16(current, 50) - 15_000) <= 2)
        assertTrue(readS16(current, 99) in 19_890..19_920)
    }

    @Test
    fun framesAfterFadeWindowArePendingOnly() {
        val processor = KotlinManualCrossfadeProcessor()
        assertTrue(processor.start(durationMs = 4, sampleRate = 1_000))
        val current = ByteArray(16)
        val pending = ByteArray(16)
        repeat(8) { frame ->
            writeS16(current, frame, 12_000)
            writeS16(pending, frame, -4_000)
        }

        val result = processor.mixInPlace(
            currentBuffer = current,
            pendingBuffer = pending,
            offset = 0,
            length = current.size,
            sampleRate = 1_000,
            frameSize = 2,
            bitsPerSample = 16,
            outputIsFloat = false,
            outputIsPacked24 = false,
        )

        assertTrue(result.completed)
        assertEquals(-4_000, readS16(current, 5))
        assertEquals(-4_000, readS16(current, 7))
    }

    private fun writeS16(buffer: ByteArray, frame: Int, value: Int) {
        val offset = frame * 2
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value shr 8).toByte()
    }

    private fun readS16(buffer: ByteArray, frame: Int): Int {
        val offset = frame * 2
        return (
            (buffer[offset].toInt() and 0xff) or
                (buffer[offset + 1].toInt() shl 8)
            ).toShort().toInt()
    }
}
