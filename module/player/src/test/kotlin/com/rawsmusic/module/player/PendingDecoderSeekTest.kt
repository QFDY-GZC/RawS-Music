package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PendingDecoderSeekTest {
    @Test
    fun targetAndSerialAreConsumedAsOneRequest() {
        val pending = PendingDecoderSeek()
        pending.publish(serial = 7L, targetMs = 12_345L)

        assertEquals(PendingDecoderSeek.Request(7L, 12_345L), pending.consumeLatest())
        assertNull(pending.consumeLatest())
    }

    @Test
    fun newerRequestAtomicallyReplacesOlderRequest() {
        val pending = PendingDecoderSeek()
        pending.publish(1L, 100L)
        pending.publish(2L, 200L)
        pending.cancel(1L)

        assertEquals(PendingDecoderSeek.Request(2L, 200L), pending.consumeLatest())
    }
}
