package com.rawsmusic.core.ui.widget

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricMode
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.ui.animation.AnimParams
import kotlin.math.abs

class LyricView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private var lyricData: LyricData = LyricData()
    private var currentLineIndex: Int = -1
    private var lyricMode: LyricMode = LyricMode.SIMPLE
    private var currentPositionMs: Long = 0L

    // 字体减小50%：原200%为84f→42f, 96f→48f
    // 行间距减半：原200→100
    // 原文翻译间距加大：原50→80
    private val normalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 42f   // 原84f * 0.5
        color = Color.parseColor("#80FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true  // 加粗
    }

    private val currentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f   // 原96f * 0.5
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    /** 逐字高亮 - 已高亮部分的画笔 */
    private val currentHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    /** 逐字高亮 - 未高亮部分的画笔 */
    private val currentDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 48f
        color = Color.parseColor("#60FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private val translationPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 30f   // 原60f * 0.5
        color = Color.parseColor("#70FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true  // 翻译也加粗
    }

    private val immersiveNormalPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 38f   // 原76f * 0.5
        color = Color.parseColor("#50FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
    }

    private val immersiveCurrentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 52f  // 原104f * 0.5
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    /** 沉浸模式逐字高亮 - 已高亮部分 */
    private val immersiveHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 52f
        color = Color.WHITE
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    /** 沉浸模式逐字高亮 - 未高亮部分 */
    private val immersiveDimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 52f
        color = Color.parseColor("#40FFFFFF")
        textAlign = Paint.Align.LEFT
        isFakeBoldText = true
        strokeWidth = 3f
        style = Paint.Style.FILL_AND_STROKE
    }

    private var scrollY = 0f
    // 字体减小50%后行间距同步缩小，原文翻译间距加大防重叠
    private var lineSpacing = 100f     // 原200f * 0.5
    private var translationSpacing = 80f   // 原文和翻译间距加大
    private var topPadding = 0f
    private val density = resources.displayMetrics.density

    private val scroller = OverScroller(context)

    private var isUserScrolling = false
    private var scrollAnimator: ValueAnimator? = null
    private var autoScrollRunnable: Runnable? = null

    /** 高亮行居中偏移 — 歌词高亮时居中至屏幕 */
    var anchorTopOffset: Float = 0f
        private set

    /** 点击歌词行跳转回调 */
    var onLyricLineClick: ((lineIndex: Int, timestampMs: Long) -> Unit)? = null

    /** 歌词行变化回调 — 用于更新胶囊播放栏实时歌词
     *  参数：original, translation, isDualLine, isChinese
     */
    var onLyricLineChanged: ((original: String?, translation: String?, isDualLine: Boolean, isChinese: Boolean) -> Unit)? = null

    /** 歌词滚动到边界时回调 */
    var onScrollBoundary: ((topReached: Boolean, bottomReached: Boolean) -> Unit)? = null

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

                // 修复手势方向：distanceY正值=手指向下→内容向上→scrollY增加
                scrollY += distanceY
                scrollY = scrollY.coerceIn(0f, maxScrollY)

                // 边界检测
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

    fun setLyricData(data: LyricData) {
        lyricData = data
        currentLineIndex = -1
        currentPositionMs = 0L
        scrollY = 0f
        isUserScrolling = false
        scrollAnimator?.cancel()
        scrollAnimator = null
        invalidate()
    }

    fun updatePosition(positionMs: Long) {
        currentPositionMs = positionMs
        if (lyricData.isEmpty) return
        val index = lyricData.findCurrentLine(positionMs)
        if (index >= 0 && index != currentLineIndex) {
            currentLineIndex = index
            // 新行高亮瞬间，自动归位对齐，重置用户滚动状态
            isUserScrolling = false
            scrollToLine(index, true)
            // 通知胶囊播放栏歌词行变化 — 双语判定逻辑
            val line = lyricData.lines[index]
            val nextLine = lyricData.lines.getOrNull(index + 1)
            val isChinese = isChineseText(line.text)
            // 判定是否双行显示：
            // 1. 当前行有translation字段（来自TTML或 / 分隔）→ 双行
            // 2. 下一行有相同时间戳（LRC格式原文+翻译分两行）→ 双行
            val isDualLine: Boolean
            val translationText: String?
            if (line.translation.isNotBlank()) {
                // 当前行自带翻译（TTML/内嵌格式）
                isDualLine = true
                translationText = line.translation
            } else if (nextLine != null && nextLine.timeStamp == line.timeStamp) {
                // 下一行有相同时间戳 → 视为翻译行
                isDualLine = true
                translationText = nextLine.text
            } else {
                isDualLine = false
                translationText = null
            }
            onLyricLineChanged?.invoke(line.text, translationText, isDualLine, isChinese)
        } else if (index >= 0 && index == currentLineIndex) {
            // 同一行内逐字高亮更新
            if (lyricData.lines[index].hasWordTiming) {
                invalidate()
            }
        }
    }

    /** 判断文本是否主要为中文 */
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
        return cjkCount * 2 > text.length  // CJK字符占比>50%视为中文
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

    /**
     * 外部更新anchor偏移（歌词主层高度）— 仅用于边界检测
     */
    fun updateAnchorOffset(offset: Float) {
        anchorTopOffset = offset
    }

    /**
     * 滚动到指定行 — 高亮行居中至屏幕
     */
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

    /**
     * 计算指定行的滚动Y坐标 — 高亮行居中至歌词容器65%区域
     */
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
        // 高亮行居中：居中于可用区域（顶部遮挡区 到 底部控制栏之间）
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

        // 歌词容器：65%屏幕高度居中
        // topPadding = 主层高度 + 额外间距（歌词从主层下方开始）
        topPadding = anchorTopOffset + 16f * density
        var y = topPadding - scrollY
        val textX = paddingLeft.toFloat()

        // 底部控制栏高度估算
        val bottomClipY = height - 100f * density

        for (i in lyricData.lines.indices) {
            val line = lyricData.lines[i]
            val isCurrent = i == currentLineIndex

            // 超出可视范围的行跳过绘制
            val lineTotalHeight = lineSpacing +
                    (if (line.translation.isNotBlank()) translationSpacing else 0f)
            if (y + lineTotalHeight < topPadding - scrollY || y - lineSpacing > bottomClipY) {
                y += lineSpacing
                if (line.translation.isNotBlank()) y += translationSpacing
                continue
            }

            // 原文
            if (isCurrent && line.hasWordTiming) {
                drawLineWithWordHighlight(canvas, line, textX, y)
            } else {
                val paint = when {
                    isCurrent && lyricMode == LyricMode.IMMERSIVE -> immersiveCurrentPaint
                    !isCurrent && lyricMode == LyricMode.IMMERSIVE -> immersiveNormalPaint
                    isCurrent -> currentPaint
                    else -> normalPaint
                }
                canvas.drawText(line.text, textX, y, paint)
            }

            // 翻译紧挨原文下方
            if (line.translation.isNotBlank()) {
                canvas.drawText(line.translation, textX, y + translationSpacing, translationPaint)
            }

            y += lineSpacing
            if (line.translation.isNotBlank()) y += translationSpacing
        }
    }

    /**
     * 逐字/逐词高亮渲染 — 当前行的文字按时间轴分段着色
     */
    private fun drawLineWithWordHighlight(canvas: Canvas, line: LyricLine, textX: Float, y: Float) {
        val highlightPaint = when (lyricMode) {
            LyricMode.IMMERSIVE -> immersiveHighlightPaint
            LyricMode.SIMPLE -> currentHighlightPaint
        }
        val dimPaint = when (lyricMode) {
            LyricMode.IMMERSIVE -> immersiveDimPaint
            LyricMode.SIMPLE -> currentDimPaint
        }

        val text = line.text
        if (text.isEmpty()) return

        val highlightCount = line.getHighlightedCharCount(currentPositionMs)

        if (highlightCount <= 0) {
            // 全部未高亮
            canvas.drawText(text, textX, y, dimPaint)
        } else if (highlightCount >= text.length) {
            // 全部高亮
            canvas.drawText(text, textX, y, highlightPaint)
        } else {
            // 部分高亮 — 先画暗色全文，再用高亮色覆盖已唱部分
            canvas.drawText(text, textX, y, dimPaint)
            // 测量已高亮部分的宽度
            val highlightWidth = highlightPaint.measureText(text, 0, highlightCount)
            canvas.save()
            canvas.clipRect(textX, y - highlightPaint.textSize, textX + highlightWidth, y + highlightPaint.descent())
            canvas.drawText(text, textX, y, highlightPaint)
            canvas.restore()
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

    /** 手动滑动后延迟自动归位 — 下一句高亮切换时自动平滑归位到顶部遮挡线 */
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
    }
}
