package com.rawsmusic.core.common.utils

import android.os.Debug
import android.os.SystemClock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * Low-overhead power diagnostics.
 *
 * Logs are deliberately aggregated and throttled so the diagnostics themselves do not become a new
 * battery drain.  Filter logcat by `PowerTrace`, or export the normal RawSMusic log file.
 */
object PowerTraceLogger {
    private const val TAG = "PowerTrace"
    private const val SUMMARY_INTERVAL_MS = 10_000L
    private const val FLOW_INTERVAL_MS = 15_000L

    private val lastLogAt = ConcurrentHashMap<String, AtomicLong>()

    private val flowFrames = AtomicLong(0L)
    private val flowActiveFrames = AtomicLong(0L)
    private val bitmapRequests = AtomicLong(0L)
    private val bitmapCacheHits = AtomicLong(0L)
    private val bitmapQueued = AtomicLong(0L)
    private val bitmapJoined = AtomicLong(0L)
    private val bitmapDecoded = AtomicLong(0L)
    private val bitmapDecodeMisses = AtomicLong(0L)
    private val bitmapDecodeTotalMs = AtomicLong(0L)
    private val libraryLoads = AtomicLong(0L)

    fun flowMode(
        mode: String,
        isDark: Boolean,
        coverKey: String?
    ) {
        logNow(
            key = "flow.mode",
            message = "FLOW_MODE mode=$mode themeDark=$isDark cover=${coverKey.safeTail()} ${memorySummary()}"
        )
    }

    fun flowFrame(
        mode: String,
        enabled: Boolean,
        frameIntervalMs: Long
    ) {
        val frames = flowFrames.incrementAndGet()
        if (enabled) flowActiveFrames.incrementAndGet()
        if (!shouldLog("flow.frame", FLOW_INTERVAL_MS)) return

        log(
            "FLOW_FRAME mode=$mode enabled=$enabled frames=$frames activeFrames=${flowActiveFrames.get()} " +
                "targetInterval=${frameIntervalMs}ms approxFps=${1000f / frameIntervalMs.coerceAtLeast(1L)} " +
                memorySummary()
        )
    }

    fun flowPalette(
        stage: String,
        mode: String,
        source: String,
        colorCount: Int,
        elapsedMs: Long,
        coverKey: String?
    ) {
        logNow(
            key = "flow.palette.$stage",
            message = "FLOW_PALETTE stage=$stage mode=$mode source=$source colors=$colorCount " +
                "elapsed=${elapsedMs}ms cover=${coverKey.safeTail()} ${memorySummary()}"
        )
    }

    fun bitmapProviderInit(
        workerCount: Int,
        cacheMaxBytes: Long
    ) {
        logNow(
            key = "bitmap.init",
            message = "BITMAP_INIT workers=$workerCount cacheMax=${cacheMaxBytes / 1024}KB ${memorySummary()}"
        )
    }

    fun bitmapRequest(
        state: String,
        priority: String,
        size: String,
        key: String?
    ) {
        bitmapRequests.incrementAndGet()
        when (state) {
            "cache_hit" -> bitmapCacheHits.incrementAndGet()
            "queued" -> bitmapQueued.incrementAndGet()
            "join_in_flight" -> bitmapJoined.incrementAndGet()
        }
        if (!shouldLog("bitmap.summary", SUMMARY_INTERVAL_MS)) return
        logBitmapSummary(extra = "lastRequest=$state priority=$priority size=$size key=${key.safeTail()}")
    }

    fun bitmapDecodeDone(
        priority: String,
        size: String,
        result: Boolean,
        elapsedMs: Long,
        key: String?
    ) {
        bitmapDecoded.incrementAndGet()
        bitmapDecodeTotalMs.addAndGet(elapsedMs.coerceAtLeast(0L))
        if (!result) bitmapDecodeMisses.incrementAndGet()
        if (!shouldLog("bitmap.decode", SUMMARY_INTERVAL_MS)) return
        logBitmapSummary(extra = "lastDecode result=$result priority=$priority size=$size elapsed=${elapsedMs}ms key=${key.safeTail()}")
    }

    fun repositoryStartup(
        stage: String,
        reason: String,
        songs: Int,
        elapsedMs: Long
    ) {
        logNow(
            key = "repo.$stage",
            message = "REPO stage=$stage reason=$reason songs=$songs elapsed=${elapsedMs}ms ${memorySummary()}"
        )
    }

    fun libraryLoad(
        stage: String,
        songs: Int,
        elapsedMs: Long,
        fromCache: Boolean
    ) {
        libraryLoads.incrementAndGet()
        logNow(
            key = "library.$stage",
            message = "LIBRARY stage=$stage songs=$songs elapsed=${elapsedMs}ms fromCache=$fromCache loads=${libraryLoads.get()} ${memorySummary()}"
        )
    }

    fun playerStartup(
        stage: String,
        detail: String,
        elapsedMs: Long
    ) {
        logNow(
            key = "player.$stage",
            message = "PLAYER stage=$stage elapsed=${elapsedMs}ms $detail ${memorySummary()}"
        )
    }

    private fun logBitmapSummary(extra: String) {
        val decoded = bitmapDecoded.get()
        val avgDecodeMs = if (decoded > 0L) bitmapDecodeTotalMs.get() / decoded else 0L
        log(
            "BITMAP_SUMMARY requests=${bitmapRequests.get()} cacheHits=${bitmapCacheHits.get()} " +
                "queued=${bitmapQueued.get()} joined=${bitmapJoined.get()} decoded=$decoded " +
                "misses=${bitmapDecodeMisses.get()} avgDecode=${avgDecodeMs}ms $extra ${memorySummary()}"
        )
    }

    private fun shouldLog(key: String, intervalMs: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val holder = lastLogAt.getOrPut(key) { AtomicLong(0L) }
        while (true) {
            val previous = holder.get()
            if (now - previous < intervalMs) return false
            if (holder.compareAndSet(previous, now)) return true
        }
    }

    private fun logNow(key: String, message: String) {
        // Same-stage logs can be noisy on startup; keep them visible but still lightly throttled.
        if (shouldLog(key, 1_000L)) log(message)
    }

    private fun log(message: String) {
        AppLogger.i(TAG, message)
    }

    private fun memorySummary(): String {
        val runtime = Runtime.getRuntime()
        val usedKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024L
        return "javaHeap=${usedKb}KB nativeHeap=${nativeKb}KB"
    }

    private fun String?.safeTail(limit: Int = 56): String {
        if (this.isNullOrBlank()) return "-"
        return takeLast(limit).replace('\n', ' ').replace('\r', ' ')
    }
}
