package com.rawsmusic.module.data.prefs

import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class BackupBoundedReaderTest {
    @Test fun exactLimitIsAccepted() {
        val input = CountingInput(33554432)
        val bytes = BackupBoundedReader.read(input)
        assertEquals(33554432, bytes.size)
        assertEquals(33554432, input.consumed)
    }
    @Test fun oneByteOverLimitStopsWithoutReadingTheRemainder() {
        val input = CountingInput(33554432 + 100000)
        try { BackupBoundedReader.read(input); fail("Oversized input accepted") }
        catch (_: IllegalArgumentException) { assertEquals(33554433, input.consumed) }
    }
    @Test fun smallerLimitsAndEmptyInputWork() {
        assertEquals(0, BackupBoundedReader.read(CountingInput(0), 10).size)
        assertEquals(10, BackupBoundedReader.read(CountingInput(10), 10).size)
        try { BackupBoundedReader.read(CountingInput(11), 10); fail("Oversized input accepted") }
        catch (_: IllegalArgumentException) { }
    }
    private class CountingInput(private val size: Int) : InputStream() {
        var consumed = 0
        override fun read(): Int = if (consumed < size) { consumed++; 0 } else -1
        override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
            if (consumed >= size) return -1
            val count = minOf(length, size - consumed)
            bytes.fill(0, offset, offset + count)
            consumed += count
            return count
        }
    }
}
