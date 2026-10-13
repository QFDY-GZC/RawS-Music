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
    @Test
    fun oldReadRemainsInvalidAfterNewSeekHasReleased() {
        val barrier = SeekOutputBarrier()
        val oldToken = barrier.beginRead()
        barrier.arm(6L, 60_000L)
        barrier.markCommitted(6L)
        barrier.finishRead(barrier.beginRead())
        assertEquals(SeekOutputBarrier.ReadDecision.Discard, barrier.finishRead(oldToken))
    }
    @Test
    fun partialWriteStopsAtNewSeekAndStaysInvalidAfterRelease() {
        val barrier = SeekOutputBarrier()
        val token = barrier.beginRead()
        assertEquals(true, barrier.canContinueWrite(token))
        barrier.arm(7L, 70_000L)
        assertEquals(false, barrier.canContinueWrite(token))
        barrier.markCommitted(7L)
        val fresh = barrier.beginRead()
        barrier.finishRead(fresh)
        assertEquals(true, barrier.canContinueWrite(fresh))
        assertEquals(false, barrier.canContinueWrite(token))
    }
    @Test
    fun oldWriteCannotBeAdmittedAfterSeekEvenWhenBarrierReleased() {
        val barrier = SeekOutputBarrier()
        val old = barrier.beginRead()
        barrier.arm(8L, 80_000L)
        barrier.markCommitted(8L)
        val fresh = barrier.beginRead()
        barrier.finishRead(fresh)
        var writes = 0
        assertEquals(null, barrier.writeIfCurrent(old) { writes++; 32 })
        assertEquals(32, barrier.writeIfCurrent(fresh) { writes++; 32 })
        assertEquals(1, writes)
    }

    @Test
    fun seekArmingWaitsForAlreadyAdmittedNativeWrite() {
        val barrier = SeekOutputBarrier()
        val token = barrier.beginRead()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val executor = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val writing = executor.submit<Int?> {
                barrier.writeIfCurrent(token) {
                    entered.countDown()
                    check(release.await(2, java.util.concurrent.TimeUnit.SECONDS))
                    32
                }
            }
            check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS))
            val arming = executor.submit { barrier.arm(9L, 90_000L) }
            assertEquals(false, arming.isDone)
            release.countDown()
            assertEquals(32, writing.get(2, java.util.concurrent.TimeUnit.SECONDS))
            arming.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(null, barrier.writeIfCurrent(token) { error("old retry admitted") })
        } finally {
            release.countDown()
            executor.shutdownNow()
        }
    }
}
