package com.rawsmusic.lyrico.runtime

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class LyricoBoundedResponseTest {
    @Test fun exactLimitAndEmptyResponsesAreAccepted() {
        val bytes = ByteArray(64) { it.toByte() }
        assertArrayEquals(bytes, readBoundedPluginResponse(ByteArrayInputStream(bytes), 64))
        assertEquals(0, readBoundedPluginResponse(ByteArrayInputStream(byteArrayOf()), 64).size)
    }
    @Test fun declaredOversizedResponseIsRejectedBeforeAnyRead() {
        var reads = 0
        val stream = object : InputStream() { override fun read(): Int { reads++; return 1 } }
        assertThrows(IOException::class.java) { readBoundedPluginResponse(stream, 64, 65) }
        assertEquals(0, reads)
    }
    @Test fun chunkedOrDecodedResponseCannotGrowPastLimit() {
        var consumed = 0
        val stream = object : InputStream() {
            override fun read(): Int { consumed++; return 1 }
        }
        assertThrows(IOException::class.java) { readBoundedPluginResponse(stream, 64) }
        assertEquals(65, consumed)
    }
    @Test(timeout = 5000) fun failureAlwaysReturnsHttpCapacity() {
        repeat(100) {
            assertThrows(IOException::class.java) {
                LyricoHttpTransport.withPermit<Unit> { throw IOException("fixture") }
            }
        }
        assertEquals("ok", LyricoHttpTransport.withPermit { "ok" })
    }
}
