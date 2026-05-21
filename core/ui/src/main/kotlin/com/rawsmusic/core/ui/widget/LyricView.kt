package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.SystemClock
import android.util.AttributeSet
import android.view.Choreographer
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricMode
import com.rawsmusic.core.ui.animation.AnimParams
import kotlin.math.abs

class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    companion object {
        private const val LIFT_DURATION_MS = 300L
        private const val LIFT_OFFSET_DP = 8f
        private const val LINE_ALPHA_ANIM_DURATION = 300L
        private const val ALPHA_CURRENT = 1.0f
        private const val ALPHA_PAST = 0.5f
        private const val ALPHA_FUTURE = 0.7f
    }

    private var lyricData: LyricData = LyricData()
    private var currentLineIndex: Int = -1
    private var lyricMode: LyricMode = LyricMode.SIMPLE
    private var currentPositionMs: Long = 0L
    private var interpolatedPositionMs: Long = 0L
    private var lastPositionUpdateTimeMs: Long = 0L

    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f
        color = Color.parseColor("#80FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val currentHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val currentDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        color = Color.parseColor("#60FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val translationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f
        color = Color.parseColor("#70FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val immersiveNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 38f
        color = Color.parseColor("#50FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val immersiveCurrentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 52f
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val immersiveHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 52f
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val immersiveDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 52f
        color = Color.parseColor("#40FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private var scrollY = 0f
    private var lineSpacing = 100f
    private var translationSpacing = 80f
    private var topPadding = 0f
    private val density = resources.displayMetrics.density

    private val scroller = OverScroller(context)

    private var isUserScrolling = false
    private var scrollAnimator: ValueAnimator? = null
    private var autoScrollRunnable: Runnable? = null

    var anchorTopOffset: Float = 0f
        private set

    var onLyricLineClick: ((lineIndex: Int, timestampMs: Long) -> Unit)? = null

    var onLyricLineChanged: ((original: String?, translation: String?, isDualLine: Boolean, isChinese: Boolean) -> Unit)? = null

    var onScrollBoundary: ((topReached: Boolean, bottomReached: Boolean) -> Unit)? = null

    private var lineAlphas = FloatArray(0)
    private var lineAlphaAnimators = mutableListOf<ValueAnimator>()

    private val choreographer = Choreographer.getInstance()
    private var isFrameCallbackPosted = false
    private val frameCallback = Choreographer.FrameCallback {
        isFrameCallbackPosted = false
        if (currentLineIndex >= 0 && currentLineIndex < lyricData.lines.size) {
            val line = lyricData.lines[currentLineIndex]
            if (line.hasWordTiming) {
                val elapsed = SystemClock.elapsedRealtime() - lastPositionUpdateTimeMs
                interpolatedPositionMs = currentPositionMs + elapsed
                invalidate()
                postFrameCallback()
            }
        }
    }

    private val autoScrollJob = Runnable {
        if (!isUserScrolling && currentLineIndex >= 0 && isAttachedToWindow) {
            scrollToLine(currentLineIndex, true)
        }
    }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            val clickedLine = findLineAtY(e.y)
            if (clickedLine >= 0 && clickedLine < lyricData.lines.size) {
                val line = lyricData.lines[clickedLine]
                onLyricLineClick?.invoke(clickedLine, line.timeStamp)
                currentLineIndex = clickedLine
                isUserScrolling = false
                scrollToLine(clickedLine, true)
            }
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (abs(distanceY) > abs(distanceX)) {
                isUserScrolling = true
                cancelAutoScroll()
                scrollAnimator?.cancel()
                scrollAnimator = null

                scrollY += distanceY
                scrollY = scrollY.coerceIn(0f, maxScrollY)

                if (scrollY == 0f && distanceY < 0) {
                    onScrollBoundary?.invoke(true, false)
                } else if (scrollY >= maxScrollY && distanceY > 0) {
                    onScrollBoundary?.invoke(false, true)
                } else {
                    onScrollBoundary?.invoke(false, false)
                }

                invalidate()
                return true
            }
            return false
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            isUserScrolling = true
            cancelAutoScroll()
            scroller.fling(
                0, scrollY.toInt(),
                0, -velocityY.toInt(),
                0, 0,
                0, maxScrollY.toInt()
            )
            postInvalidateOnAnimation()
            return true
        }
    })

    private fun postFrameCallback() {
        if (!isFrameCallbackPosted && isAttachedToWindow) {
            isFrameCallbackPosted = true
            choreographer.postFrameCallback(frameCallback)
        }
    }

    private fun removeFrameCallback() {
        if (isFrameCallbackPosted) {
            isFrameCallbackPosted = false
            choreographer.removeFrameCallback(frameCallback)
        }
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }

    fun setLyricData(data: LyricData) {
        lyricData = data
        currentLineIndex = -1
        currentPositionMs = 0L
        interpolatedPositionMs = 0L
        scrollY = 0f
        isUserScrolling = false
        scrollAnimator?.cancel()
        scrollAnimator = null
        lineAlphas = FloatArray(data.lines.size) { ALPHA_FUTURE }
        lineAlphaAnimators.forEach { it.cancel() }
        lineAlphaAnimators.clear()
        removeFrameCallback()
        invalidate()
    }

    fun updatePosition(positionMs: Long) {
        currentPositionMs = positionMs
        lastPositionUpdateTimeMs = SystemClock.elapsedRealtime()
        interpolatedPositionMs = positionMs
        if (lyricData.isEmpty) return
        val index = lyricData.findCurrentLine(positionMs)
        if (index >= 0 && index != currentLineIndex) {
            currentLineIndex = index
            isUserScrolling = false
            scrollToLine(index, true)
            updateLineAlphas(index)
            val line = lyricData.lines[index]
            val nextLine = lyricData.lines.getOrNull(index + 1)
            val isChinese = isChineseText(line.text)
            val isDualLine: Boolean
            val translationText: String?
            if (line.translation.isNotBlank()) {
                isDualLine = true
                translationText = line.translation
            } else if (nextLine != null && nextLine.timeStamp == line.timeStamp) {
                isDualLine = true
                translationText = nextLine.text
            } else {
                isDualLine = false
                translationText = null
            }
            onLyricLineChanged?.invoke(line.text, translationText, isDualLine, isChinese)
        }
        if (index >= 0 && lyricData.lines[index].hasWordTiming) {
            postFrameCallback()
        }
        invalidate()
    }

    private fun updateLineAlphas(currentIndex: Int) {
        lineAlphaAnimators.forEach { it.cancel() }
        lineAlphaAnimators.clear()
        for (i in lyricData.lines.indices) {
            val targetAlpha = when {
                i == currentIndex -> ALPHA_CURRENT
                i < currentIndex -> ALPHA_PAST
                else -> ALPHA_FUTURE
            }
            if (i < lineAlphas.size) {
                val startAlpha = lineAlphas[i]
                if (startAlpha != targetAlpha) {
                    val animator = ValueAnimator.ofFloat(startAlpha, targetAlpha).apply {
                        duration = LINE_ALPHA_ANIM_DURATION
                        addUpdateListener { anim ->
                            if (i < lineAlphas.size) {
                                lineAlphas[i] = anim.animatedValue as Float
                                invalidate()
                            }
                        }
                    }
                    lineAlphaAnimators.add(animator)
                    animator.start()
                } else {
                    lineAlphas[i] = targetAlpha
                }
            }
        }
    }

    private fun isChineseText(text: String): Boolean {
        if (text.isBlank()) return false
        var cjkCount = 0
        for (c in text) {
            val block = Character.UnicodeBlock.of(c)
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS) {
                cjkCount++
            }
        }
        return cjkCount * 2 > text.length
    }

    fun setCurrentLine(index: Int, smooth: Boolean = true) {
        if (index == currentLineIndex || lyricData.isEmpty) return
        currentLineIndex = index
        if (!isUserScrolling) {
            scrollToLine(index, smooth)
        }
    }

    fun setLyricMode(mode: LyricMode) {
        lyricMode = mode
        when (mode) {
            LyricMode.SIMPLE -> {
                lineSpacing = 100f
                normalPaint.textSize = 42f
                currentPaint.textSize = 48f
                currentHighlightPaint.textSize = 48f
                currentDimPaint.textSize = 48f
            }
            LyricMode.IMMERSIVE -> {
                lineSpacing = 125f
                normalPaint.textSize = 38f
                currentPaint.textSize = 52f
                currentHighlightPaint.textSize = 52f
                currentDimPaint.textSize = 52f
                immersiveHighlightPaint.textSize = 52f
                immersiveDimPaint.textSize = 52f
            }
        }
        invalidate()
    }

    fun resetScroll() {
        scrollAnimator?.cancel()
        scrollAnimator = null
        scrollY = 0f
        isUserScrolling = false
        invalidate()
    }

    fun updateAnchorOffset(offset: Float) {
        anchorTopOffset = offset
    }

    private fun scrollToLine(index: Int, smooth: Boolean) {
        if (index < 0 || index >= lyricData.lines.size) return
        val targetY = getLineScrollY(index)
        if (smooth && isAttachedToWindow) {
            scrollAnimator?.cancel()
            scrollAnimator = ValueAnimator.ofFloat(scrollY, targetY).apply {
                duration = AnimParams.PAGE_TRANSITION_DURATION
                interpolator = DecelerateInterpolator()
                addUpdateListener { anim ->
                    scrollY = anim.animatedValue as Float
                    invalidate()
                }
                start()
            }
        } else {
            scrollY = targetY
            invalidate()
        }
    }

    private fun getLineScrollY(index: Int): Float {
        val tp = anchorTopOffset + 16f * density
        var y = tp
        for (i in 0 until index) {
            y += lineSpacing
            val line = lyricData.lines[i]
            if (line.translation.isNotBlank()) {
                y += translationSpacing
            }
        }
        val bottomClip = height - 100f * density
        val centerOffset = (bottomClip - tp) / 2f
        return (y - centerOffset).coerceIn(0f, maxScrollY)
    }

    private val maxScrollY: Float
        get() {
            if (lyricData.isEmpty) return 0f
            val tp = anchorTopOffset + 16f * density
            var total = tp
            for (line in lyricData.lines) {
                total += lineSpacing
                if (line.translation.isNotBlank()) total += translationSpacing
            }
            return (total - height + 80f * density).coerceAtLeast(0f)
        }

    private fun findLineAtY(touchY: Float): Int {
        if (lyricData.isEmpty) return -1
        topPadding = anchorTopOffset + 16f * density
        var y = topPadding - scrollY
        for (i in lyricData.lines.indices) {
            val lineTop = y
            val lineBottom = y + lineSpacing +
                    (if (lyricData.lines[i].translation.isNotBlank()) translationSpacing else 0f)
            if (touchY in lineTop..lineBottom) {
                return i
            }
            y += lineSpacing
            if (lyricData.lines[i].translation.isNotBlank()) y += translationSpacing
        }
        return -1
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return

        if (lyricData.isEmpty) {
            canvas.drawText("暂无歌词", paddingLeft.toFloat(), height / 2f, normalPaint)
            return
        }

        topPadding = anchorTopOffset + 16f * density
        var y = topPadding - scrollY
        val textX = paddingLeft.toFloat()
        val bottomClipY = height - 100f * density

        for (i in lyricData.lines.indices) {
            val line = lyricData.lines[i]
            val isCurrent = i == currentLineIndex

            val lineTotalHeight = lineSpacing +
                    (if (line.translation.isNotBlank()) translationSpacing else 0f)
            if (y + lineTotalHeight < topPadding - scrollY || y - lineSpacing > bottomClipY) {
                y += lineSpacing
                if (line.translation.isNotBlank()) y += translationSpacing
                continue
            }

            val alpha = if (i < lineAlphas.size) lineAlphas[i] else ALPHA_FUTURE

            if (isCurrent && line.hasWordTiming) {
                drawLineWithLyricBoxStyle(canvas, line, textX, y, alpha)
            } else {
                val paint = when {
                    isCurrent && lyricMode == LyricMode.IMMERSIVE -> immersiveCurrentPaint
                    !isCurrent && lyricMode == LyricMode.IMMERSIVE -> immersiveNormalPaint
                    isCurrent -> currentPaint
                    else -> normalPaint
                }
                val savedAlpha = paint.alpha
                paint.alpha = (savedAlpha * alpha).toInt().coerceIn(0, 255)
                canvas.drawText(line.text, textX, y, paint)
                paint.alpha = savedAlpha
            }

            if (line.translation.isNotBlank()) {
                val savedAlpha = translationPaint.alpha
                translationPaint.alpha = (savedAlpha * alpha).toInt().coerceIn(0, 255)
                canvas.drawText(line.translation, textX, y + translationSpacing, translationPaint)
                translationPaint.alpha = savedAlpha
            }

            y += lineSpacing
            if (line.translation.isNotBlank()) y += translationSpacing
        }
    }

    private fun drawLineWithLyricBoxStyle(
        canvas: Canvas, line: LyricLine, textX: Float, y: Float, lineAlpha: Float
    ) {
        val hlPaint = when (lyricMode) {
            LyricMode.IMMERSIVE -> immersiveHighlightPaint
            LyricMode.SIMPLE -> currentHighlightPaint
        }
        val dmPaint = when (lyricMode) {
            LyricMode.IMMERSIVE -> immersiveDimPaint
            LyricMode.SIMPLE -> currentDimPaint
        }

        val text = line.text
        if (text.isEmpty() || line.words.isEmpty()) return

        val pos = interpolatedPositionMs
        var currentX = textX

        for (word in line.words) {
            val wordText = word.text
            if (wordText.isEmpty()) continue

            val wordWidth = dmPaint.measureText(wordText)

            val liftOffset = if (pos >= word.begin && !line.skipAnimation) {
                val elapsed = (pos - word.begin).coerceAtMost(LIFT_DURATION_MS)
                val progress = easeOutCubic(elapsed.toFloat() / LIFT_DURATION_MS.toFloat())
                LIFT_OFFSET_DP * density * (1f - progress)
            } else 0f

            val colorProgress = when {
                pos >= word.end -> 1f
                pos <= word.begin -> 0f
                word.duration > 0 -> ((pos - word.begin).toFloat() / word.duration).coerceIn(0f, 1f)
                else -> 1f
            }

            val drawY = y - liftOffset

            when {
                colorProgress >= 1f -> {
                    val saved = hlPaint.alpha
                    hlPaint.alpha = (saved * lineAlpha).toInt().coerceIn(0, 255)
                    canvas.drawText(wordText, currentX, drawY, hlPaint)
                    hlPaint.alpha = saved
                }
                colorProgress <= 0f -> {
                    val saved = dmPaint.alpha
                    dmPaint.alpha = (saved * lineAlpha).toInt().coerceIn(0, 255)
                    canvas.drawText(wordText, currentX, drawY, dmPaint)
                    dmPaint.alpha = saved
                }
                else -> {
                    val hlColor = hlPaint.color
                    val dmColor = dmPaint.color
                    val shader = LinearGradient(
                        currentX, 0f, currentX + wordWidth, 0f,
                        intArrayOf(hlColor, hlColor, dmColor, dmColor),
                        floatArrayOf(0f, colorProgress, colorProgress + 0.001f, 1f),
                        Shader.TileMode.CLAMP
                    )
                    val savedShader = hlPaint.shader
                    val savedAlpha = hlPaint.alpha
                    hlPaint.shader = shader
                    hlPaint.alpha = (savedAlpha * lineAlpha).toInt().coerceIn(0, 255)
                    canvas.drawText(wordText, currentX, drawY, hlPaint)
                    hlPaint.shader = savedShader
                    hlPaint.alpha = savedAlpha
                }
            }

            currentX += wordWidth
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val handled = gestureDetector.onTouchEvent(event)
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (isUserScrolling) {
                    scheduleAutoScroll()
                }
            }
        }
        return handled || super.onTouchEvent(event)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollY = scroller.currY.toFloat()
            invalidate()
        } else if (isUserScrolling && !scroller.isFinished) {
            isUserScrolling = true
        }
    }

    private fun scheduleAutoScroll() {
        cancelAutoScroll()
        autoScrollRunnable = autoScrollJob
        postDelayed(autoScrollJob, AnimParams.LYRIC_AUTO_SCROLL_DELAY)
    }

    private fun cancelAutoScroll() {
        autoScrollRunnable?.let { removeCallbacks(it) }
        autoScrollRunnable = null
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        scrollAnimator?.cancel()
        scrollAnimator = null
        cancelAutoScroll()
        scroller.abortAnimation()
        removeFrameCallback()
        lineAlphaAnimators.forEach { it.cancel() }
        lineAlphaAnimators.clear()
    }
}
