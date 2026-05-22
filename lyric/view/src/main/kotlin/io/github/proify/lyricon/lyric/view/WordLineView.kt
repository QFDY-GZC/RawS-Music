package io.github.proify.lyricon.lyric.view

import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Color
import android.os.SystemClock
import android.view.View
import android.view.animation.OvershootInterpolator
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.view.children
import io.github.proify.lyricon.lyric.model.LyricWord
import io.github.proify.lyricon.lyric.view.dp
import kotlin.math.abs

class WordLineView(context: Context) : LinearLayout(context) {

    companion object {
        private const val BOUNCE_DURATION_MS = 150L
        private const val BOUNCE_UP_DP = 3f
        private const val BOUNCE_SCALE = 1.1f
        private const val MIN_BOUNCE_INTERVAL_MS = 200L
    }

    private val wordViews = mutableListOf<TextView>()
    private var currentHighlightedWord: LyricWord? = null
    private var lastBounceTime = 0L
    private val argbEvaluator = ArgbEvaluator()
    private var animator: ValueAnimator? = null

    init {
        orientation = HORIZONTAL
    }

    /**
     * 设置单词列表，创建对应的 TextView
     */
    fun setWords(words: List<LyricWord>) {
        removeAllViews()
        wordViews.clear()
        words.forEach { word ->
            val textView = TextView(context).apply {
                text = word.text
                textSize = 16f
                setTextColor(Color.WHITE)
                tag = word
            }
            addView(textView)
            wordViews.add(textView)
        }
    }

    /**
     * 高亮指定单词，并触发动效
     */
    fun highlightWord(word: LyricWord, highlightColor: Int) {
        if (currentHighlightedWord == word) return
        currentHighlightedWord = word

        wordViews.forEach { tv ->
            val w = tv.tag as? LyricWord
            if (w == word) {
                tv.setTextColor(highlightColor)
                triggerBounce(tv)
            } else {
                tv.setTextColor(Color.WHITE)
            }
        }
    }

    /**
     * 更新单词内高亮进度（卡拉 OK 效果）
     */
    fun updateWordProgress(word: LyricWord, progress: Float) {
        val startColor = Color.WHITE
        val endColor = currentHighlightColor ?: Color.YELLOW
        val blended = argbEvaluator.evaluate(progress, startColor, endColor) as Int
        wordViews.find { it.tag == word }?.setTextColor(blended)
    }

    private var currentHighlightColor: Int? = null

    fun setHighlightColor(color: Int) {
        currentHighlightColor = color
    }

    private fun triggerBounce(view: View) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastBounceTime < MIN_BOUNCE_INTERVAL_MS) return
        lastBounceTime = now

        view.animate()
            .translationY(-BOUNCE_UP_DP.dp)
            .scaleX(BOUNCE_SCALE)
            .scaleY(BOUNCE_SCALE)
            .setDuration(BOUNCE_DURATION_MS)
            .setInterpolator(OvershootInterpolator(1.5f))
            .withEndAction {
                view.animate()
                    .translationY(0f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(BOUNCE_DURATION_MS)
                    .start()
            }
            .start()
    }

    fun reset() {
        currentHighlightedWord = null
        wordViews.forEach { it.animate().cancel() }
    }
}