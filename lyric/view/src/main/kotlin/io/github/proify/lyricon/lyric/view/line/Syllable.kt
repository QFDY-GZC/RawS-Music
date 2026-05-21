package io.github.proify.lyricon.lyric.view.line

import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextPaint
import io.github.proify.lyricon.lyric.view.LyricPlayListener
import io.github.proify.lyricon.lyric.view.line.model.LyricModel
import io.github.proify.lyricon.lyric.view.line.model.WordModel

class Syllable(private val view: LyricLineView) {

    companion object {
        private const val LIFT_DURATION_MS = 300L
        private const val LIFT_OFFSET_DP = 8f
        private const val GRADIENT_EDGE_RATIO = 0.15f
    }

    private val backgroundPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = TextPaint(Paint.ANTI_ALIAS_FLAG)

    private val textRenderer = LineTextRenderer()

    var lastPosition = Long.MIN_VALUE
        private set

    private var lastPositionWallTimeMs: Long = 0L
    private var interpolatedPosition: Long = Long.MIN_VALUE

    var playListener: LyricPlayListener? = null

    private val rainbowColor = RainbowColor(
        background = intArrayOf(0),
        highlight = intArrayOf(0)
    )

    val isRainbowHighlight get() = rainbowColor.highlight.size > 1
    val isRainbowBackground get() = rainbowColor.background.size > 1

    var isGradientEnabled: Boolean = true
    var isScrollOnly: Boolean = false
    var isSustainLiftEnabled: Boolean = true
    var isSustainGlowEnabled: Boolean = true
    var isCharFloatAnimationEnabled: Boolean = true
    var isSharpenEnabled: Boolean = false
    var sharpenIntensity: Float = 1.0f

    val textSize: Float get() = backgroundPaint.textSize
    val isStarted: Boolean get() = lastPosition != Long.MIN_VALUE
    val isPlaying: Boolean get() = lastPosition >= 0
    val isFinished: Boolean get() = false
    val isCharAnimActive: Boolean get() = lastPosition != Long.MIN_VALUE

    private data class RainbowColor(
        var background: IntArray,
        var highlight: IntArray
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RainbowColor) return false
            return background.contentEquals(other.background) && highlight.contentEquals(other.highlight)
        }

        override fun hashCode(): Int =
            31 * background.contentHashCode() + highlight.contentHashCode()
    }

    fun setColor(background: IntArray, highlight: IntArray) {
        if (background.isEmpty() || highlight.isEmpty()) return
        if (!rainbowColor.background.contentEquals(background) || !rainbowColor.highlight.contentEquals(highlight)) {
            backgroundPaint.color = background[0]
            highlightPaint.color = highlight[0]
            rainbowColor.background = background
            rainbowColor.highlight = highlight
            textRenderer.clearShaderCache()
        }
    }

    fun setTextSize(size: Float) {
        if (backgroundPaint.textSize != size) {
            backgroundPaint.textSize = size
            highlightPaint.textSize = size
            reLayout()
        }
    }

    fun reLayout() {
        textRenderer.updateMetrics(backgroundPaint)
        view.invalidate()
    }

    fun setTypeface(typeface: Typeface?) {
        if (backgroundPaint.typeface != typeface) {
            backgroundPaint.typeface = typeface
            highlightPaint.typeface = typeface
            textRenderer.updateMetrics(backgroundPaint)
        }
    }

    fun reset() {
        lastPosition = Long.MIN_VALUE
        interpolatedPosition = Long.MIN_VALUE
        lastPositionWallTimeMs = 0L
    }

    fun seek(position: Long) {
        lastPosition = position
        interpolatedPosition = position
        lastPositionWallTimeMs = SystemClock.elapsedRealtime()
        view.invalidate()
    }

    fun updateProgress(position: Long) {
        if (lastPosition != Long.MIN_VALUE && position < lastPosition) {
            seek(position)
            return
        }
        lastPosition = position
        interpolatedPosition = position
        lastPositionWallTimeMs = SystemClock.elapsedRealtime()
        view.invalidate()
    }

    fun onFrameUpdate(nanoTime: Long): Boolean {
        if (lastPosition == Long.MIN_VALUE) return false

        val now = SystemClock.elapsedRealtime()
        val elapsed = now - lastPositionWallTimeMs
        interpolatedPosition = lastPosition + elapsed

        view.invalidate()
        return true
    }

    fun draw(canvas: Canvas) {
        textRenderer.draw(
            canvas,
            view.lyric,
            view.measuredWidth,
            view.measuredHeight,
            view.scrollXOffset,
            view.isOverflow(),
            backgroundPaint,
            highlightPaint,
            view.textPaint,
            isGradientEnabled,
            isScrollOnly,
            interpolatedPosition
        )
    }

    private fun easeOutCubic(t: Float): Float = 1f - (1f - t).let { it * it * it }

    private inner class LineTextRenderer {
        private val fontMetrics = Paint.FontMetrics()
        private var baselineOffset = 0f

        private var cachedRainbowShader: LinearGradient? = null
        private var lastTotalWidth = -1f
        private var lastColorsHash = 0

        fun updateMetrics(paint: TextPaint) {
            paint.getFontMetrics(fontMetrics)
            baselineOffset = -(fontMetrics.descent + fontMetrics.ascent) / 2f
        }

        fun clearShaderCache() {
            cachedRainbowShader = null
            lastTotalWidth = -1f
        }

        fun draw(
            canvas: Canvas, model: LyricModel, viewWidth: Int, viewHeight: Int,
            scrollX: Float, isOverflow: Boolean, bgPaint: TextPaint,
            hlPaint: TextPaint, normPaint: TextPaint,
            useGradient: Boolean, scrollOnly: Boolean,
            position: Long
        ) {
            val y = (viewHeight / 2f) + baselineOffset
            val density = view.resources.displayMetrics.density
            val liftPx = LIFT_OFFSET_DP * density

            canvas.save()
            val leftBearingPadding = model.textLeftBearingPadding
            val xOffset =
                if (isOverflow) scrollX else if (model.isAlignedRight) viewWidth - model.width else 0f
            canvas.translate(xOffset + leftBearingPadding, 0f)

            if (scrollOnly || model.words.isEmpty()) {
                if (isRainbowBackground) {
                    normPaint.shader = getOrCreateRainbowShader(model.width, rainbowColor.background)
                } else {
                    normPaint.shader = null
                }
                canvas.drawText(model.wordText, 0f, y, normPaint)
                canvas.restore()
                return
            }

            val pos = if (position == Long.MIN_VALUE) -1L else position

            for (word in model.words) {
                val wordText = word.text
                if (wordText.isEmpty()) continue

                val wordWidth = word.textWidth
                val wordStartX = word.startPosition

                val liftOffset = if (pos >= word.begin) {
                    val elapsed = (pos - word.begin).coerceAtMost(LIFT_DURATION_MS)
                    liftPx * (1f - easeOutCubic(elapsed.toFloat() / LIFT_DURATION_MS.toFloat()))
                } else 0f

                val drawY = y - liftOffset

                val colorProgress = when {
                    pos >= word.end -> 1f
                    pos <= word.begin -> 0f
                    word.duration > 0 -> ((pos - word.begin).toFloat() / word.duration).coerceIn(0f, 1f)
                    else -> 1f
                }

                if (colorProgress <= 0f) {
                    bgPaint.shader = null
                    if (isRainbowBackground) {
                        bgPaint.shader = getOrCreateRainbowShader(model.width, rainbowColor.background)
                    }
                    canvas.drawText(wordText, wordStartX, drawY, bgPaint)
                } else {
                    val dmColor = if (isRainbowBackground) rainbowColor.background.first() else bgPaint.color
                    val hlColor = if (isRainbowHighlight) rainbowColor.highlight.first() else hlPaint.color

                    bgPaint.shader = null
                    canvas.drawText(wordText, wordStartX, drawY, bgPaint)

                    if (colorProgress < 1f) {
                        val edgeWidth = wordWidth * GRADIENT_EDGE_RATIO
                        val highlightWidth = wordWidth * colorProgress
                        val transitionStart = if (highlightWidth > edgeWidth) {
                            (highlightWidth - edgeWidth) / highlightWidth
                        } else 0f

                        val shader = LinearGradient(
                            wordStartX, 0f, wordStartX + highlightWidth, 0f,
                            intArrayOf(hlColor, hlColor, dmColor),
                            floatArrayOf(0f, transitionStart, 1f),
                            Shader.TileMode.CLAMP
                        )
                        val savedShader = hlPaint.shader
                        hlPaint.shader = shader
                        canvas.drawText(wordText, wordStartX, drawY, hlPaint)
                        hlPaint.shader = savedShader
                    } else {
                        hlPaint.shader = null
                        if (isRainbowHighlight) {
                            hlPaint.shader = getOrCreateRainbowShader(model.width, rainbowColor.highlight)
                        }
                        canvas.drawText(wordText, wordStartX, drawY, hlPaint)
                    }
                }
            }

            canvas.restore()
        }

        private fun getOrCreateRainbowShader(totalWidth: Float, colors: IntArray): Shader {
            val colorsHash = colors.contentHashCode()
            if (cachedRainbowShader == null || lastTotalWidth != totalWidth || lastColorsHash != colorsHash) {
                cachedRainbowShader = LinearGradient(
                    0f, 0f, totalWidth, 0f,
                    colors, null, Shader.TileMode.CLAMP
                )
                lastTotalWidth = totalWidth
                lastColorsHash = colorsHash
            }
            return cachedRainbowShader!!
        }
    }
}
