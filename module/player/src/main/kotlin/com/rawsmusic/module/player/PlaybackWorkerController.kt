package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Owns the single playback worker used by FfmpegAudioPlayer.
 *
 * Future.cancel(false) can report isDone while a Runnable is still executing in
 * uninterruptible native code. This controller therefore tracks the Runnable's
 * real exit separately and never creates a replacement executor while an old
 * worker is alive. That invariant is required by the process-global USB engine.
 */
internal class PlaybackWorkerController(
    private val tag: String,
    private val threadName: String = "FfmpegAudioPlayer-Worker"
) {
    private var executor: ExecutorService = newExecutor()

    @Volatile
    private var currentTask: Future<*>? = null

    /** Includes queued tasks and tasks whose Runnable has not left finally. */
    private val outstandingTaskCount = AtomicInteger(0)
    private val idleMonitor = Object()

    private fun newExecutor(): ExecutorService = object : ThreadPoolExecutor(
        1,
        1,
        0L,
        TimeUnit.MILLISECONDS,
        LinkedBlockingQueue(),
        { runnable -> Thread(runnable, threadName).apply { isDaemon = true } }
    ) {
        override fun shutdownNow(): MutableList<Runnable> {
            val neverStarted = super.shutdownNow()
            neverStarted.forEach { task ->
                (task as? Future<*>)?.cancel(false)
            }
            return neverStarted
        }
    }

    fun currentTaskSnapshot(): Future<*>? = currentTask

    fun isIdle(): Boolean = outstandingTaskCount.get() == 0

    fun awaitIdle(timeoutMs: Long): Boolean {
        val boundedTimeoutMs = timeoutMs.coerceIn(1L, 30_000L)
        val deadlineNs = System.nanoTime() + boundedTimeoutMs * 1_000_000L
        synchronized(idleMonitor) {
            while (outstandingTaskCount.get() > 0) {
                val remainingNs = deadlineNs - System.nanoTime()
                if (remainingNs <= 0L) return false
                val waitMs = (remainingNs / 1_000_000L).coerceAtLeast(1L)
                try {
                    idleMonitor.wait(waitMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return false
                }
            }
        }
        return true
    }

    fun ensureActive(reason: String) {
        if (!executor.isShutdown && !executor.isTerminated) return
        if (!isIdle()) {
            AppLogger.e(tag, "Playback worker activation refused while old task is still running: reason=$reason")
            return
        }
        rebuild(reason)
    }

    /**
     * Waits for the old Runnable to actually leave its finally block. It does
     * not trust Future.isDone and does not create a parallel replacement worker.
     */
    fun ensureAvailableForReplacement(reason: String, timeoutMs: Long = 1_500L): Boolean {
        if (!awaitIdle(timeoutMs)) {
            AppLogger.e(
                tag,
                "Playback worker replacement refused: old task did not exit reason=$reason timeoutMs=$timeoutMs"
            )
            return false
        }
        ensureActive(reason)
        return !executor.isShutdown && !executor.isTerminated
    }

    fun rebuild(reason: String) {
        if (!isIdle()) {
            AppLogger.e(tag, "Playback worker rebuild refused while task is running: reason=$reason")
            return
        }
        val oldExecutor = executor
        executor = newExecutor()
        runCatching { oldExecutor.shutdownNow() }
            .onFailure { AppLogger.w(tag, "Playback worker old executor shutdown failed: reason=$reason", it) }
        AppLogger.w(tag, "Playback worker rebuilt: reason=$reason")
    }

    fun cancelCurrent(reason: String, interrupt: Boolean): Future<*>? {
        val task = currentTask
        currentTask = null
        if (task != null) {
            runCatching { task.cancel(interrupt) }
                .onFailure { AppLogger.w(tag, "Playback worker cancel failed: reason=$reason interrupt=$interrupt", it) }
        }
        return task
    }

    fun submit(reason: String, block: () -> Unit): Future<*> {
        ensureActive(reason)
        if (executor.isShutdown || executor.isTerminated) {
            throw RejectedExecutionException("Playback worker unavailable: reason=$reason")
        }
        val task = TrackedTask(reason, block)
        return try {
            executor.execute(task)
            task.also { currentTask = it }
        } catch (e: RejectedExecutionException) {
            task.cancel(false)
            throw e
        }
    }

    fun execute(reason: String, block: () -> Unit) {
        ensureActive(reason)
        if (executor.isShutdown || executor.isTerminated) {
            throw RejectedExecutionException("Playback worker unavailable: reason=$reason")
        }
        val task = TrackedTask(reason, block)
        try {
            executor.execute(task)
        } catch (e: RejectedExecutionException) {
            task.cancel(false)
            AppLogger.e(tag, "Playback worker rejected execute without parallel rebuild: reason=$reason", e)
            throw e
        }
    }

    private inner class TrackedTask(
        private val reason: String,
        block: () -> Unit
    ) : FutureTask<Unit>(Callable {
        block()
        Unit
    }) {
        private val started = AtomicBoolean(false)
        private val released = AtomicBoolean(false)

        init {
            val outstanding = outstandingTaskCount.incrementAndGet()
            AppLogger.d(tag, "Playback worker task reserved: reason=$reason outstanding=$outstanding")
        }

        override fun run() {
            started.set(true)
            AppLogger.d(tag, "Playback worker task started: reason=$reason")
            try {
                super.run()
            } finally {
                releaseOnce("runnable_finally")
            }
        }

        override fun done() {
            // FutureTask.done() runs immediately for cancel(false), including
            // while the Callable is still executing. Release here only if the
            // task never started; a running task releases from run/finally.
            if (!started.get()) {
                releaseOnce("cancelled_before_start")
            }
        }

        private fun releaseOnce(phase: String) {
            if (!released.compareAndSet(false, true)) return
            val remaining = outstandingTaskCount.decrementAndGet()
            synchronized(idleMonitor) {
                idleMonitor.notifyAll()
            }
            AppLogger.d(
                tag,
                "Playback worker task released: reason=$reason phase=$phase outstanding=$remaining"
            )
        }
    }

    fun shutdown(reason: String) {
        cancelCurrent(reason, interrupt = true)
        runCatching { executor.shutdownNow() }
            .onFailure { AppLogger.w(tag, "Playback worker shutdown failed: reason=$reason", it) }
    }
}
