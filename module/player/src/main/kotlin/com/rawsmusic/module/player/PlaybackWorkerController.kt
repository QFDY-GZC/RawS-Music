package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException

/**
 * Owns the single playback worker used by FfmpegAudioPlayer.
 *
 * FfmpegAudioPlayer still decides what work to run, but executor/task lifecycle is
 * kept here so play/stop/rebuild/release do not all hand-roll Future cancellation
 * and executor recovery.
 */
internal class PlaybackWorkerController(
    private val tag: String,
    private val threadName: String = "FfmpegAudioPlayer-Worker"
) {
    private var executor: ExecutorService = newExecutor()

    @Volatile
    private var currentTask: Future<*>? = null

    private fun newExecutor(): ExecutorService = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, threadName).apply { isDaemon = true }
    }

    fun currentTaskSnapshot(): Future<*>? = currentTask

    fun ensureActive(reason: String) {
        if (!executor.isShutdown && !executor.isTerminated) return
        rebuild(reason)
    }

    fun rebuild(reason: String) {
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
        val task = executor.submit(Runnable { block() })
        currentTask = task
        return task
    }

    fun execute(reason: String, block: () -> Unit) {
        try {
            ensureActive(reason)
            executor.execute(Runnable { block() })
        } catch (e: RejectedExecutionException) {
            AppLogger.w(tag, "Playback worker rejected execute: reason=$reason; rebuilding once", e)
            rebuild("rejected_$reason")
            executor.execute(Runnable { block() })
        }
    }

    fun shutdown(reason: String) {
        cancelCurrent(reason, interrupt = false)
        runCatching { executor.shutdown() }
            .onFailure { AppLogger.w(tag, "Playback worker shutdown failed: reason=$reason", it) }
    }
}
