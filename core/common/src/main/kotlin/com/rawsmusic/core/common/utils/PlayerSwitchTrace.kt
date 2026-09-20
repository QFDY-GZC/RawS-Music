package com.rawsmusic.core.common.utils

import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Non-blocking, delayed diagnostic trace for one player track-switch transaction.
 *
 * Hot-path callers only read [System.nanoTime], increment atomics, and enqueue a small immutable
 * event into a lock-free queue. Nothing is printed while artwork/transport animation is running.
 * The complete transaction is rendered and emitted later on a dedicated background HandlerThread.
 */
object PlayerSwitchTrace {
    private const val TAG = "PlayerSwitchTrace"
    private const val MAX_EVENTS = 1600
    private const val MAX_FRAME_SAMPLES = 2048
    private const val FLUSH_DELAY_MS = 1_200L
    private const val WATCHDOG_FLUSH_MS = 5_000L
    private const val MAX_LOG_CHUNK = 3_500

    private data class Event(
        val atNs: Long,
        val name: String,
        val detail: String,
        val threadName: String,
    )

    private class CounterMetric {
        val total = AtomicLong(0L)
        val firstNs = AtomicLong(0L)
        val lastNs = AtomicLong(0L)
    }

    private class DurationMetric {
        val count = AtomicLong(0L)
        val totalNs = AtomicLong(0L)
        val maxNs = AtomicLong(0L)
        val firstNs = AtomicLong(0L)
        val lastNs = AtomicLong(0L)
    }

    private class Session(
        val id: Long,
        val startedNs: Long,
        val kind: String,
        val detail: String,
    ) {
        val events = ConcurrentLinkedQueue<Event>()
        val eventCount = AtomicInteger(0)
        val dropped = AtomicInteger(0)
        val frameCount = AtomicInteger(0)
        val frameDropped = AtomicInteger(0)
        val frameTimesNs = LongArray(MAX_FRAME_SAMPLES)
        val frameValues = FloatArray(MAX_FRAME_SAMPLES)
        val counters = ConcurrentHashMap<String, CounterMetric>()
        val durations = ConcurrentHashMap<String, DurationMetric>()
        val flushToken = AtomicLong(0L)
        val flushed = AtomicBoolean(false)
    }

    private val nextSessionId = AtomicLong(1L)
    private val current = AtomicReference<Session?>(null)
    private val workerStarted = AtomicBoolean(false)
    @Volatile private var workerHandler: Handler? = null

    /** Start the logging worker before the first user gesture so thread creation is never on a switch frame. */
    fun prepare() {
        if (!workerStarted.compareAndSet(false, true)) return
        val thread = HandlerThread("RawPlayerSwitchTrace", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        workerHandler = Handler(thread.looper)
    }

    fun isActive(): Boolean = current.get() != null

    /**
     * Ensure one active transaction. Nested artwork/transport begin calls join the same switch
     * instead of resetting the timeline.
     */
    fun begin(kind: String, detail: String = ""): Long {
        prepare()
        while (true) {
            val existing = current.get()
            if (existing != null) {
                enqueue(existing, "begin_nested:$kind", detail)
                return existing.id
            }
            val created = Session(
                id = nextSessionId.getAndIncrement(),
                startedNs = System.nanoTime(),
                kind = kind,
                detail = detail,
            )
            if (current.compareAndSet(null, created)) {
                enqueue(created, "begin", "kind=$kind $detail".trim())
                workerHandler?.postDelayed(
                    {
                        if (current.compareAndSet(created, null)) {
                            flush(created, "watchdog")
                        }
                    },
                    WATCHDOG_FLUSH_MS,
                )
                return created.id
            }
        }
    }

    fun mark(name: String, detail: String = "") {
        current.get()?.let { enqueue(it, name, detail) }
    }

    fun count(name: String, amount: Long = 1L) {
        val session = current.get() ?: return
        val now = System.nanoTime()
        val metric = session.counters.computeIfAbsent(name) { CounterMetric() }
        metric.total.addAndGet(amount)
        recordMetricTime(metric.firstNs, metric.lastNs, now)
    }

    fun duration(name: String, durationNs: Long) {
        val session = current.get() ?: return
        val now = System.nanoTime()
        val safeDuration = durationNs.coerceAtLeast(0L)
        val metric = session.durations.computeIfAbsent(name) { DurationMetric() }
        metric.count.incrementAndGet()
        metric.totalNs.addAndGet(safeDuration)
        while (true) {
            val previous = metric.maxNs.get()
            if (safeDuration <= previous || metric.maxNs.compareAndSet(previous, safeDuration)) break
        }
        recordMetricTime(metric.firstNs, metric.lastNs, now)
    }

    /**
     * Animation samples stay in a primitive ring instead of allocating Event/String objects on
     * every vsync. The trace itself therefore cannot create the GC stalls it is meant to measure.
     */
    fun frame(@Suppress("UNUSED_PARAMETER") name: String, value: Float) {
        val session = current.get() ?: return
        val index = session.frameCount.getAndIncrement()
        if (index >= MAX_FRAME_SAMPLES) {
            session.frameDropped.incrementAndGet()
            return
        }
        session.frameTimesNs[index] = System.nanoTime()
        session.frameValues[index] = value
    }

    private fun recordMetricTime(firstNs: AtomicLong, lastNs: AtomicLong, now: Long) {
        firstNs.compareAndSet(0L, now)
        lastNs.set(now)
    }

    /**
     * Keep the transaction open for a short tail after the visual settle so renderer/UI/background
     * callbacks that land immediately after the animation are still part of the same dump.
     */
    fun finishLater(reason: String, delayMs: Long = FLUSH_DELAY_MS) {
        val session = current.get() ?: return
        enqueue(session, "finish_requested", reason)
        val token = session.flushToken.incrementAndGet()
        val handler = workerHandler ?: return
        handler.postDelayed(
            {
                if (session.flushToken.get() != token || session.flushed.get()) return@postDelayed
                if (current.compareAndSet(session, null)) {
                    flush(session, reason)
                } else if (session.flushed.compareAndSet(false, true)) {
                    flushAlreadyClaimed(session, reason)
                }
            },
            delayMs.coerceAtLeast(0L),
        )
    }

    /** Called when a newer switch supersedes the active one before its delayed flush. */
    fun supersedeAndBegin(kind: String, detail: String = ""): Long {
        prepare()
        val old = current.getAndSet(null)
        if (old != null) {
            enqueue(old, "finish_requested", "superseded_by=$kind")
            workerHandler?.post {
                if (old.flushed.compareAndSet(false, true)) {
                    flushAlreadyClaimed(old, "superseded_by=$kind")
                }
            }
        }
        return begin(kind, detail)
    }

    private fun enqueue(session: Session, name: String, detail: String) {
        val index = session.eventCount.getAndIncrement()
        if (index >= MAX_EVENTS) {
            session.dropped.incrementAndGet()
            return
        }
        session.events.offer(Event(System.nanoTime(), name, detail, Thread.currentThread().name))
    }

    private fun flush(session: Session, reason: String) {
        if (!session.flushed.compareAndSet(false, true)) return
        flushAlreadyClaimed(session, reason)
    }

    private fun flushAlreadyClaimed(session: Session, reason: String) {
        val events = ArrayList<Event>(session.eventCount.get().coerceAtMost(MAX_EVENTS))
        while (true) {
            events.add(session.events.poll() ?: break)
        }
        val lastFrameIndex = session.frameCount.get().coerceAtMost(MAX_FRAME_SAMPLES) - 1
        val lastFrameNs = if (lastFrameIndex >= 0) session.frameTimesNs[lastFrameIndex] else 0L
        val observedEndNs = maxOf(events.lastOrNull()?.atNs ?: 0L, lastFrameNs)
        val endNs = observedEndNs.takeIf { it != 0L } ?: System.nanoTime()
        val totalMs = (endNs - session.startedNs).coerceAtLeast(0L) / 1_000_000.0
        var previousFrameNs = 0L
        var maxFrameGapNs = 0L
        var frameGapOver20 = 0
        var frameGapOver30 = 0
        val frameSamples = session.frameCount.get().coerceAtMost(MAX_FRAME_SAMPLES)
        val largestFrameGaps = ArrayList<Pair<Int, Long>>(8)
        for (index in 0 until frameSamples) {
            val atNs = session.frameTimesNs[index]
            if (atNs == 0L) continue
            if (previousFrameNs != 0L) {
                val gap = (atNs - previousFrameNs).coerceAtLeast(0L)
                maxFrameGapNs = maxOf(maxFrameGapNs, gap)
                if (gap >= 20_000_000L) frameGapOver20++
                if (gap >= 30_000_000L) frameGapOver30++
                if (gap >= 20_000_000L) {
                    largestFrameGaps.add(index to gap)
                    largestFrameGaps.sortByDescending { it.second }
                    if (largestFrameGaps.size > 8) largestFrameGaps.removeAt(largestFrameGaps.lastIndex)
                }
            }
            previousFrameNs = atNs
        }
        val header = buildString {
            append("TRACE id=").append(session.id)
            append(" kind=").append(session.kind)
            append(" total=").append(String.format(Locale.US, "%.3f", totalMs)).append("ms")
            append(" events=").append(events.size)
            append(" dropped=").append(session.dropped.get())
            append(" finish=").append(reason)
            append(" frameSamples=").append(frameSamples)
            append(" frameDropped=").append(session.frameDropped.get())
            append(" maxFrameGap=")
                .append(String.format(Locale.US, "%.3f", maxFrameGapNs / 1_000_000.0)).append("ms")
            append(" frameGap20=").append(frameGapOver20)
            append(" frameGap30=").append(frameGapOver30)
            if (session.detail.isNotBlank()) append(" detail=").append(session.detail)
        }
        Log.i(TAG, header)

        if (largestFrameGaps.isNotEmpty()) {
            val frameSummary = buildString {
                append("FRAME_GAPS id=").append(session.id)
                largestFrameGaps.forEach { (index, gapNs) ->
                    val startNs = session.frameTimesNs[index - 1]
                    val endNs = session.frameTimesNs[index]
                    append("\n#").append(index)
                    append(" +").append(String.format(Locale.US, "%.3f", (startNs - session.startedNs) / 1_000_000.0))
                    append("..").append(String.format(Locale.US, "%.3f", (endNs - session.startedNs) / 1_000_000.0))
                    append("ms gap=").append(String.format(Locale.US, "%.3f", gapNs / 1_000_000.0)).append("ms")
                    append(" ratio=").append(session.frameValues[index - 1])
                    append("->").append(session.frameValues[index])
                    val inside = events.asSequence()
                        .filter { it.atNs in startNs..endNs }
                        .take(6)
                        .map { it.name }
                        .toList()
                    if (inside.isNotEmpty()) {
                        append(" events=").append(inside.joinToString(","))
                    } else {
                        val before = events.lastOrNull { it.atNs < startNs }?.name
                        val after = events.firstOrNull { it.atNs > endNs }?.name
                        append(" events=-")
                        if (before != null) append(" before=").append(before)
                        if (after != null) append(" after=").append(after)
                    }
                }
            }
            Log.i(TAG, frameSummary)
        }

        if (session.counters.isNotEmpty() || session.durations.isNotEmpty()) {
            val metricSummary = buildString {
                append("METRICS id=").append(session.id)
                session.counters.toSortedMap().forEach { (name, metric) ->
                    append("\ncount:").append(name).append('=').append(metric.total.get())
                }
                session.durations.toSortedMap().forEach { (name, metric) ->
                    val count = metric.count.get().coerceAtLeast(1L)
                    val totalNs = metric.totalNs.get()
                    append("\nduration:").append(name)
                    append(" calls=").append(metric.count.get())
                    append(" avg=").append(String.format(Locale.US, "%.3f", totalNs / count / 1_000_000.0)).append("ms")
                    append(" max=").append(String.format(Locale.US, "%.3f", metric.maxNs.get() / 1_000_000.0)).append("ms")
                }
            }
            Log.i(TAG, metricSummary)
        }

        var previousNs = session.startedNs
        val chunk = StringBuilder(MAX_LOG_CHUNK + 256)
        events.forEachIndexed { index, event ->
            val fromStartMs = (event.atNs - session.startedNs).coerceAtLeast(0L) / 1_000_000.0
            val deltaMs = (event.atNs - previousNs).coerceAtLeast(0L) / 1_000_000.0
            previousNs = event.atNs
            val line = buildString {
                append('#').append(index.toString().padStart(3, '0'))
                append(" +").append(String.format(Locale.US, "%8.3f", fromStartMs)).append("ms")
                append(" d=").append(String.format(Locale.US, "%7.3f", deltaMs)).append("ms ")
                append('[').append(event.threadName).append("] ")
                append(event.name)
                if (event.detail.isNotBlank()) append(" | ").append(event.detail)
                append('\n')
            }
            if (chunk.length + line.length > MAX_LOG_CHUNK && chunk.isNotEmpty()) {
                Log.i(TAG, "TRACE id=${session.id}\n$chunk")
                chunk.setLength(0)
            }
            chunk.append(line)
        }
        if (chunk.isNotEmpty()) Log.i(TAG, "TRACE id=${session.id}\n$chunk")
        Log.i(TAG, "TRACE_END id=${session.id}")
    }
}
