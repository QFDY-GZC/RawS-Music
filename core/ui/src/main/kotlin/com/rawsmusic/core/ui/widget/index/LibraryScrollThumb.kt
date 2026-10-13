package com.rawsmusic.core.ui.widget.index

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import kotlin.math.abs

/** One draw-only observer; scrolling does not recompose HOME's carousel or card holders. */
@Composable
internal fun LibraryScrollThumb(state: ComposeVirtualListState, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val view = remember(context, state) { LibraryScrollThumbView(context, state) }
    val light = MiuixTheme.colorScheme.background.luminance() > 0.5f
    val range = state.settledScrollRangePx
    DisposableEffect(view, state) {
        val listener: () -> Unit = { view.invalidate() }
        state.viewportScrollOwner.addExactPositionListener(listener)
        onDispose {
            state.viewportScrollOwner.removeExactPositionListener(listener)
            view.dispose()
        }
    }
    AndroidView(
        factory = { view },
        modifier = modifier.width(24.dp).fillMaxHeight(),
        update = {
            it.normalColor = if (light) 0xff888888.toInt() else 0xff888888.toInt()
            it.pressedColor = if (light) 0xff444444.toInt() else 0xffbbbbbb.toInt()
            it.visibility = if (range > 0) View.VISIBLE else View.INVISIBLE
            it.invalidate()
        },
    )
}

private class LibraryScrollThumbView(context: Context, val state: ComposeVirtualListState) : View(context) {
    var normalColor: Int = 0xff888888.toInt()
    var pressedColor: Int = 0xff444444.toInt()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val density = resources.displayMetrics.density
    private var widthScale = 1f
    private var animation: ValueAnimator? = null
    private var captured = false
    private var dragging = false
    private var downY = 0f
    private var grabOffset = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { contentDescription = "Scroll"; isClickable = true }

    private fun geometry() = libraryScrollGeometry(height.toFloat(), density)

    override fun onDraw(canvas: Canvas) {
        if (state.settledScrollRangePx <= 0) return
        val geometry = geometry()
        val top = geometry.thumbTop(state.viewportScrollY, state.settledScrollRangePx.toFloat())
        val thumbWidth = 10f * density * widthScale
        paint.color = if (captured) pressedColor else normalColor
        canvas.drawRoundRect(width - thumbWidth, top, width.toFloat(), top + geometry.thumbHeight,
            thumbWidth / 2f, thumbWidth / 2f, paint)
    }

    private fun animatePressed(pressed: Boolean) {
        animation?.cancel()
        animation = ValueAnimator.ofFloat(widthScale, if (pressed) 1.5f else 1f).apply {
            duration = 250L
            addUpdateListener { widthScale = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (state.settledScrollRangePx <= 0 || state.isTransitioning) return false
                val geometry = geometry()
                val top = geometry.thumbTop(state.viewportScrollY, state.settledScrollRangePx.toFloat())
                if (event.y < top || event.y > top + geometry.thumbHeight) return false
                captured = true
                dragging = false
                downY = event.y
                grabOffset = event.y - top
                parent?.requestDisallowInterceptTouchEvent(true)
                animatePressed(true)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!captured) return false
                if (!dragging && abs(event.y - downY) > slop) dragging = true
                if (dragging) {
                    state.requestScrollToPixel(geometry().scrollForThumb(event.y - grabOffset, state.settledScrollRangePx.toFloat()))
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!captured) return false
                captured = false
                parent?.requestDisallowInterceptTouchEvent(false)
                animatePressed(false)
                if (!dragging && event.actionMasked == MotionEvent.ACTION_UP) performClick()
            }
        }
        return captured || event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL
    }

    override fun performClick(): Boolean { super.performClick(); return true }
    fun dispose() { animation?.cancel(); captured = false; parent?.requestDisallowInterceptTouchEvent(false) }
}
