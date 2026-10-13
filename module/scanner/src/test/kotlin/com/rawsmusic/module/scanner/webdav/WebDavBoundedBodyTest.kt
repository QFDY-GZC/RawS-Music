package com.rawsmusic.module.scanner.webdav

import java.io.ByteArrayInputStream
import java.io.InputStream
import org.junit.Assert.*
import org.junit.Test

class WebDavBoundedBodyTest {
    @Test fun exactLimitSucceedsAndOverflowStopsAtOneExtraByte() {
        assertEquals(10, WebDavBoundedBody.read(ByteArrayInputStream(ByteArray(10)), 10).size)
        var read = 0
        val source = object : InputStream() { override fun read(): Int { read++; return 1 } }
        assertTrue(runCatching { WebDavBoundedBody.read(source, 10) }.isFailure)
        assertEquals(11, read)
    }
}
