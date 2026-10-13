package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkTransitionState
import com.rawsmusic.core.ui.widget.bitmaps.PlayerArtworkDirection
import kotlin.math.abs

/** Shared arbitration only; the existing dismiss host continues to own return motion. */
internal class ImmersiveVerticalGestureGate {
    var enabled = false
    var action = ImmersiveVerticalAction.None
    var artworkBounds = Rect.Zero
    var stackOrigin = Offset.Zero
    val dismissAllowed: Boolean get() = !enabled || action == ImmersiveVerticalAction.Dismiss
}

internal val LocalImmersiveVerticalGestureGate = staticCompositionLocalOf<ImmersiveVerticalGestureGate?> { null }

internal data class ImmersiveVerticalPresentation(
    val source: AudioFile,
    val sourceCover: String?,
    val target: AudioFile,
    val direction: PlayerArtworkDirection,
)

internal class ImmersiveVerticalTrackMotion(private val artwork: PlaybackArtworkTransitionState) {
    var presentation by mutableStateOf<ImmersiveVerticalPresentation?>(null)
    var committed by mutableStateOf(false)
    fun progress(): Float {
        val value = presentation ?: return 0f
        // Holder promotion resets ratio to zero before metadata publication. Retain the incoming
        // page at its endpoint until the transport identity catches up, instead of flashing A.
        return if (committed && artwork.foregroundCurrentKey() == value.target.coverKey) 1f
        else artwork.ratio.coerceIn(0f, 1f)
    }
    fun translation(height: Float, incoming: Boolean): Float {
        val value = presentation ?: return 0f
        val sign = if (value.direction == PlayerArtworkDirection.Next) -1f else 1f
        return sign * (progress() - if (incoming) 1f else 0f) * height
    }
}

internal fun Modifier.immersiveVerticalTrackGesture(
    gate: ImmersiveVerticalGestureGate,
    motion: ImmersiveVerticalTrackMotion,
    artwork: PlaybackArtworkTransitionState,
    eligible: () -> Boolean,
    currentSong: () -> AudioFile?,
    coverPath: () -> String?,
    queue: () -> List<AudioFile>,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): Modifier = pointerInput(gate, motion, artwork) {
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        gate.action = ImmersiveVerticalAction.None
        if (!eligible() || motion.presentation != null || artwork.isSettling) return@awaitEachGesture
        val startedOnArtwork = gate.artworkBounds.contains(down.position + gate.stackOrigin)
        val previousKey = artwork.gestureTargetKey(PlayerArtworkDirection.Previous)
        val nextKey = artwork.gestureTargetKey(PlayerArtworkDirection.Next)
        val gestureQueue = queue()
        val velocity = VelocityTracker().apply { addPosition(down.uptimeMillis, down.position) }
        var action = ImmersiveVerticalAction.None
        var started = false
        var released = false
        val height = size.height.toFloat().coerceAtLeast(1f)
        try {
            while (true) {
                // Classify vertical motion before clickable/lyric descendants see it. Horizontal
                // motion is rejected below, leaving timeline seeking and page navigation intact.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (event.changes.count { it.pressed } > 1 || !eligible()) break
                val delta = change.position - down.position
                // Consumed DOWN is a tap claim, not a drag claim. Never let a title/button press
                // remove that rectangle from the full-screen vertical gesture surface.
                if (!started && immersiveVerticalChildOwnsMotion(change.isConsumed, delta.x, delta.y)) break
                velocity.addPosition(change.uptimeMillis, change.position)
                if (!started) {
                    if (!change.pressed) break
                    if (abs(delta.x) > viewConfiguration.touchSlop && abs(delta.x) >= abs(delta.y)) break
                    if (abs(delta.y) <= viewConfiguration.touchSlop) continue
                    action = immersiveVerticalAction(true, true, false, startedOnArtwork, delta.y)
                    gate.action = action
                    if (action == ImmersiveVerticalAction.Dismiss) break
                    val direction = if (action == ImmersiveVerticalAction.Next) PlayerArtworkDirection.Next
                        else PlayerArtworkDirection.Previous
                    val source = currentSong() ?: break
                    val key = if (direction == PlayerArtworkDirection.Next) nextKey else previousKey
                    val target = gestureQueue.firstOrNull { it.coverKey == key } ?: break
                    if (target.coverKey == source.coverKey || !artwork.beginGesture(direction, key)) break
                    motion.committed = false
                    motion.presentation = ImmersiveVerticalPresentation(source, coverPath(), target, direction)
                    started = true
                }
                val signedProgress = immersiveVerticalSignedProgress(delta.y, height)
                artwork.updateContinuousGesture(signedProgress, previousKey, nextKey)
                val presentation = motion.presentation ?: break
                if (presentation.direction != artwork.direction) {
                    val key = if (artwork.direction == PlayerArtworkDirection.Next) nextKey else previousKey
                    val target = gestureQueue.firstOrNull { it.coverKey == key } ?: break
                    motion.presentation = presentation.copy(target = target, direction = artwork.direction)
                }
                val progress = artwork.ratio.coerceIn(0f, 1f)
                change.consume()
                if (!change.pressed) {
                    released = true
                    val direction = motion.presentation?.direction ?: break
                    motion.committed = artwork.endGesture(
                        progress = progress,
                        velocityPxPerSecond = if (progress > 0f) velocity.calculateVelocity().y else 0f,
                        artworkWidthPx = height,
                        physicalEndpointReached = progress >= 1f,
                        gestureTravelProgress = progress,
                    ) {
                        motion.presentation?.let {
                            artwork.retainGestureCommitUntilBinding(it.source.coverKey, it.target.coverKey)
                        }
                        if (direction == PlayerArtworkDirection.Next) onNext() else onPrevious()
                    }
                    break
                }
            }
        } finally {
            if (started && !released) artwork.cancelGesture()
        }
    }
}
