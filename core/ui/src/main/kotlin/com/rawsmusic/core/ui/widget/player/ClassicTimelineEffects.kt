package com.rawsmusic.core.ui.widget.player

import android.animation.ValueAnimator
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.Constraints
import com.rawsmusic.core.common.utils.AudioUtils
import kotlin.math.abs
import kotlinx.coroutines.channels.Channel

private class ClassicTimelineAnimation(initial: Float) {
    val progress = ClassicProgressSpring(initial)
    val press = ClassicProgressSpring(0f)
    val translation = ClassicProgressSpring(0f)
    val whiteHead = ClassicProgressSpring(0f)
    val cone = ClassicConeAnimation()
    val fling = ClassicProgressFling()
    val hold = ClassicSeekHold()
    var lastHeadLength = 0f
    var seekOwned = false
    val interaction = ClassicVisualInteraction()
    val clock = ClassicProgressFrameClock()
    val wake = Channel<Unit>(Channel.CONFLATED)
    var reportedFraction = initial
    var reportedMs = 0L
    var durationMs = 0L
    var playing = false
    var lightEnabled = false
    var dragFraction = initial
    var rubberTarget = 0f
    fun signal() { wake.trySend(Unit) }
    val running get() = progress.running || press.running || translation.running ||
        whiteHead.running || cone.running || fling.running
}

internal data class ClassicTimelineFrame(
    val progress: Float,
    val headLength: Float = 0f,
    val headAlpha: Float = 0f,
    val press: Float = 0f,
    val translation: Float = 0f,
    val cone: ClassicConeFrame = ClassicConeFrame(0.1f, 0f, 0f, 0f, 0f, 0f),
    val displayedMs: Long = 0L,
    val phaseFlags: Int = 0,
    val settled: Boolean = true,
)

@Composable
internal fun ClassicTimelineEffects(
    currentPositionMs: Long,
    totalDurationMs: Long,
    isPlaying: Boolean,
    trackKey: String,
    artworkKey: String?,
    trackColor: Color,
    fallbackColor: Color,
    timeColor: Color,
    onSeekStart: () -> Unit,
    onSeekStop: (Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val offsetY = with(density) { 4.dp.toPx() }
    val touchHalfHeight = with(density) { 12.dp.toPx() }
    val rubberLimit = with(density) { 28.dp.toPx() }
    val powerManager = remember(context) { context.getSystemService(Context.POWER_SERVICE) as PowerManager }
    var powerSaving by remember { mutableStateOf(powerManager.isPowerSaveMode) }
    val animationsEnabled = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled()
    val lightEnabled = !powerSaving && animationsEnabled
    val albumColor = rememberClassicLightColor(artworkKey, fallbackColor)
    val realFraction = if (totalDurationMs > 0L)
        (currentPositionMs.toDouble() / totalDurationMs).toFloat().coerceIn(0f, 1f) else 0f
    val animation = remember(trackKey) { ClassicTimelineAnimation(realFraction) }
    val frameState = remember(trackKey) { mutableStateOf(ClassicTimelineFrame(realFraction, displayedMs = currentPositionMs)) }
    var widthPx by remember { mutableIntStateOf(1) }
    val latestStart by rememberUpdatedState(onSeekStart)
    val latestStop by rememberUpdatedState(onSeekStop)

    fun finishSeek(fraction: Float) {
        // The head returns to playback/paused mode at release, independent of the backend's
        // seek acknowledgement or lifecycle. Never leave a press flag inside a Compose closure.
        animation.interaction.finish()
        animation.rubberTarget = 0f
        animation.press.target = 0f
        animation.translation.target = 0f
        animation.cone.transition(if (animation.lightEnabled)
            classicConeMode(animation.playing, false) else ClassicConeMode.Off)
        animation.signal()
        if (!animation.seekOwned) return
        animation.seekOwned = false
        animation.hold.commit(fraction, SystemClock.uptimeMillis())
        animation.signal()
        latestStop(fraction.coerceIn(0f, 1f))
    }

    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                powerSaving = powerManager.isPowerSaveMode
            }
        }
        val filter = IntentFilter(PowerManager.ACTION_POWER_SAVE_MODE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else context.registerReceiver(receiver, filter)
        onDispose { context.unregisterReceiver(receiver) }
    }
    DisposableEffect(animation) {
        onDispose {
            animation.fling.cancel()
            animation.interaction.finish()
            // A removed/changed track must release the parent's seek ownership exactly once.
            if (animation.seekOwned) {
                animation.seekOwned = false
                latestStop(animation.progress.value.coerceIn(0f, 1f))
            }
        }
    }

    // Inputs wake one actor; MOVE never cancels a pending vsync or resets spring velocity.
    SideEffect {
        if (animation.reportedFraction != realFraction || animation.reportedMs != currentPositionMs ||
            animation.durationMs != totalDurationMs || animation.playing != isPlaying ||
            animation.lightEnabled != lightEnabled) {
            animation.reportedFraction = realFraction
            animation.reportedMs = currentPositionMs
            animation.durationMs = totalDurationMs
            animation.playing = isPlaying
            animation.lightEnabled = lightEnabled
            animation.signal()
        }
    }
    LaunchedEffect(animation) {
        animation.signal()
        while (true) {
            animation.wake.receive()
            animation.clock.reset()
            do {
                val nowNs = withFrameNanos { it }
                // Drop only obsolete input notifications, never a frame. The latest target is
                // already stored synchronously by the pointer handler before this vsync.
                while (animation.wake.tryReceive().isSuccess) { }
                val dt = animation.clock.advance(nowNs)
                animation.press.target = if (animation.interaction.pressed) 1f else 0f
                animation.translation.target = animation.rubberTarget
                animation.cone.transition(if (animation.lightEnabled)
                    classicConeMode(animation.playing, animation.interaction.dragging) else ClassicConeMode.Off)
                if (animation.interaction.dragging) animation.progress.target = animation.dragFraction
                else if (!animation.interaction.flinging) {
                    val reported = animation.hold.display(animation.reportedFraction, SystemClock.uptimeMillis())
                    if (animation.hold.untilMs == 0L) animation.progress.snap(reported)
                    else animation.progress.target = reported
                }
                if (animation.interaction.flinging) {
                    animation.fling.advance(dt)
                    animation.progress.snap(animation.fling.fraction)
                    if (!animation.fling.running) {
                        finishSeek(animation.fling.fraction)
                        animation.interaction.flinging = false
                    }
                } else animation.progress.advance(dt, 0.15f)
                val desiredHead = if (animation.lightEnabled)
                    classicHeadLength(animation.progress.value, animation.playing, animation.interaction.dragging) else 0f
                if (desiredHead > 0f) animation.lastHeadLength = desiredHead
                animation.whiteHead.target = if (desiredHead > 0f) 1f else 0f
                animation.whiteHead.advance(dt, 0.5f)
                animation.press.advance(dt, 0.5f)
                animation.translation.advance(dt, if (animation.interaction.pressed) 0.15f else 0.42f,
                    bounce = if (animation.interaction.pressed) 0.15f else 0.65f)
                animation.cone.advance(dt)
                val seekDisplay = animation.interaction.dragging || animation.interaction.flinging || animation.hold.untilMs > SystemClock.uptimeMillis()
                frameState.value = ClassicTimelineFrame(animation.progress.value.coerceIn(0f, 1f),
                    animation.lastHeadLength, animation.whiteHead.value, animation.press.value,
                    animation.translation.value, animation.cone.frame(),
                    displayedMs = if (seekDisplay) (animation.progress.value.coerceIn(0f, 1f).toDouble() *
                        animation.durationMs).toLong() else animation.reportedMs,
                    phaseFlags = (if (animation.playing) 1 else 0) or
                        (if (animation.interaction.pressed) 2 else 0) or
                        (if (animation.interaction.dragging) 4 else 0) or
                        (if (animation.interaction.flinging) 8 else 0) or
                        (if (animation.lightEnabled) 16 else 0),
                    settled = !animation.running)
            } while (animation.running || animation.hold.untilMs > SystemClock.uptimeMillis())
        }
    }

    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(28.dp)
            .onSizeChanged { widthPx = it.width.coerceAtLeast(1) }
            .pointerInput(animation, totalDurationMs, widthPx, rtl, lightEnabled, animationsEnabled) {
                fun fractionAt(x: Float): Float {
                    val physical = (x / widthPx).coerceIn(0f, 1f)
                    return if (rtl) 1f - physical else physical
                }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                    if (totalDurationMs <= 0L || widthPx <= 1) return@awaitEachGesture
                    if (animation.fling.running) {
                        animation.fling.cancel()
                        animation.interaction.flinging = false
                        finishSeek(animation.fling.fraction)
                    }
                    val allowed = isPlayerTimelineSeekDownAllowed(down.position.y,
                        size.height.toFloat(), offsetY, touchHalfHeight)
                    animation.interaction.pressed = allowed
                    animation.signal()
                    val press = ClassicProgressPress(allowed, down.uptimeMillis, viewConfiguration.longPressTimeoutMillis)
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    var axis = PlayerTimelineGestureAxis.Undecided
                    var started = false
                    var holdClaimed = false
                    var released = false
                    var lastFraction = frameState.value.progress
                    var lastPosition = down.position
                    fun beginSeek() {
                        if (started) return
                        started = true
                        lastFraction = frameState.value.progress
                        animation.dragFraction = lastFraction
                        animation.hold.clear()
                        animation.seekOwned = true
                        animation.interaction.drag()
                        latestStart()
                        animation.signal()
                    }
                    try {
                        while (true) {
                            val event = if (!started && allowed && axis == PlayerTimelineGestureAxis.Undecided) {
                                withTimeoutOrNull((press.deadlineMs - SystemClock.uptimeMillis()).coerceAtLeast(1L)) {
                                    awaitPointerEvent(PointerEventPass.Main)
                                }
                            } else awaitPointerEvent(PointerEventPass.Main)
                            if (event == null) {
                                if (press.shouldClaim(SystemClock.uptimeMillis(), axis, false)) {
                                    holdClaimed = true
                                    axis = PlayerTimelineGestureAxis.HorizontalSeek
                                    beginSeek()
                                }
                                continue
                            }
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            tracker.addPosition(change.uptimeMillis, change.position)
                            lastPosition = change.position
                            if (axis == PlayerTimelineGestureAxis.Undecided && change.isConsumed) {
                                axis = PlayerTimelineGestureAxis.VerticalScene
                                animation.interaction.pressed = false
                            }
                            if (axis == PlayerTimelineGestureAxis.Undecided) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                axis = if (allowed) resolvePlayerTimelineGestureAxis(dx, dy, viewConfiguration.touchSlop)
                                    else if (abs(dy) > viewConfiguration.touchSlop) PlayerTimelineGestureAxis.VerticalScene
                                    else PlayerTimelineGestureAxis.Undecided
                                if (axis == PlayerTimelineGestureAxis.HorizontalSeek) {
                                    beginSeek()
                                } else if (axis == PlayerTimelineGestureAxis.VerticalScene) animation.interaction.pressed = false
                            }
                            if (axis == PlayerTimelineGestureAxis.HorizontalSeek) {
                                if (!holdClaimed || change.position != down.position)
                                    lastFraction = fractionAt(change.position.x)
                                animation.dragFraction = lastFraction
                                val dx = change.position.x - down.position.x
                                animation.rubberTarget = if (lightEnabled) {
                                    val amount = abs(dx) * 0.05f
                                    kotlin.math.sign(dx) * rubberLimit * (1f - 1f / (1f + amount / rubberLimit))
                                } else 0f
                                change.consume()
                            }
                            animation.signal()
                            if (!change.pressed) { released = true; break }
                        }
                    } finally {
                        animation.interaction.release(false)
                        animation.rubberTarget = 0f
                        if (started) {
                            val velocity = if (released) tracker.calculateVelocity().x * if (rtl) -1f else 1f else 0f
                            animation.interaction.flinging = released && animationsEnabled &&
                                animation.fling.start(lastFraction, velocity, widthPx.toFloat())
                            if (!animation.interaction.flinging) finishSeek(lastFraction)
                        } else if (released && allowed && axis == PlayerTimelineGestureAxis.Undecided) {
                            animation.hold.clear()
                            animation.seekOwned = true
                            latestStart()
                            val tapped = fractionAt(lastPosition.x)
                            animation.progress.target = tapped
                            finishSeek(tapped)
                        }
                        animation.signal()
                    }
                }
            }) {
            // A single native View matches COUI's direct Canvas pipeline. The complete paint
            // envelope remains declared, but there is no separate Compose SCREEN framebuffer.
            AndroidView(
                factory = { ClassicNativeProgressView(it) },
                modifier = Modifier.layout { measurable, constraints ->
                    val geometry = classicGlowGeometry(constraints.maxWidth.toFloat(),
                        constraints.maxHeight.toFloat(), offsetY)
                    val paintWidth = kotlin.math.ceil(geometry.width).toInt()
                    val paintHeight = kotlin.math.ceil(geometry.height).toInt()
                    val placeable = measurable.measure(Constraints.fixed(paintWidth, paintHeight))
                    layout(constraints.maxWidth, constraints.maxHeight) {
                        placeable.place((constraints.maxWidth - paintWidth) / 2,
                            (constraints.maxHeight - paintHeight) / 2)
                    }
                },
                onReset = null,
                onRelease = { it.release() },
                update = { view ->
                    view.bind(ClassicNativeRenderPacket(frameState.value, trackColor.toArgb(),
                        albumColor.toArgb(), density.density, offsetY, rtl, lightEnabled))
                },
            )
        }
        Spacer(Modifier.height(3.dp))
        ClassicTimelineTimeLabels(frameState, totalDurationMs, timeColor)
    }
}

@Composable
private fun ClassicTimelineTimeLabels(frame: State<ClassicTimelineFrame>, durationMs: Long, color: Color) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(AudioUtils.formatDuration(frame.value.displayedMs), color = color, fontSize = 12.sp)
        Text(AudioUtils.formatDuration(durationMs), color = color, fontSize = 12.sp)
    }
}
