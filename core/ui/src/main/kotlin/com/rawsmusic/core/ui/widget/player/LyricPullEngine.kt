package com.rawsmusic.core.ui.widget.player

import kotlin.math.abs

internal enum class LyricPullItemPhase { Initial, WaitingForPull, Pulling, Done }
internal data class LyricPullFrame(val offsetsPx: Map<Int, Float>, val running: Boolean,
                                  val stackOffsetPx: Float = 0f)

/** Each row owns a real spring; offsets are relative to the shared logical-layout compensation. */
internal class LyricPullEngine {
    private data class Item(val spring: LyricSpringState, var startMs: Long,
                            var phase: LyricPullItemPhase)
    private val items = mutableMapOf<Int, Item>()
    private val stack = LyricSpringState()
    private var lastFrameMs = Long.MIN_VALUE
    private var lastAnchorIndex = -1
    private var lastVisibleIndices: List<Int> = emptyList()

    fun reset(): LyricPullFrame {
        items.clear(); stack.value = 0f; stack.velocity = 0f; lastFrameMs = Long.MIN_VALUE
        lastAnchorIndex = -1
        lastVisibleIndices = emptyList()
        return LyricPullFrame(emptyMap(), false)
    }

    fun beginForwardPull(anchorIndex: Int, pullDistancePx: Float, viewportHeightPx: Float,
                         visibleIndices: List<Int>, frameTimeMs: Long): Boolean =
        beginTransition(anchorIndex, pullDistancePx, viewportHeightPx, visibleIndices, frameTimeMs, true)

    fun beginTransition(anchorIndex: Int, deltaPx: Float, viewportHeightPx: Float,
                        visibleIndices: List<Int>, frameTimeMs: Long, stagger: Boolean): Boolean {
        if (anchorIndex < 0 || !deltaPx.isFinite() || deltaPx == 0f || viewportHeightPx <= 0f) return false
        advance(frameTimeMs, visibleIndices)
        val targetTimeMs = maxOf(frameTimeMs, lastFrameMs)
        val newSentence = anchorIndex != lastAnchorIndex
        val previousStack = stack.value
        val previousVelocity = stack.velocity
        stack.value += deltaPx
        val visible = visibleIndices.distinct().sorted()
        // The iOS enumeration is current, visible previous rows (nearest first), then following
        // rows. Its two immediate slots do not mean current plus the first following sentence.
        val animationOrder = visible.filter { it == anchorIndex } +
            visible.filter { it < anchorIndex }.asReversed() + visible.filter { it > anchorIndex }
        for (index in visibleIndices.distinct()) {
            val item = items.getOrPut(index) {
                Item(LyricSpringState(previousStack, previousVelocity), frameTimeMs, LyricPullItemPhase.Done)
            }
            val alreadyMoving = item.phase == LyricPullItemPhase.Pulling &&
                (abs(item.spring.value) > 1f || abs(item.spring.velocity) > 8f)
            item.spring.value += deltaPx
            // Correcting this sentence's measured layout must not erase its 50ms wave.
            if (!newSentence && item.phase != LyricPullItemPhase.Done) continue
            // A quick next line bends an existing trajectory; it must not freeze it for another delay.
            val rank = animationOrder.indexOf(index).coerceAtLeast(0)
            val delay = if (stagger && !alreadyMoving) LyricAmMotionSpec.delayMs(rank) else 0L
            val proposedStart = targetTimeMs + delay
            item.startMs = if (newSentence && item.phase == LyricPullItemPhase.WaitingForPull)
                minOf(item.startMs, proposedStart) else proposedStart
            item.phase = if (item.startMs > targetTimeMs) LyricPullItemPhase.WaitingForPull else LyricPullItemPhase.Pulling
        }
        lastFrameMs = targetTimeMs
        lastAnchorIndex = anchorIndex
        return true
    }

    fun advance(frameTimeMs: Long, visibleIndices: List<Int>): LyricPullFrame {
        val now = if (lastFrameMs == Long.MIN_VALUE) frameTimeMs else maxOf(frameTimeMs, lastFrameMs)
        val previousFrame = if (lastFrameMs == Long.MIN_VALUE) now else lastFrameMs
        val dt = (now - previousFrame).coerceIn(0L, 32L) / 1000f
        stack.advance(dt)
        val offsets = linkedMapOf<Int, Float>()
        var previousItem: Item? = null
        for (index in visibleIndices) {
            val item = items.getOrPut(index) {
                // New bottom-edge rows join the predecessor's displayed trajectory without a jump.
                val inherited = previousItem?.spring ?: stack
                Item(LyricSpringState(inherited.value, inherited.velocity), now,
                    if (inherited.running) LyricPullItemPhase.Pulling else LyricPullItemPhase.Done)
            }
            if (now >= item.startMs && item.phase != LyricPullItemPhase.Done) {
                item.phase = LyricPullItemPhase.Pulling
                val activeDt = (now - maxOf(previousFrame, item.startMs)).coerceIn(0L, 32L) / 1000f
                item.spring.advance(activeDt)
                if (!item.spring.running) item.phase = LyricPullItemPhase.Done
            }
            offsets[index] = item.spring.value - stack.value
            previousItem = item
        }
        if (visibleIndices != lastVisibleIndices) {
            items.keys.retainAll(visibleIndices.toSet())
            lastVisibleIndices = visibleIndices.toList()
        }
        lastFrameMs = now
        val running = stack.running || items.values.any { it.spring.running }
        return LyricPullFrame(offsets, running, stack.value)
    }

    fun rebindJumpRows(anchorIndex: Int, visibleIndices: List<Int>, frameTimeMs: Long, stagger: Boolean) {
        advance(frameTimeMs, lastVisibleIndices)
        val now = maxOf(frameTimeMs, lastFrameMs)
        val order = visibleIndices.filter { it == anchorIndex } +
            visibleIndices.filter { it < anchorIndex }.asReversed() + visibleIndices.filter { it > anchorIndex }
        val retained = items.toMap()
        items.clear()
        for (index in visibleIndices) {
            retained[index]?.let { item -> items[index] = item }
            if (index in items) continue
            val delay = if (stagger) LyricAmMotionSpec.delayMs(order.indexOf(index).coerceAtLeast(0)) else 0L
            items[index] = Item(LyricSpringState(stack.value, if (delay == 0L) stack.velocity else 0f),
                now + delay, if (delay > 0L) LyricPullItemPhase.WaitingForPull else LyricPullItemPhase.Pulling)
        }
        lastVisibleIndices = visibleIndices.toList()
    }

    internal fun phaseOf(index: Int): LyricPullItemPhase = items[index]?.phase ?: LyricPullItemPhase.Initial
}
