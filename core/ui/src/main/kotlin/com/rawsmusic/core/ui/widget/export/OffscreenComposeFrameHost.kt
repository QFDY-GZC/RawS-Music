package com.rawsmusic.core.ui.widget.export

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.view.View
import android.view.ViewGroup
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Recomposer
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.ComposeView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield

/**
 * Hosts a real Compose tree off screen and renders it frame-by-frame at a caller-controlled time.
 *
 * The composition gets its own [Recomposer] whose [BroadcastFrameClock] only ticks when
 * [advanceFrame] is called. Every `withFrameNanos`, `animate*AsState`, spring and lazy-list scroll
 * animation inside the tree therefore advances by exactly the exported frame interval instead of
 * wall-clock time, which makes lyric-video frames deterministic regardless of encode speed.
 *
 * The view is attached (so the ViewTree lifecycle/saved-state owners and hardware display lists
 * exist) inside a GONE container: the window never draws it, only [draw] does. Must be used from
 * the main thread.
 */
class OffscreenComposeFrameHost(
    activity: Activity,
    val widthPx: Int,
    val heightPx: Int,
    content: @Composable () -> Unit,
) {
    private val frameClock = BroadcastFrameClock()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main + frameClock)
    private val recomposer = Recomposer(scope.coroutineContext)
    private val container = DetachedLayout(activity)
    private val composeView = ComposeView(activity)
    private var released = false

    init {
        require(widthPx > 0 && heightPx > 0) { "Invalid export size ${widthPx}x$heightPx" }
        scope.launch { recomposer.runRecomposeAndApplyChanges() }
        composeView.setParentCompositionContext(recomposer)
        container.visibility = View.GONE
        container.addView(composeView, ViewGroup.LayoutParams(widthPx, heightPx))
        (activity.window.decorView as ViewGroup).addView(container, ViewGroup.LayoutParams(0, 0))
        composeView.setContent(content)
    }

    /**
     * Advance the composition to [frameTimeNanos]. Two frame passes are issued with the same time:
     * the first runs animation frame callbacks (which write state), the second recomposes with
     * those values. Both are idempotent for an unchanged frame time.
     */
    suspend fun advanceFrame(frameTimeNanos: Long) {
        check(!released) { "Host already released" }
        repeat(2) {
            Snapshot.sendApplyNotifications()
            frameClock.sendFrame(frameTimeNanos)
            // Let continuations resumed by this frame (animation/effect coroutines on Main) run
            // before the next pass reads their state.
            repeat(DRAIN_YIELDS) { yield() }
        }
        Snapshot.sendApplyNotifications()
        measureAndLayout()
    }

    /** Draw the laid-out tree into [canvas]. Use a hardware canvas so blur/RenderEffect apply. */
    fun draw(canvas: Canvas) {
        check(!released) { "Host already released" }
        composeView.draw(canvas)
    }

    fun release() {
        if (released) return
        released = true
        runCatching { composeView.disposeComposition() }
        runCatching { (container.parent as? ViewGroup)?.removeView(container) }
        recomposer.cancel()
        scope.cancel()
    }

    private fun measureAndLayout() {
        composeView.measure(
            View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY),
        )
        composeView.layout(0, 0, widthPx, heightPx)
    }

    /** Container that never lays out/measures its child: the host owns the child's geometry. */
    private class DetachedLayout(context: Context) : ViewGroup(context) {
        override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
            setMeasuredDimension(0, 0)
        }

        override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) = Unit
    }

    private companion object {
        const val DRAIN_YIELDS = 3
    }
}
