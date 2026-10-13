package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class RingBufferTerminalTest {
    @Test fun finishedEmptyDecoderIsTerminalWithoutClosingRing() {
        val ring = RingBuffer(32)
        ring.markEOF()
        assertFalse(ring.isClosed())
        assertTrue(ring.isEof())
    }

    @Test fun eofKeepsBufferedPcmReadableUntilDrained() {
        val ring = RingBuffer(32)
        ring.write(byteArrayOf(1, 2, 3, 4), 0, 4)
        ring.markEOF()
        assertFalse(ring.isEof())
        assertEquals(4, ring.readWithTimeout(ByteArray(4), 0, 4, 1))
        assertTrue(ring.isEof())
    }

    @Test fun clearStartsANewNonterminalRing() {
        val ring = RingBuffer(32)
        ring.markEOF()
        ring.clear()
        assertFalse(ring.isEof())
    }
}
