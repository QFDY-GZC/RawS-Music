package com.rawsmusic.module.scanner

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean

class AutomaticScanSchedulerTest {
    @Test fun automaticEventCannotReplaceManualBeforeItsTimerRuns() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val scheduler = AutomaticScanScheduler(scope, { true }, { 100000 }, { 100000 }, { false }, AtomicBoolean(), { release.await() }, { calls += it.reason })
        scheduler.manual("manual")
        scheduler.systemChanged("change")
        repeat(10) { yield() }
        release.complete(Unit)
        repeat(10) { yield() }
        assertEquals(listOf("manual"), calls)
        scheduler.destroy(); scope.cancel()
    }
    @Test fun disabledAutomaticStillAllowsManual() = runBlocking {
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val scheduler = AutomaticScanScheduler(scope, { false }, { 100000 }, { 100000 }, { false }, AtomicBoolean(), { }, { calls += it.reason })
        scheduler.startup("startup", true)
        scheduler.systemChanged("change")
        scheduler.manual("manual")
        repeat(10) { yield() }
        assertEquals(listOf("manual"), calls)
        scheduler.destroy(); scope.cancel()
    }
    @Test fun recreationDoesNotRepeatStartup() = runBlocking {
        val token = AtomicBoolean()
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        fun create() = AutomaticScanScheduler(scope, { true }, { 100000 }, { 100000 }, { false }, token, { }, { calls += it.reason })
        val first = create()
        first.startup("first", true)
        repeat(10) { yield() }
        first.destroy()
        val second = create()
        second.startup("second", true)
        repeat(10) { yield() }
        assertEquals(listOf("first"), calls)
        second.destroy(); scope.cancel()
    }
    @Test fun burstsMergeAndCancellationInvalidatesTimer() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val scheduler = AutomaticScanScheduler(scope, { true }, { 100000 }, { 100000 }, { false }, AtomicBoolean(), { release.await() }, { calls += it.reason })
        scheduler.systemChanged("one"); scheduler.systemChanged("two")
        repeat(10) { yield() }
        scheduler.cancel()
        repeat(10) { yield() }
        release.complete(Unit)
        repeat(10) { yield() }
        assertTrue(calls.isEmpty())
        scheduler.destroy(); scope.cancel()
    }
    @Test fun manualDuringScanIsPreservedAndNotOverwrittenByAutomatic() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val calls = mutableListOf<String>()
        val scope = CoroutineScope(coroutineContext + SupervisorJob())
        val scheduler = AutomaticScanScheduler(scope, { true }, { 100000 }, { 100000 }, { false }, AtomicBoolean(), { }, {
            calls += it.reason
            if (it.reason == "first") release.await()
        })
        scheduler.manual("first")
        repeat(10) { yield() }
        scheduler.manual("second"); scheduler.systemChanged("change")
        repeat(10) { yield() }
        release.complete(Unit)
        repeat(10) { yield() }
        assertEquals(listOf("first", "second"), calls)
        scheduler.destroy(); scope.cancel()
    }
}
