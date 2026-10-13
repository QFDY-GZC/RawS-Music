package com.rawsmusic.module.player

import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class LatestSnapshotWriterTest {
    @Test fun slowWriterKeepsOnlyLatestOfTenThousandPendingSnapshots() = runBlocking {
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val writer = LatestSnapshotWriter<Int>(scope) {
            if (it == 0) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            values.add(it)
            if (it == 10000) done.countDown()
        }
        try {
            writer.submit(0)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            for (i in 1..10000) writer.submit(i)
            release.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertEquals(listOf(0, 10000), values.toList())
        } finally { release.countDown(); job.cancelAndJoin() }
    }
    @Test fun finalFlushPreventsPendingSnapshotFromOverwritingFinalState() = runBlocking {
        val job = SupervisorJob()
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val values = Collections.synchronizedList(mutableListOf<Int>())
        val writer = LatestSnapshotWriter<Int>(CoroutineScope(job + Dispatchers.IO)) {
            if (it == 0) { started.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
            values.add(it)
        }
        var flush: Thread? = null
        try {
            writer.submit(0)
            assertTrue(started.await(5, TimeUnit.SECONDS))
            writer.submit(1)
            flush = Thread { writer.flushFinal { values.add(99) } }.apply { start() }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (flush.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, flush.state)
            release.countDown()
            flush.join(5000)
            assertFalse(flush.isAlive)
            job.cancelAndJoin()
            assertEquals(listOf(0, 99), values.toList())
        } finally { release.countDown(); flush?.join(5000); job.cancelAndJoin() }
    }
}
