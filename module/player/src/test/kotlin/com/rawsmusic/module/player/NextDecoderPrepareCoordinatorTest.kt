package com.rawsmusic.module.player

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NextDecoderPrepareCoordinatorTest {
    @Test
    fun `same plan is scheduled once and executes off caller thread`() {
        val executor = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "next-prepare-test")
        }
        try {
            val prepareCount = AtomicInteger(0)
            val done = CountDownLatch(1)
            val callerThread = Thread.currentThread().name
            var workerThread = ""
            val coordinator = NextDecoderPrepareCoordinator(
                tag = "test",
                executor = executor,
                isRequestCurrent = { true },
                isAlreadyPrepared = { false },
                prepare = {
                    workerThread = Thread.currentThread().name
                    prepareCount.incrementAndGet()
                    done.countDown()
                    true
                },
            )
            val request = NextDecoderPrepareCoordinator.Request("next.flac", 3, 8L, "state_playing")
            assertTrue(coordinator.schedule(request))
            assertTrue(coordinator.schedule(request))
            assertTrue(done.await(2, TimeUnit.SECONDS))
            assertEquals(1, prepareCount.get())
            assertFalse(workerThread == callerThread)
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `obsolete plan is rejected before touching decoder`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val prepareCount = AtomicInteger(0)
            val coordinator = NextDecoderPrepareCoordinator(
                tag = "test",
                executor = executor,
                isRequestCurrent = { false },
                isAlreadyPrepared = { false },
                prepare = {
                    prepareCount.incrementAndGet()
                    true
                },
            )
            assertFalse(
                coordinator.schedule(
                    NextDecoderPrepareCoordinator.Request("old.flac", 1, 2L, "obsolete"),
                ),
            )
            assertEquals(0, prepareCount.get())
        } finally {
            executor.shutdownNow()
        }
    }
}

// Additional Phase 9A6 coverage is kept in a separate class to avoid changing
// the original test names used by earlier focused harnesses.
class NextDecoderPrepareCoordinatorStateTest {
    @Test
    fun `failed prepare exposes terminal detail and allows a later retry`() {
        val executor = Executors.newSingleThreadExecutor()
        try {
            val attempt = AtomicInteger(0)
            val done = CountDownLatch(2)
            val coordinator = NextDecoderPrepareCoordinator(
                tag = "test",
                executor = executor,
                isRequestCurrent = { true },
                isAlreadyPrepared = { false },
                prepare = {
                    val current = attempt.incrementAndGet()
                    done.countDown()
                    current >= 2
                },
                outcomeDetail = { _, ok -> if (ok) "prepared" else "native_slot_rejected_ready" },
            )
            val request = NextDecoderPrepareCoordinator.Request("next.flac", 3, 9L, "manual")
            assertTrue(coordinator.schedule(request))
            while (coordinator.snapshot("next.flac", 3, 9L).inFlight) Thread.sleep(1L)
            val failed = coordinator.snapshot("next.flac", 3, 9L)
            assertEquals(NextDecoderPrepareCoordinator.State.FAILED, failed.state)
            assertEquals("native_slot_rejected_ready", failed.detail)

            assertTrue(coordinator.schedule(request))
            assertTrue(done.await(2, TimeUnit.SECONDS))
            while (coordinator.snapshot("next.flac", 3, 9L).inFlight) Thread.sleep(1L)
            val succeeded = coordinator.snapshot("next.flac", 3, 9L)
            assertEquals(NextDecoderPrepareCoordinator.State.SUCCEEDED, succeeded.state)
            assertEquals(2, succeeded.attempt)
        } finally {
            executor.shutdownNow()
        }
    }
}
