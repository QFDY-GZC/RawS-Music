package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeekOutputBarrierTest {
    @Test
    fun readWithoutSeekIsAccepted() {
        val barrier = SeekOutputBarrier()
        assertEquals(
            SeekOutputBarrier.ReadDecision.Accept,
            barrier.finishRead(barrier.beginRead())
        )
    }

    @Test
    fun readStartedBeforeSeekIsDiscarded() {
        val barrier = SeekOutputBarrier()
        val token = barrier.beginRead()
        barrier.arm(serial = 1L, positionMs = 20_000L)
        barrier.markCommitted(1L)

        assertEquals(SeekOutputBarrier.ReadDecision.Discard, barrier.finishRead(token))
    }

    @Test
    fun readStartedWhileSeekUncommittedIsDiscarded() {
        val barrier = SeekOutputBarrier()
        barrier.arm(serial = 2L, positionMs = 30_000L)
        val token = barrier.beginRead()
        barrier.markCommitted(2L)

        assertEquals(SeekOutputBarrier.ReadDecision.Discard, barrier.finishRead(token))
    }

    @Test
    fun firstReadAfterCommitReleasesBarrier() {
        val barrier = SeekOutputBarrier()
        barrier.arm(serial = 3L, positionMs = 40_000L)
        barrier.markCommitted(3L)

        assertEquals(
            SeekOutputBarrier.ReadDecision.AcceptAndRelease,
            barrier.finishRead(barrier.beginRead())
        )
        assertEquals(
            SeekOutputBarrier.ReadDecision.Accept,
            barrier.finishRead(barrier.beginRead())
        )
    }

    @Test
    fun newerSeekInvalidatesOlderReadToken() {
        val barrier = SeekOutputBarrier()
        barrier.arm(serial = 4L, positionMs = 10_000L)
        barrier.markCommitted(4L)
        val oldToken = barrier.beginRead()
        barrier.arm(serial = 5L, positionMs = 50_000L)
        barrier.markCommitted(5L)

        assertEquals(SeekOutputBarrier.ReadDecision.Discard, barrier.finishRead(oldToken))
        assertEquals(
            SeekOutputBarrier.ReadDecision.AcceptAndRelease,
            barrier.finishRead(barrier.beginRead())
        )
    }
}
