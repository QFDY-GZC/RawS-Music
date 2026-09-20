package com.rawsmusic.core.ui.perf

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.Choreographer
import android.os.Debug
import android.view.FrameMetrics
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.View
import android.view.Window
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicLongArray
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Temporary low-overhead diagnostics for HOME/category and VirtualList transitions.
 *
 * The probe is intentionally aggregate-only: no per-frame Log spam and no object allocation in the
 * Choreographer hot path. Filter logcat with `TransitionPerf`. Remove this file/call sites after the
 * transition bottleneck has been identified.
 */
internal enum class TransitionPerfStage {
    POPULATION_BIND,
    NODE_PREFLIGHT,
    NODE_CONTENT,
    LIST_DRAW,
    VIEW_CONTENT_PREPARE,
    VIEW_PROPERTY_SYNC,
    VIEW_HOLDER_STATE_APPLY,
    VIEW_STRUCTURE_RECONCILE,
    SCENE_PREPARE,
    SCENE_ANIMATE,
    VIRTUALLIST_METRICS,
    VIRTUALLIST_TRANSITION_SNAPSHOT,
    VIRTUALLIST_ITEM_MEASURE,
    ARTWORK_NODE_UPDATE,
    BITMAP_SYNC_LOOKUP,
    BITMAP_PROVIDER_ADMISSION,
    BITMAP_WORKER_DECODE,
    LIBRARY_PROVIDER_META,
}

internal enum class TransitionPerfEvent {
    GESTURE_BUSY_REJECTED,
    VIEW_CONTENT_DIRTY,
    VIEW_CHILD_ADD,
    VIEW_CHILD_MOVE,
    VIEW_CHILD_REMOVE,
    VIEW_HOLDER_POOL_HIT,
    VIEW_HOLDER_COLD_CREATE,
    VIEW_HOLDER_RELAYOUT,
    VIEW_HOLDER_DIRECT_FRAME,
    VIEW_TRANSLATION_MUTATION,
    VIEW_SCALE_MUTATION,
    VIEW_ALPHA_MUTATION,
    BASE_NODE_RECORD,
    ARTWORK_NODE_RECORD,
    TEXT_NODE_RECORD,
    NATIVE_ITEMS_PUBLISHED,
    COMPAT_ITEMS_PUBLISHED,
    TRANSITION_ITEMS_SNAPSHOT,
    TRANSITION_ITEM_COMPOSE,
    TRANSITION_ITEM_LAYOUT,
    TRANSITION_LAYER_UPDATE,
    ARTWORK_REQUEST,
    ARTWORK_QUEUED,
    ARTWORK_JOINED,
    ARTWORK_CANCELLED,
    BITMAP_RESULT_HARDWARE,
    BITMAP_RESULT_SOFTWARE,
    HARDWARE_CPU_READBACK_COPY,
    SCROLL_HANDOFF_CANCELLED,
}

internal enum class TransitionPerfPhase {
    PREPARE,
    ANIMATE,
    ENDPOINT,
}

internal object TransitionPerfTrace {
    private data class WorstFrame(val session: Long, val atNs: Long, val total: Long,
        val layout: Long, val draw: Long, val command: Long, val sync: Long, val unknown: Long, val gpu: Long)
    private data class SlowFrame(
        val phase: TransitionPerfPhase,
        val atNs: Long,
        val total: Long,
        val layout: Long,
        val draw: Long,
        val command: Long,
        val sync: Long,
        val swap: Long,
        val unknown: Long,
        val gpu: Long,
    )
    private data class MemorySnapshot(
        val javaKb: Long,
        val nativeKb: Long,
        val gcCount: Long,
        val blockingGcCount: Long,
        val gcTimeMs: Long,
        val bytesAllocated: Long,
        val bytesFreed: Long,
    )
    private val worstFrame = AtomicReference<WorstFrame?>(null)
    private const val TAG = "TransitionPerf"
    private const val MAX_FRAME_SAMPLES = 1024

    private val stageTotalNs = AtomicLongArray(TransitionPerfStage.values().size)
    private val stageCount = AtomicLongArray(TransitionPerfStage.values().size)
    private val stageMaxNs = AtomicLongArray(TransitionPerfStage.values().size)
    private val eventCount = AtomicLongArray(TransitionPerfEvent.values().size)
    private val phaseEventCount = AtomicLongArray(
        TransitionPerfPhase.values().size * TransitionPerfEvent.values().size
    )
    private val phaseFrames = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseTotalNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseTotalMaxNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseCommandNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseCommandMaxNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseUnknownNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseUnknownMaxNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseGpuNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseGpuMaxNs = AtomicLongArray(TransitionPerfPhase.values().size)
    private val phaseGpuFrames = AtomicLongArray(TransitionPerfPhase.values().size)

    private val metricsFrames = AtomicLong(0L)
    private val metricsTotalNs = AtomicLong(0L)
    private val metricsLayoutNs = AtomicLong(0L)
    private val metricsDrawNs = AtomicLong(0L)
    private val metricsCommandNs = AtomicLong(0L)
    private val metricsSyncNs = AtomicLong(0L)
    private val metricsSwapNs = AtomicLong(0L)
    private val metricsUnknownNs = AtomicLong(0L)
    private val metricsGpuNs = AtomicLong(0L)
    private val metricsMaxTotalNs = AtomicLong(0L)
    private val metricsLayoutMaxNs = AtomicLong(0L)
    private val metricsDrawMaxNs = AtomicLong(0L)
    private val metricsCommandMaxNs = AtomicLong(0L)
    private val metricsSyncMaxNs = AtomicLong(0L)
    private val metricsSwapMaxNs = AtomicLong(0L)
    private val metricsUnknownMaxNs = AtomicLong(0L)
    private val metricsGpuMaxNs = AtomicLong(0L)
    private val metricsGpuFrames = AtomicLong(0L)
    private val metricsDroppedReports = AtomicLong(0L)
    private val totalOverBudgetFrames = AtomicLong(0L)
    private val commandHeavyFrames = AtomicLong(0L)
    private val drawHeavyFrames = AtomicLong(0L)
    private val unknownHeavyFrames = AtomicLong(0L)
    private val gpuHeavyFrames = AtomicLong(0L)
    private val slowFrameLock = Any()
    private val slowFrames = ArrayList<SlowFrame>(8)
    @Volatile private var memoryStart: MemorySnapshot? = null
    @Volatile private var resourceStart: String = "-"
    @Volatile private var memoryAnimateStart: MemorySnapshot? = null
    @Volatile private var memoryEndpointStart: MemorySnapshot? = null
    @Volatile private var resourceAnimateStart: String = "-"
    @Volatile private var resourceEndpointStart: String = "-"
    @Volatile private var animateStartNs = Long.MAX_VALUE
    @Volatile private var endpointStartNs = Long.MAX_VALUE

    @Volatile private var active = false
    @Volatile private var sessionId = 0L
    @Volatile private var label = "-"
    @Volatile private var expectedFrameNs = 16_666_667L
    private var sessionStartNs = 0L
    private var lastFrameNs = 0L
    private var choreographerFrames = 0L
    private var jankFrames = 0L
    private var estimatedDroppedFrames = 0L
    private var maxFrameDeltaNs = 0L
    private val frameSamples = LongArray(MAX_FRAME_SAMPLES)
    private var frameSampleCount = 0
    private val markerLock = Any()
    private val recentMarkers = ArrayDeque<String>(12)

    private var attachedWindow: Window? = null
    private var frameMetricsThread: HandlerThread? = null
    private var frameMetricsListener: Window.OnFrameMetricsAvailableListener? = null

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!active) return
            val previous = lastFrameNs
            if (previous != 0L) {
                val delta = (frameTimeNanos - previous).coerceAtLeast(0L)
                choreographerFrames += 1L
                if (delta > expectedFrameNs * 3L / 2L) jankFrames += 1L
                val frameSlots = ((delta.toDouble() / expectedFrameNs.toDouble()) + 0.5).toLong().coerceAtLeast(1L)
                estimatedDroppedFrames += (frameSlots - 1L).coerceAtLeast(0L)
                if (delta > maxFrameDeltaNs) maxFrameDeltaNs = delta
                if (frameSampleCount < frameSamples.size) {
                    frameSamples[frameSampleCount++] = delta
                }
            }
            lastFrameNs = frameTimeNanos
            if (active) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun attach(view: View) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val window = view.context.findActivity()?.window ?: return
        if (attachedWindow === window) return
        detach()
        val thread = HandlerThread("TransitionFrameMetrics", Process.THREAD_PRIORITY_BACKGROUND).apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, dropCountSinceLastInvocation ->
            if (!active) return@OnFrameMetricsAvailableListener
            val measuredSession = sessionId
            // Metrics arrive asynchronously; a previous route's queued frame is not this session.
            val intendedVsync = metrics.getMetric(FrameMetrics.INTENDED_VSYNC_TIMESTAMP)
            if (intendedVsync > 0L && intendedVsync < sessionStartNs) return@OnFrameMetricsAvailableListener
            val total = metrics.getMetric(FrameMetrics.TOTAL_DURATION).coerceAtLeast(0L)
            val layout = metrics.getMetric(FrameMetrics.LAYOUT_MEASURE_DURATION).coerceAtLeast(0L)
            val draw = metrics.getMetric(FrameMetrics.DRAW_DURATION).coerceAtLeast(0L)
            val command = metrics.getMetric(FrameMetrics.COMMAND_ISSUE_DURATION).coerceAtLeast(0L)
            val sync = metrics.getMetric(FrameMetrics.SYNC_DURATION).coerceAtLeast(0L)
            val swap = metrics.getMetric(FrameMetrics.SWAP_BUFFERS_DURATION).coerceAtLeast(0L)
            val unknown = metrics.getMetric(FrameMetrics.UNKNOWN_DELAY_DURATION).coerceAtLeast(0L)
            val gpu = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                metrics.getMetric(FrameMetrics.GPU_DURATION)
            } else {
                -1L
            }
            val phase = phaseForTimestamp(intendedVsync)
            val phaseIndex = phase.ordinal
            phaseFrames.incrementAndGet(phaseIndex)
            phaseTotalNs.addAndGet(phaseIndex, total)
            phaseCommandNs.addAndGet(phaseIndex, command)
            phaseUnknownNs.addAndGet(phaseIndex, unknown)
            updateMax(phaseTotalMaxNs, phaseIndex, total)
            updateMax(phaseCommandMaxNs, phaseIndex, command)
            updateMax(phaseUnknownMaxNs, phaseIndex, unknown)
            if (gpu >= 0L) {
                phaseGpuFrames.incrementAndGet(phaseIndex)
                phaseGpuNs.addAndGet(phaseIndex, gpu)
                updateMax(phaseGpuMaxNs, phaseIndex, gpu)
            }
            val previousWorst = worstFrame.get()
            if (previousWorst == null || previousWorst.session != measuredSession || total > previousWorst.total) {
                if (active && sessionId == measuredSession) worstFrame.compareAndSet(previousWorst,
                    WorstFrame(measuredSession, intendedVsync - sessionStartNs, total, layout, draw, command, sync, unknown, gpu))
            }
            metricsFrames.incrementAndGet()
            metricsTotalNs.addAndGet(total)
            metricsLayoutNs.addAndGet(layout)
            metricsDrawNs.addAndGet(draw)
            metricsCommandNs.addAndGet(command)
            metricsSyncNs.addAndGet(sync)
            metricsSwapNs.addAndGet(swap)
            metricsUnknownNs.addAndGet(unknown)
            if (gpu >= 0L) {
                metricsGpuFrames.incrementAndGet()
                metricsGpuNs.addAndGet(gpu)
                updateMax(metricsGpuMaxNs, gpu)
                if (gpu > expectedFrameNs / 2L) gpuHeavyFrames.incrementAndGet()
            }
            metricsDroppedReports.addAndGet(dropCountSinceLastInvocation.toLong().coerceAtLeast(0L))
            updateMax(metricsMaxTotalNs, total)
            updateMax(metricsLayoutMaxNs, layout)
            updateMax(metricsDrawMaxNs, draw)
            updateMax(metricsCommandMaxNs, command)
            updateMax(metricsSyncMaxNs, sync)
            updateMax(metricsSwapMaxNs, swap)
            updateMax(metricsUnknownMaxNs, unknown)
            if (total > expectedFrameNs) totalOverBudgetFrames.incrementAndGet()
            if (command > expectedFrameNs / 2L) commandHeavyFrames.incrementAndGet()
            if (draw > expectedFrameNs / 2L) drawHeavyFrames.incrementAndGet()
            if (unknown > expectedFrameNs / 2L) unknownHeavyFrames.incrementAndGet()
            recordSlowFrame(
                SlowFrame(
                    phase = phase,
                    atNs = (intendedVsync - sessionStartNs).coerceAtLeast(0L),
                    total = total,
                    layout = layout,
                    draw = draw,
                    command = command,
                    sync = sync,
                    swap = swap,
                    unknown = unknown,
                    gpu = gpu,
                )
            )
        }
        window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper))
        attachedWindow = window
        frameMetricsThread = thread
        frameMetricsListener = listener
    }

    fun detach() {
        val window = attachedWindow
        val listener = frameMetricsListener
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && window != null && listener != null) {
            runCatching { window.removeOnFrameMetricsAvailableListener(listener) }
        }
        frameMetricsListener = null
        attachedWindow = null
        frameMetricsThread?.quitSafely()
        frameMetricsThread = null
    }

    fun start(newLabel: String, refreshRateHz: Float): Long {
        if (active) stop("superseded")
        reset()
        sessionId += 1L
        label = newLabel
        val hz = refreshRateHz.takeIf { it.isFinite() && it >= 30f } ?: 60f
        expectedFrameNs = (1_000_000_000.0 / hz.toDouble()).roundToLong().coerceAtLeast(1L)
        memoryStart = memorySnapshot()
        resourceStart = runCatching { BitmapProvider.transitionDiagnosticsSummary() }.getOrDefault("unavailable")
        AppLogger.i(
            TAG,
            "START id=$sessionId label=$label budget=${ms(expectedFrameNs)}ms " +
                "memory=${memorySnapshotSummary(memoryStart)} resources=$resourceStart"
        )
        sessionStartNs = System.nanoTime()
        active = true
        Choreographer.getInstance().postFrameCallback(frameCallback)
        return sessionId
    }

    fun stop(expectedSessionId: Long, reason: String) {
        if (!active || sessionId != expectedSessionId) return
        stop(reason)
    }

    fun stop(reason: String) {
        if (!active) return
        active = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        val elapsedNs = (System.nanoTime() - sessionStartNs).coerceAtLeast(1L)
        val fps = choreographerFrames * 1_000_000_000.0 / elapsedNs.toDouble()
        val p95 = percentile95()
        val memoryEnd = memorySnapshot()
        val resourceEnd = runCatching { BitmapProvider.transitionDiagnosticsSummary() }.getOrDefault("unavailable")
        AppLogger.i(
            TAG,
            "END id=$sessionId label=$label reason=$reason budget=${ms(expectedFrameNs)}ms elapsed=${ms(elapsedNs)}ms fps=${"%.1f".format(fps)} " +
                "frames=$choreographerFrames jank=$jankFrames estDropped=$estimatedDroppedFrames " +
                "maxFrame=${ms(maxFrameDeltaNs)}ms p95=${ms(p95)}ms ${windowMetricsSummary()} " +
                "suspect=${suspectSummary()} ${worstFrameSummary()} ${stageSummary()} ${eventSummary()} " +
                "${phaseSummary()} slow=${slowFrameSummary()} recent=${markerSummary(clear = true)} " +
                "memory=${memoryDeltaSummary(memoryStart, memoryEnd)} ${phaseMemorySummary(memoryEnd)} " +
                "resourcesStart={$resourceStart} resourcesEnd={$resourceEnd}"
        )
    }

    fun isActive(): Boolean = active

    fun currentSessionId(): Long = if (active) sessionId else 0L

    fun exportContext(): String = if (active) {
        val elapsedMs = ((System.nanoTime() - sessionStartNs).coerceAtLeast(0L) / 1_000_000L)
        "id=$sessionId t=${elapsedMs}ms label=${label.take(80)}"
    } else {
        "id=0"
    }

    fun overlapSummary(): String = if (active) {
        "active:id=$sessionId:${label.take(64)}"
    } else {
        "inactive"
    }

    fun mark(name: String, detail: String = "") {
        if (!active) return
        val elapsedMs = ((System.nanoTime() - sessionStartNs).coerceAtLeast(0L) / 1_000_000L)
        val entry = buildString {
            append(elapsedMs)
            append("ms:")
            append(name)
            if (detail.isNotBlank()) {
                append('(')
                append(detail.replace('\n', ' ').replace('\r', ' ').replace('|', '/').take(180))
                append(')')
            }
        }
        synchronized(markerLock) {
            while (recentMarkers.size >= 12) recentMarkers.removeFirst()
            recentMarkers.addLast(entry)
        }
    }

    fun markAnimateStart(detail: String = "") {
        if (!active) return
        animateStartNs = System.nanoTime()
        memoryAnimateStart = memorySnapshot()
        resourceAnimateStart = runCatching { BitmapProvider.transitionDiagnosticsSummary() }
            .getOrDefault("unavailable")
        mark("scene_animate_start", detail)
    }

    fun markEndpointStart(detail: String = "") {
        if (!active) return
        endpointStartNs = System.nanoTime()
        memoryEndpointStart = memorySnapshot()
        resourceEndpointStart = runCatching { BitmapProvider.transitionDiagnosticsSummary() }
            .getOrDefault("unavailable")
        mark("endpoint_owner_begin", detail)
    }

    fun recordDuration(stage: TransitionPerfStage, durationNs: Long) {
        if (!active || durationNs <= 0L) return
        val index = stage.ordinal
        stageTotalNs.addAndGet(index, durationNs)
        stageCount.incrementAndGet(index)
        updateMax(stageMaxNs, index, durationNs)
    }

    fun count(event: TransitionPerfEvent, amount: Long = 1L) {
        if (!active || amount <= 0L) return
        eventCount.addAndGet(event.ordinal, amount)
        val phase = phaseForTimestamp(System.nanoTime())
        val width = TransitionPerfEvent.values().size
        phaseEventCount.addAndGet(phase.ordinal * width + event.ordinal, amount)
    }

    fun <T> measure(stage: TransitionPerfStage, block: () -> T): T {
        if (!active) return block()
        val start = System.nanoTime()
        return try {
            block()
        } finally {
            recordDuration(stage, System.nanoTime() - start)
        }
    }


    private fun worstFrameSummary(): String {
        val frame = worstFrame.get()?.takeIf { it.session == sessionId } ?: return "worst=-"
        return "worst(at=${ms(frame.atNs)}ms,total=${ms(frame.total)},layout=${ms(frame.layout)},draw=${ms(frame.draw)}," +
            "command=${ms(frame.command)},sync=${ms(frame.sync)},unknown=${ms(frame.unknown)},gpu=${if (frame.gpu >= 0) ms(frame.gpu) else "NA"})"
    }

    private fun stageSummary(): String {
        val parts = ArrayList<String>(TransitionPerfStage.values().size)
        TransitionPerfStage.values().forEach { stage ->
            val i = stage.ordinal
            val count = stageCount.get(i)
            if (count > 0L) {
                parts += "${stage.name.lowercase()}=$count/${ms(stageTotalNs.get(i))}ms/max${ms(stageMaxNs.get(i))}"
            }
        }
        return if (parts.isEmpty()) "stages=-" else "stages=${parts.joinToString(",")}"
    }

    private fun eventSummary(): String {
        val parts = ArrayList<String>(TransitionPerfEvent.values().size)
        TransitionPerfEvent.values().forEach { event ->
            val count = eventCount.get(event.ordinal)
            if (count > 0L) parts += "${event.name.lowercase()}=$count"
        }
        return if (parts.isEmpty()) "events=-" else "events=${parts.joinToString(",")}"
    }

    private fun phaseSummary(): String {
        val eventWidth = TransitionPerfEvent.values().size
        return TransitionPerfPhase.values().joinToString(separator = " ") { phase ->
            val i = phase.ordinal
            val frames = phaseFrames.get(i)
            val gpuFrames = phaseGpuFrames.get(i)
            fun avg(values: AtomicLongArray, count: Long): String =
                if (count > 0L) ms(values.get(i) / count) else "0.00"
            val eventParts = ArrayList<String>()
            TransitionPerfEvent.values().forEach { event ->
                val count = phaseEventCount.get(i * eventWidth + event.ordinal)
                if (count > 0L) eventParts += "${event.name.lowercase()}=$count"
            }
            "phase${phase.name.lowercase().replaceFirstChar { it.uppercase() }}{" +
                "frames=$frames totalAvg=${avg(phaseTotalNs, frames)}ms/max${ms(phaseTotalMaxNs.get(i))} " +
                "cmdAvg=${avg(phaseCommandNs, frames)}ms/max${ms(phaseCommandMaxNs.get(i))} " +
                "unkAvg=${avg(phaseUnknownNs, frames)}ms/max${ms(phaseUnknownMaxNs.get(i))} " +
                "gpuAvg=${if (gpuFrames > 0L) ms(phaseGpuNs.get(i) / gpuFrames) else "NA"}ms/" +
                "max${if (gpuFrames > 0L) ms(phaseGpuMaxNs.get(i)) else "NA"} " +
                "events=${if (eventParts.isEmpty()) "-" else eventParts.joinToString(",")}}"
        }
    }

    private fun windowMetricsSummary(): String {
        val frames = metricsFrames.get()
        if (frames <= 0L) return "window=-"
        fun avg(value: AtomicLong): String = ms(value.get() / frames)
        val gpuFrames = metricsGpuFrames.get()
        val gpuAvg = if (gpuFrames > 0L) ms(metricsGpuNs.get() / gpuFrames) else "NA"
        val gpuMax = if (gpuFrames > 0L) ms(metricsGpuMaxNs.get()) else "NA"
        return "windowFrames=$frames totalAvg=${avg(metricsTotalNs)}ms totalMax=${ms(metricsMaxTotalNs.get())}ms " +
            "layoutAvg=${avg(metricsLayoutNs)}ms/max${ms(metricsLayoutMaxNs.get())} " +
            "drawAvg=${avg(metricsDrawNs)}ms/max${ms(metricsDrawMaxNs.get())} " +
            "commandAvg=${avg(metricsCommandNs)}ms/max${ms(metricsCommandMaxNs.get())} " +
            "syncAvg=${avg(metricsSyncNs)}ms/max${ms(metricsSyncMaxNs.get())} " +
            "swapAvg=${avg(metricsSwapNs)}ms/max${ms(metricsSwapMaxNs.get())} " +
            "unknownAvg=${avg(metricsUnknownNs)}ms/max${ms(metricsUnknownMaxNs.get())} " +
            "gpuAvg=${gpuAvg}ms/max${gpuMax} " +
            "overBudget=${totalOverBudgetFrames.get()} cmdHeavy=${commandHeavyFrames.get()} " +
            "drawHeavy=${drawHeavyFrames.get()} unknownHeavy=${unknownHeavyFrames.get()} gpuHeavy=${gpuHeavyFrames.get()} " +
            "listenerDrops=${metricsDroppedReports.get()}"
    }

    private fun suspectSummary(): String {
        val command = commandHeavyFrames.get()
        val draw = drawHeavyFrames.get()
        val unknown = unknownHeavyFrames.get()
        val gpu = gpuHeavyFrames.get()
        return when {
            gpu >= command && gpu >= draw && gpu >= unknown && gpu > 0L -> "gpu"
            command >= draw && command >= unknown && command > 0L -> "command_issue"
            unknown >= command && unknown >= draw && unknown > 0L -> "unknown_delay"
            draw > 0L -> "draw"
            totalOverBudgetFrames.get() > 0L -> "mixed"
            else -> "none"
        }
    }

    private fun markerSummary(clear: Boolean): String = synchronized(markerLock) {
        if (recentMarkers.isEmpty()) return@synchronized "-"
        val value = recentMarkers.joinToString("|")
        if (clear) recentMarkers.clear()
        value
    }

    private fun percentile95(): Long {
        if (frameSampleCount <= 0) return 0L
        val copy = frameSamples.copyOf(frameSampleCount)
        copy.sort()
        val index = ((copy.size - 1) * 0.95).toInt().coerceIn(0, copy.lastIndex)
        return copy[index]
    }

    private fun recordSlowFrame(frame: SlowFrame) = synchronized(slowFrameLock) {
        if (slowFrames.size < 8) {
            slowFrames += frame
            return@synchronized
        }
        var smallestIndex = 0
        var smallestTotal = slowFrames[0].total
        for (index in 1 until slowFrames.size) {
            val total = slowFrames[index].total
            if (total < smallestTotal) {
                smallestTotal = total
                smallestIndex = index
            }
        }
        if (frame.total > smallestTotal) slowFrames[smallestIndex] = frame
    }

    private fun slowFrameSummary(): String = synchronized(slowFrameLock) {
        if (slowFrames.isEmpty()) return@synchronized "-"
        slowFrames
            .sortedByDescending { it.total }
            .take(6)
            .joinToString(";") { frame ->
                buildString {
                    append('@').append(ms(frame.atNs)).append("ms[")
                        .append(frame.phase.name.lowercase()).append(']')
                    append(" total=").append(ms(frame.total))
                    append(" layout=").append(ms(frame.layout))
                    append(" draw=").append(ms(frame.draw))
                    append(" cmd=").append(ms(frame.command))
                    append(" sync=").append(ms(frame.sync))
                    append(" swap=").append(ms(frame.swap))
                    append(" unk=").append(ms(frame.unknown))
                    append(" gpu=").append(if (frame.gpu >= 0L) ms(frame.gpu) else "NA")
                }
            }
    }

    private fun reset() {
        worstFrame.set(null)
        TransitionPerfStage.values().indices.forEach { i ->
            stageTotalNs.set(i, 0L)
            stageCount.set(i, 0L)
            stageMaxNs.set(i, 0L)
        }
        TransitionPerfEvent.values().indices.forEach { i -> eventCount.set(i, 0L) }
        phaseEventCount.setAll(0L)
        phaseFrames.setAll(0L)
        phaseTotalNs.setAll(0L)
        phaseTotalMaxNs.setAll(0L)
        phaseCommandNs.setAll(0L)
        phaseCommandMaxNs.setAll(0L)
        phaseUnknownNs.setAll(0L)
        phaseUnknownMaxNs.setAll(0L)
        phaseGpuNs.setAll(0L)
        phaseGpuMaxNs.setAll(0L)
        phaseGpuFrames.setAll(0L)
        metricsFrames.set(0L)
        metricsTotalNs.set(0L)
        metricsLayoutNs.set(0L)
        metricsDrawNs.set(0L)
        metricsCommandNs.set(0L)
        metricsSyncNs.set(0L)
        metricsSwapNs.set(0L)
        metricsUnknownNs.set(0L)
        metricsGpuNs.set(0L)
        metricsMaxTotalNs.set(0L)
        metricsLayoutMaxNs.set(0L)
        metricsDrawMaxNs.set(0L)
        metricsCommandMaxNs.set(0L)
        metricsSyncMaxNs.set(0L)
        metricsSwapMaxNs.set(0L)
        metricsUnknownMaxNs.set(0L)
        metricsGpuMaxNs.set(0L)
        metricsGpuFrames.set(0L)
        metricsDroppedReports.set(0L)
        totalOverBudgetFrames.set(0L)
        commandHeavyFrames.set(0L)
        drawHeavyFrames.set(0L)
        unknownHeavyFrames.set(0L)
        gpuHeavyFrames.set(0L)
        lastFrameNs = 0L
        choreographerFrames = 0L
        jankFrames = 0L
        estimatedDroppedFrames = 0L
        maxFrameDeltaNs = 0L
        frameSampleCount = 0
        memoryStart = null
        resourceStart = "-"
        memoryAnimateStart = null
        memoryEndpointStart = null
        resourceAnimateStart = "-"
        resourceEndpointStart = "-"
        animateStartNs = Long.MAX_VALUE
        endpointStartNs = Long.MAX_VALUE
        synchronized(slowFrameLock) { slowFrames.clear() }
        synchronized(markerLock) { recentMarkers.clear() }
    }

    private fun updateMax(target: AtomicLong, candidate: Long) {
        while (true) {
            val previous = target.get()
            if (candidate <= previous) return
            if (target.compareAndSet(previous, candidate)) return
        }
    }

    private fun updateMax(target: AtomicLongArray, index: Int, candidate: Long) {
        while (true) {
            val previous = target.get(index)
            if (candidate <= previous) return
            if (target.compareAndSet(index, previous, candidate)) return
        }
    }

    private fun AtomicLongArray.setAll(value: Long) {
        for (index in 0 until length()) set(index, value)
    }

    private fun phaseForTimestamp(timestampNs: Long): TransitionPerfPhase = when {
        timestampNs >= endpointStartNs -> TransitionPerfPhase.ENDPOINT
        timestampNs >= animateStartNs -> TransitionPerfPhase.ANIMATE
        else -> TransitionPerfPhase.PREPARE
    }

    private fun ms(ns: Long): String = "%.2f".format(ns / 1_000_000.0)

    private fun memorySnapshot(): MemorySnapshot {
        val runtime = Runtime.getRuntime()
        val javaKb = (runtime.totalMemory() - runtime.freeMemory()) / 1024L
        val nativeKb = Debug.getNativeHeapAllocatedSize() / 1024L
        return MemorySnapshot(
            javaKb = javaKb,
            nativeKb = nativeKb,
            gcCount = runtimeStatLong("art.gc.gc-count"),
            blockingGcCount = runtimeStatLong("art.gc.blocking-gc-count"),
            gcTimeMs = runtimeStatLong("art.gc.gc-time"),
            bytesAllocated = runtimeStatLong("art.gc.bytes-allocated"),
            bytesFreed = runtimeStatLong("art.gc.bytes-freed"),
        )
    }

    private fun runtimeStatLong(key: String): Long {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return -1L
        return runCatching { Debug.getRuntimeStat(key)?.toLongOrNull() ?: -1L }.getOrDefault(-1L)
    }

    private fun memorySnapshotSummary(snapshot: MemorySnapshot?): String {
        snapshot ?: return "-"
        return "java=${snapshot.javaKb}KB native=${snapshot.nativeKb}KB gc=${snapshot.gcCount} " +
            "blockingGc=${snapshot.blockingGcCount} gcTime=${snapshot.gcTimeMs}ms " +
            "alloc=${snapshot.bytesAllocated} freed=${snapshot.bytesFreed}"
    }

    private fun memoryDeltaSummary(start: MemorySnapshot?, end: MemorySnapshot): String {
        start ?: return "end(${memorySnapshotSummary(end)})"
        fun delta(before: Long, after: Long): String =
            if (before >= 0L && after >= 0L) (after - before).toString() else "NA"
        return "start(${memorySnapshotSummary(start)}) end(${memorySnapshotSummary(end)}) " +
            "delta(java=${end.javaKb - start.javaKb}KB native=${end.nativeKb - start.nativeKb}KB " +
            "gc=${delta(start.gcCount, end.gcCount)} blockingGc=${delta(start.blockingGcCount, end.blockingGcCount)} " +
            "gcTime=${delta(start.gcTimeMs, end.gcTimeMs)}ms alloc=${delta(start.bytesAllocated, end.bytesAllocated)} " +
            "freed=${delta(start.bytesFreed, end.bytesFreed)})"
    }

    private fun phaseMemorySummary(end: MemorySnapshot): String {
        val animate = memoryAnimateStart
        val endpoint = memoryEndpointStart
        val prepareEnd = animate ?: endpoint ?: end
        val animateEnd = endpoint ?: end
        return buildString {
            append("phaseMemory{")
            append("prepare=").append(memoryDeltaCompact(memoryStart, prepareEnd))
            append(" animate=").append(memoryDeltaCompact(animate, animateEnd))
            append(" endpoint=").append(memoryDeltaCompact(endpoint, end))
            append(" resourcesAnimate={").append(resourceAnimateStart).append('}')
            append(" resourcesEndpoint={").append(resourceEndpointStart).append('}')
            append('}')
        }
    }

    private fun memoryDeltaCompact(start: MemorySnapshot?, end: MemorySnapshot): String {
        if (start == null) return "-"
        fun delta(before: Long, after: Long): String =
            if (before >= 0L && after >= 0L) (after - before).toString() else "NA"
        return "java=${end.javaKb - start.javaKb}KB/native=${end.nativeKb - start.nativeKb}KB/" +
            "gc=${delta(start.gcCount, end.gcCount)}/alloc=${delta(start.bytesAllocated, end.bytesAllocated)}"
    }

    private tailrec fun Context.findActivity(): Activity? = when (this) {
        is Activity -> this
        is ContextWrapper -> baseContext.findActivity()
        else -> null
    }
}
