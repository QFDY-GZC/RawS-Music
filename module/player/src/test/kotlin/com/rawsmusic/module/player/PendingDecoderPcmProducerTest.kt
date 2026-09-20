package com.rawsmusic.module.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PendingDecoderPcmProducerTest {
    @Test
    fun `pending renderer reads never decode and handoff preserves byte order`() {
        val source = ByteArray(128 * 1024) { (it and 0xFF).toByte() }
        var sourceOffset = 0
        var decoderThreadName = ""
        var closeCalls = 0
        val frameSize = 4
        val pcm = PreparedPcmBuffer(
            capacityBytes = 16 * 1024,
            frameSize = frameSize,
        )
        val producer = PendingDecoderPcmProducer(
            tag = "PendingDecoderPcmProducerTest",
            handle = 101L,
            path = "test.pcm",
            generation = 7,
            frameSize = frameSize,
            minimumReadyFrames = 1024L,
            handoffReadyFrames = 512L,
            pcm = pcm,
            gaplessAuditRegistry = DecoderGaplessAuditRegistry("test") { it },
            isGenerationCurrent = { it == 7 },
            decodeChunk = { _, destination, offset, length ->
                decoderThreadName = Thread.currentThread().name
                if (sourceOffset >= source.size) {
                    -1
                } else {
                    val count = minOf(length, 4096, source.size - sourceOffset)
                    source.copyInto(destination, offset, sourceOffset, sourceOffset + count)
                    sourceOffset += count
                    count
                }
            },
            closeDecoder = { closeCalls++ },
        )

        producer.start()
        val ready = producer.awaitReady(2_000L)
        assertTrue(ready.ready)
        assertEquals("RawS Pending Decoder", decoderThreadName)

        var expectedOffset = 0
        val scratch = ByteArray(4096)
        repeat(3) {
            var count: Int
            do {
                count = pcm.read(scratch, 0, scratch.size)
                if (count == 0) Thread.sleep(1L)
            } while (count == 0)
            assertSequential(source, expectedOffset, scratch, count)
            expectedOffset += count
        }

        assertTrue(producer.freezeForHandoff(1_000L))
        val activeRing = RingBuffer(64 * 1024)
        val seeded = producer.completeHandoff(activeRing)
        assertTrue(seeded > 0)

        var remaining = seeded
        while (remaining > 0) {
            val count = activeRing.readWithTimeout(
                scratch,
                0,
                minOf(scratch.size, remaining),
                20L,
            )
            assertTrue(count > 0)
            assertSequential(source, expectedOffset, scratch, count)
            expectedOffset += count
            remaining -= count
        }

        assertEquals(PendingDecoderPcmProducer.TerminalState.TRANSFERRED, producer.state)
        assertEquals(0, closeCalls, "handoff transfers the decoder handle; it must not close it")
    }

    private fun assertSequential(
        source: ByteArray,
        sourceOffset: Int,
        actual: ByteArray,
        length: Int,
    ) {
        for (index in 0 until length) {
            assertEquals(source[sourceOffset + index], actual[index], "byte ${sourceOffset + index}")
        }
    }
}
