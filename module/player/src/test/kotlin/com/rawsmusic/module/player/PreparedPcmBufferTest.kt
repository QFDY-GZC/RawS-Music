package com.rawsmusic.module.player

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PreparedPcmBufferTest {
    @Test
    fun primedPrefixIsFrameAlignedAndDrainsWithoutDroppingTrackStart() {
        val source = ByteArray(64) { it.toByte() }
        val prepared = PreparedPcmBuffer(capacityBytes = 64, frameSize = 4)

        assertEquals(60, prepared.append(source, 0, 63))
        assertEquals(15L, prepared.totalWrittenFrames)

        val crossfadePrefix = ByteArray(20)
        assertEquals(20, prepared.read(crossfadePrefix, 0, crossfadePrefix.size))
        assertArrayEquals(source.copyOfRange(0, 20), crossfadePrefix)

        val ring = RingBuffer(128)
        assertEquals(40, prepared.drainInto(ring))
        val gaplessRemainder = ByteArray(40)
        assertEquals(40, ring.readNonBlocking(gaplessRemainder, 0, gaplessRemainder.size))
        assertArrayEquals(source.copyOfRange(20, 60), gaplessRemainder)
        assertEquals(0, prepared.availableBytes)
    }
}
