package com.rawsmusic.core.ui.widget.player

import android.view.Choreographer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.rawsmusic.core.ui.scene.LocalUiFrameAnimationActive

/** Listener owned by a draw node. No Compose Snapshot state participates in this callback. */
internal fun interface LyricDirectFrameListener {
    fun onLyricFrame(positionMs: Float)
}

/**
 * One Choreographer-driven render clock for the whole lyric surface.
 *
 * Player callbacks only reconcile the source anchor. While playback is moving, exactly one
 * Choreographer callback advances the render position for the surface and invalidates the small
 * set of attached draw nodes that currently intersect the spatial lift window. This deliberately
 * avoids both per-vsync Snapshot writes and one Choreographer callback per word/glyph.
 */
@Stable
internal class LyricDirectRenderClock : Choreographer.FrameCallback {
    private var sourcePositionMs: Float = 0f
    private var renderedPositionMs: Float = 0f
    private var durationMs: Long = 0L
    private var playing: Boolean = false
    private var initialized: Boolean = false
    private var lastFrameNs: Long = 0L
    private var playbackSpeed: Float = 1f

    private val listeners = LinkedHashSet<LyricDirectFrameListener>()
    private var choreographer: Choreographer? = null
    private var callbackPosted = false

    /**
     * Offline export (lyric video) timeline: the render position is exactly the supplied source
     * position for every frame and no Choreographer callback is ever posted. The exporter steps
     * positionMs by 1/fps itself, so wall-clock extrapolation would make frames non-deterministic.
     */
    var fixedTimeline: Boolean = false

    fun updateSource(positionMs: Long, isPlaying: Boolean, durationMs: Long) {
        val source = positionMs.coerceAtLeast(0L).toFloat()
        this.durationMs = durationMs
        if (fixedTimeline) {
            sourcePositionMs = source
            renderedPositionMs = source
            playing = false
            initialized = true
            lastFrameNs = 0L
            cancelFrameCallback()
            dispatchCurrentFrame()
            return
        }
        val rate = com.rawsmusic.core.common.utils.LyricPlaybackTimeline.speed
        if (rate != playbackSpeed) {
            playbackSpeed = rate
            sourcePositionMs = source
            renderedPositionMs = source
            lastFrameNs = 0L
            dispatchCurrentFrame()
        }

        if (!initialized) {
            sourcePositionMs = source
            renderedPositionMs = source
            playing = isPlaying
            initialized = true
            lastFrameNs = 0L
            dispatchCurrentFrame()
            ensureFrameCallback()
            return
        }

        if (!isPlaying) {
            sourcePositionMs = source
            renderedPositionMs = source
            playing = false
            lastFrameNs = 0L
            cancelFrameCallback()
            dispatchCurrentFrame()
            return
        }

        if (!playing) {
            sourcePositionMs = source
            renderedPositionMs = source
            playing = true
            lastFrameNs = 0L
            dispatchCurrentFrame()
            ensureFrameCallback()
            return
        }

        if (source != sourcePositionMs) {
            val previousSource = sourcePositionMs
            val update = LyricRenderClockPolicy.onSourceUpdate(
                renderedPositionMs = renderedPositionMs,
                previousSourcePositionMs = previousSource,
                sourcePositionMs = source,
            )
            renderedPositionMs = update.positionMs
            sourcePositionMs = source
            if (update.hardReanchored) {
                // Playback is already running. Resetting the frame timestamp here discards the
                // next display interval on every phase correction (especially visible at 2x/3x).
                dispatchCurrentFrame()
            }
        }
        ensureFrameCallback()
    }

    fun addListener(listener: LyricDirectFrameListener) {
        if (!listeners.add(listener)) return
        listener.onLyricFrame(currentPosition())
        ensureFrameCallback()
    }

    fun removeListener(listener: LyricDirectFrameListener) {
        listeners.remove(listener)
        if (listeners.isEmpty()) cancelFrameCallback()
    }

    override fun doFrame(frameTimeNanos: Long) {
        callbackPosted = false
        if (!playing || listeners.isEmpty()) return
        val framePosition = advanceTo(frameTimeNanos)
        // All listeners live on the main thread. draw invalidation does not synchronously detach a
        // node, so iterating the stable attached-listener set requires no per-frame copy/allocation.
        listeners.forEach { it.onLyricFrame(framePosition) }
        ensureFrameCallback()
    }

    fun currentPosition(): Float = clamp(renderedPositionMs)

    private fun advanceTo(frameTimeNanos: Long): Float {
        if (!initialized) return 0f
        if (!playing) return clamp(renderedPositionMs)

        if (lastFrameNs == 0L) {
            lastFrameNs = frameTimeNanos
            if (renderedPositionMs <= 0f && sourcePositionMs > 0f) {
                renderedPositionMs = sourcePositionMs
            }
            return clamp(renderedPositionMs)
        }

        val dt = (frameTimeNanos - lastFrameNs).coerceAtLeast(0L) / 1_000_000f
        lastFrameNs = frameTimeNanos
        renderedPositionMs = LyricRenderClockPolicy.advance(
            renderedPositionMs = renderedPositionMs,
            frameDeltaMs = dt,
            playbackSpeed = playbackSpeed,
        )
        return clamp(renderedPositionMs)
    }

    private fun dispatchCurrentFrame() {
        if (listeners.isEmpty()) return
        val current = currentPosition()
        listeners.forEach { it.onLyricFrame(current) }
    }

    private fun ensureFrameCallback() {
        if (fixedTimeline || !playing || listeners.isEmpty() || callbackPosted) return
        val scheduler = choreographer ?: Choreographer.getInstance().also { choreographer = it }
        callbackPosted = true
        scheduler.postFrameCallback(this)
    }

    private fun cancelFrameCallback() {
        if (callbackPosted) {
            choreographer?.removeFrameCallback(this)
            callbackPosted = false
        }
    }

    private fun clamp(value: Float): Float = if (durationMs > 0L) {
        value.coerceIn(0f, durationMs.toFloat())
    } else {
        value.coerceAtLeast(0f)
    }
}

/**
 * True only inside the offline lyric-video exporter. The surrounding composition is driven by a
 * manual frame clock, so the lyric render clock must follow the supplied position exactly.
 */
internal val LocalLyricExportTimeline = staticCompositionLocalOf { false }

@Composable
internal fun rememberLyricDirectRenderClock(
    positionMs: Long,
    isPlaying: Boolean,
    durationMs: Long,
): LyricDirectRenderClock {
    val frameAnimationActive = LocalUiFrameAnimationActive.current
    val fixedTimeline = LocalLyricExportTimeline.current
    val clock = remember { LyricDirectRenderClock() }
    SideEffect {
        clock.fixedTimeline = fixedTimeline
        clock.updateSource(
            positionMs = positionMs,
            isPlaying = isPlaying && frameAnimationActive,
            durationMs = durationMs,
        )
    }
    return clock
}

/**
 * Invalidates only the attached draw node from [LyricDirectRenderClock].
 *
 * This is intentionally separate from Compose state. Small surfaces such as the MiniPlayer can
 * sample [LyricDirectRenderClock.currentPosition] inside draw without publishing a 60/120 Hz
 * Snapshot value that would recompose the whole playback bar.
 */
private class LyricClockDrawInvalidationNode(
    var clock: LyricDirectRenderClock,
) : Modifier.Node(), DrawModifierNode, LyricDirectFrameListener {
    override val shouldAutoInvalidate: Boolean
        get() = false

    override fun onAttach() {
        super.onAttach()
        clock.addListener(this)
    }

    override fun onDetach() {
        clock.removeListener(this)
        super.onDetach()
    }

    override fun onLyricFrame(positionMs: Float) {
        if (isAttached) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        drawContent()
    }

    fun updateClock(nextClock: LyricDirectRenderClock) {
        if (clock === nextClock) return
        if (isAttached) clock.removeListener(this)
        clock = nextClock
        if (isAttached) clock.addListener(this)
        invalidateDraw()
    }
}

private data class LyricClockDrawInvalidationElement(
    val clock: LyricDirectRenderClock,
) : ModifierNodeElement<LyricClockDrawInvalidationNode>() {
    override fun create(): LyricClockDrawInvalidationNode =
        LyricClockDrawInvalidationNode(clock)

    override fun update(node: LyricClockDrawInvalidationNode) {
        node.updateClock(clock)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "lyricClockDrawInvalidation"
    }
}

internal fun Modifier.lyricClockDrawInvalidation(
    clock: LyricDirectRenderClock?,
): Modifier = if (clock == null) this else then(LyricClockDrawInvalidationElement(clock))
