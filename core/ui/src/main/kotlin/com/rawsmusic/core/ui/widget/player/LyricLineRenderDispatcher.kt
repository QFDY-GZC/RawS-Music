package com.rawsmusic.core.ui.widget.player

import androidx.compose.runtime.Stable
import java.util.IdentityHashMap

/**
 * One frame fan-out owner for one timed lyric line.
 *
 * The surface already has one [LyricDirectRenderClock]. Do not register every ordinary word's
 * highlight and lyric nodes directly on that clock: a 15-word line would otherwise wake roughly
 * thirty listeners on every display frame even though only the current highlight and the one/two
 * words intersecting the spatial band can change pixels. This dispatcher is the only clock
 * listener for the ordinary line and forwards frames only to those nodes whose visual state is
 * currently moving, plus one boundary frame when a node enters/leaves a moving state.
 *
 * Long-note/legacy owners may keep their existing direct-clock path; this dispatcher specifically
 * collapses the common ordinary karaoke lane without changing typography or layout ownership.
 */
@Stable
internal class LyricLineRenderDispatcher(
    private val clock: LyricDirectRenderClock,
    private val lineGeometry: LyricLineFloatUpGeometry,
) : LyricDirectFrameListener {

    private val frameTargets = LinkedHashSet<LyricLineFrameTarget>()
    private val highlightTargets = LinkedHashSet<LyricLineHighlightFrameTarget>()
    private val frameModes = IdentityHashMap<LyricLineFrameTarget, LyricFrameSamplingMode>()
    private val highlightPhases = IdentityHashMap<LyricLineHighlightFrameTarget, Int>()
    private var registeredWithClock = false

    fun currentPosition(): Float = clock.currentPosition()

    fun addFrameTarget(target: LyricLineFrameTarget) {
        if (!frameTargets.add(target)) {
            refreshFrameTarget(target)
            return
        }
        ensureClockRegistration()
        val position = currentPosition()
        val mode = lyricMode(target, position)
        frameModes[target] = mode
        target.onLyricLineFrame(position, mode)
    }

    fun removeFrameTarget(target: LyricLineFrameTarget) {
        frameTargets.remove(target)
        frameModes.remove(target)
        releaseClockIfIdle()
    }

    fun refreshFrameTarget(target: LyricLineFrameTarget) {
        if (target !in frameTargets) return
        val position = currentPosition()
        val mode = lyricMode(target, position)
        frameModes[target] = mode
        target.onLyricLineFrame(position, mode)
    }

    fun addHighlightTarget(target: LyricLineHighlightFrameTarget) {
        if (!highlightTargets.add(target)) {
            refreshHighlightTarget(target)
            return
        }
        ensureClockRegistration()
        val position = currentPosition()
        val phase = highlightPhase(target, position)
        highlightPhases[target] = phase
        target.onHighlightLineFrame(position)
    }

    fun removeHighlightTarget(target: LyricLineHighlightFrameTarget) {
        highlightTargets.remove(target)
        highlightPhases.remove(target)
        releaseClockIfIdle()
    }

    fun refreshHighlightTarget(target: LyricLineHighlightFrameTarget) {
        if (target !in highlightTargets) return
        val position = currentPosition()
        highlightPhases[target] = highlightPhase(target, position)
        target.onHighlightLineFrame(position)
    }

    override fun onLyricFrame(positionMs: Float) {
        // No collection is copied/allocated here. Normal lines are small, but unlike the old model
        // each node also no longer appears independently in the surface clock's listener set.
        for (target in frameTargets) {
            val next = lyricMode(target, positionMs)
            val previous = frameModes[target]
            if (next == LyricFrameSamplingMode.ANIMATED || next != previous) {
                frameModes[target] = next
                target.onLyricLineFrame(positionMs, next)
            }
        }

        for (target in highlightTargets) {
            val next = highlightPhase(target, positionMs)
            val previous = highlightPhases[target]
            if (next == HIGHLIGHT_PHASE_ACTIVE || next != previous) {
                highlightPhases[target] = next
                target.onHighlightLineFrame(positionMs)
            }
        }
    }

    private fun lyricMode(
        target: LyricLineFrameTarget,
        positionMs: Float,
    ): LyricFrameSamplingMode = lineGeometry.frameSamplingMode(
        slotIndex = target.segmentIndex,
        positionMs = positionMs,
        textScalePx = target.textScalePx,
    )

    private fun highlightPhase(target: LyricLineHighlightFrameTarget, positionMs: Float): Int = when {
        positionMs < target.highlightBeginMs.toFloat() -> HIGHLIGHT_PHASE_BEFORE
        positionMs >= target.highlightEndMs.toFloat() -> HIGHLIGHT_PHASE_AFTER
        else -> HIGHLIGHT_PHASE_ACTIVE
    }

    private fun ensureClockRegistration() {
        if (registeredWithClock) return
        registeredWithClock = true
        clock.addListener(this)
    }

    private fun releaseClockIfIdle() {
        if (!registeredWithClock || frameTargets.isNotEmpty() || highlightTargets.isNotEmpty()) return
        registeredWithClock = false
        clock.removeListener(this)
    }

    private companion object {
        const val HIGHLIGHT_PHASE_BEFORE = 0
        const val HIGHLIGHT_PHASE_ACTIVE = 1
        const val HIGHLIGHT_PHASE_AFTER = 2
    }
}

internal interface LyricLineFrameTarget {
    val segmentIndex: Int
    val textScalePx: Float
    fun onLyricLineFrame(positionMs: Float, mode: LyricFrameSamplingMode)
}

internal interface LyricLineHighlightFrameTarget {
    val highlightBeginMs: Long
    val highlightEndMs: Long
    fun onHighlightLineFrame(positionMs: Float)
}
