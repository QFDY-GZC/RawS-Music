package com.rawsmusic.module.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GaplessStitcherTest {
    @Test
    fun `stitches pending frames after current tail without changing samples`() {
        val output = ByteArray(16) { 0x55.toByte() }
        val current = byteArrayOf(1, 2, 3, 4, 5, 6)
        System.arraycopy(current, 0, output, 0, current.size)
        val pending = byteArrayOf(11, 12, 13, 14, 15, 16, 17, 18)

        val result = KotlinGaplessStitcher.stitch(
            outputBuffer = output,
            currentBytes = 6,
            pendingBuffer = pending,
            pendingBytes = pending.size,
            targetBytes = 12,
            frameSize = 2,
        )

        requireNotNull(result)
        assertEquals(12, result.totalBytes)
        assertEquals(6, result.pendingBytes)
        assertEquals(3, result.boundaryFrameOffset)
        assertArrayEquals(
            byteArrayOf(1, 2, 3, 4, 5, 6, 11, 12, 13, 14, 15, 16),
            output.copyOfRange(0, 12),
        )
    }

    @Test
    fun `rejects non-frame pending bytes`() {
        val output = ByteArray(8)
        val pending = ByteArray(1)
        assertNull(
            KotlinGaplessStitcher.stitch(
                outputBuffer = output,
                currentBytes = 4,
                pendingBuffer = pending,
                pendingBytes = 1,
                targetBytes = 8,
                frameSize = 2,
            )
        )
    }
}
