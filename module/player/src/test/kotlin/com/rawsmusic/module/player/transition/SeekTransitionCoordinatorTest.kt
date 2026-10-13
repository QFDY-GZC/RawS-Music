package com.rawsmusic.module.player.transition

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekTransitionCoordinatorTest {
    @Test
    fun backwardSeekAfterNearEndKeepsFadeAudioUntilDecoderBarrier() {
        val output = com.rawsmusic.module.player.SeekOutputBarrier()
        val ring = com.rawsmusic.module.player.RingBuffer(16)
        var fadeReads = 0
        val coordinator = SeekTransitionCoordinator(
            tag = "test",
            fadeOutBlocking = { _, _, current ->
                repeat(4) {
                    ring.write(byteArrayOf(1, 2), 0, 2)
                    val token = output.beginRead()
                    assertEquals(2, ring.readWithTimeout(ByteArray(16), 0, 16, 0))
                    assertEquals(com.rawsmusic.module.player.SeekOutputBarrier.ReadDecision.Accept,
                        output.finishRead(token))
                    fadeReads++
                }
                current()
            },
            armFadeIn = { _, _ -> },
            seekFadeMs = { 100 },
            backend = KotlinSeekBarrierBackend(),
            armOutputBarrier = { output.arm(it.serial, it.targetMs) },
        )
        for (target in listOf(290_000L, 5_000L)) {
            val request = coordinator.begin(target, false, true)
            assertTrue(coordinator.awaitDecoderBarrier(request))
            assertEquals(com.rawsmusic.module.player.SeekOutputBarrier.ReadDecision.Discard,
                output.finishRead(output.beginRead()))
            assertTrue(coordinator.markDecoderCommitted(request.serial))
            output.markCommitted(request.serial)
            assertEquals(com.rawsmusic.module.player.SeekOutputBarrier.ReadDecision.AcceptAndRelease,
                output.finishRead(output.beginRead()))
            assertTrue(coordinator.markOutputCommitted(request.serial))
        }
        assertEquals(8, fadeReads)
    }
    @Test
    fun playingSeekRequiresFadeDecoderAndFirstOutputInOrder() {
        val fades = mutableListOf<String>()
        val backend = KotlinSeekBarrierBackend()
        val coordinator = SeekTransitionCoordinator(
            tag = "test",
            fadeOutBlocking = { duration, reason, shouldContinue ->
                fades += "out:$duration:$reason"
                shouldContinue()
            },
            armFadeIn = { duration, reason -> fades += "in:$duration:$reason" },
            seekFadeMs = { 100 },
            backend = backend,
        )

        val request = coordinator.begin(5_000L, keepPaused = false, requireFadeOut = true)
        assertFalse(coordinator.canStartDecoder(request.serial))
        assertTrue(coordinator.awaitDecoderBarrier(request))
        assertTrue(coordinator.markDecoderCommitted(request.serial))
        assertTrue(coordinator.markOutputCommitted(request.serial))
        assertEquals(SeekBarrierPhase.COMPLETED, coordinator.snapshot().phase)
        assertTrue(fades.first().startsWith("out:100:"))
        assertTrue(fades.last().startsWith("in:100:"))
    }

    @Test
    fun newerSeekInvalidatesEveryOldPhaseTransition() {
        val backend = KotlinSeekBarrierBackend()
        val coordinator = SeekTransitionCoordinator(
            tag = "test",
            fadeOutBlocking = { _, _, shouldContinue -> shouldContinue() },
            armFadeIn = { _, _ -> },
            seekFadeMs = { 100 },
            backend = backend,
        )

        val first = coordinator.begin(1_000L, keepPaused = false, requireFadeOut = true)
        val second = coordinator.begin(2_000L, keepPaused = false, requireFadeOut = false)

        assertFalse(coordinator.awaitDecoderBarrier(first))
        assertFalse(coordinator.markDecoderCommitted(first.serial))
        assertTrue(coordinator.canStartDecoder(second.serial))
        assertTrue(coordinator.markDecoderCommitted(second.serial))
    }

    @Test
    fun pausedSeekNeverStartsAudibleFadeUntilResumeRelease() {
        var fadeInCount = 0
        val coordinator = SeekTransitionCoordinator(
            tag = "test",
            fadeOutBlocking = { _, _, _ -> error("paused seek must not fade out") },
            armFadeIn = { _, _ -> fadeInCount++ },
            seekFadeMs = { 100 },
            backend = KotlinSeekBarrierBackend(),
        )

        val request = coordinator.begin(3_000L, keepPaused = true, requireFadeOut = true)
        assertTrue(coordinator.awaitDecoderBarrier(request))
        assertTrue(coordinator.markDecoderCommitted(request.serial))
        assertEquals(SeekBarrierPhase.PAUSED_COMMITTED, coordinator.snapshot().phase)
        assertEquals(0, fadeInCount)
        assertTrue(coordinator.releasePaused(request.serial))
        assertEquals(SeekBarrierPhase.COMPLETED, coordinator.snapshot().phase)
    }
}
