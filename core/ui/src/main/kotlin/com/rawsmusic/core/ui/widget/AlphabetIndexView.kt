package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout
import com.rawsmusic.core.common.utils.CjkSortUtils

/**
 * 动态索引侧边栏 — 自动适配中文/日文(平假名/片假名)/数字/英文/特殊符号
 * 根据实际歌曲列表动态生成索引字符，无缺失、无遗漏
 */
class AlphabetIndexView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : FrameLayout(context, attrs, defStyleAttr) {

    var onLetterSelected: ((String) -> Unit)? = null

    /** 动态索引字符列表 */
    private var letters = listOf("#", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
        "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")

    private val density = resources.displayMetrics.density
    private val letterPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 8f * density
        color = 0xD0FFFFFF.toInt() // 始终高亮白色
        textAlign = Paint.Align.CENTER
    }

    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 9f * density
        color = 0xFFFFFFFF.toInt() // 选中时纯白
        textAlign = Paint.Align.CENTER
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    private val capsulePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x0CFFFFFF // 极淡背景
        style = Paint.Style.FILL
    }

    private val capsuleRect = RectF()
    private var selectedIndex = -1

    init {
        setWillNotDraw(false)
    }

    /**
     * 根据歌曲标题列表动态生成索引
     * 自动适配：中文(拼音首字母)、日文(平假名/片假名分组)、数字、英文、特殊符号
     */
    fun updateIndexFromTitles(titles: List<String>) {
        val indexSet = linkedSetOf<String>()

        for (title in titles) {
            val firstChar = title.firstOrNull() ?: continue
            val key = categorizeChar(firstChar)
            indexSet.add(key)
        }

        if (indexSet.isEmpty()) {
            letters = defaultLetters()
        } else {
            // 排序：# → 数字 → 英文 → 平假名 → 片假名 → 中文拼音
            letters = indexSet.sortedWith(compareBy { indexSortKey(it) })
            // 确保至少有内容
            if (letters.isEmpty()) letters = defaultLetters()
        }
        invalidate()
    }

    /**
     * 字符分类规则
     * - 特殊符号 → "#"
     * - 数字 → "0-9"
     * - 英文 → 大写字母
     * - 平假名 → あ-ん 分组
     * - 片假名 → ア-ン 分组
     * - 中文 → 拼音首字母
     */
    private fun categorizeChar(c: Char): String {
        return when {
            c in 'A'..'Z' -> c.toString()
            c in 'a'..'z' -> c.uppercaseChar().toString()
            c in '0'..'9' -> "0-9"
            c in '\u3040'..'\u309F' -> categorizeHiragana(c)
            c in '\u30A0'..'\u30FF' -> categorizeKatakana(c)
            c in '\u4E00'..'\u9FFF' -> CjkSortUtils.getPinyinInitial(c)
            else -> "#"
        }
    }

    /** 平假名分组：あ行、か行、さ行、た行、な行、は行、ま行、や行、ら行、わ行 */
    private fun categorizeHiragana(c: Char): String {
        val groups = listOf(
            "あ" to listOf('あ','い','う','え','お'),
            "か" to listOf('か','き','く','け','こ','が','ぎ','ぐ','げ','ご'),
            "さ" to listOf('さ','し','す','せ','そ','ざ','じ','ず','ぜ','ぞ'),
            "た" to listOf('た','ち','つ','て','と','だ','ぢ','づ','で','ど'),
            "な" to listOf('な','に','ぬ','ね','の'),
            "は" to listOf('は','ひ','ふ','へ','ほ','ば','び','ぶ','べ','ぼ','ぱ','ぴ','ぷ','ぺ','ぽ'),
            "ま" to listOf('ま','み','む','め','も'),
            "や" to listOf('や','ゆ','よ'),
            "ら" to listOf('ら','り','る','れ','ろ'),
            "わ" to listOf('わ','を','ん')
        )
        for ((label, chars) in groups) {
            if (c in chars) return label
        }
        return "あ"
    }

    /** 片假名分组：ア行、カ行、サ行、タ行、ナ行、ハ行、マ行、ヤ行、ラ行、ワ行 */
    private fun categorizeKatakana(c: Char): String {
        val groups = listOf(
            "ア" to listOf('ア','イ','ウ','エ','オ'),
            "カ" to listOf('カ','キ','ク','ケ','コ','ガ','ギ','グ','ゲ','ゴ'),
            "サ" to listOf('サ','シ','ス','セ','ソ','ザ','ジ','ズ','ゼ','ゾ'),
            "タ" to listOf('タ','チ','ツ','テ','ト','ダ','ヂ','ヅ','デ','ド'),
            "ナ" to listOf('ナ','ニ','ヌ','ネ','ノ'),
            "ハ" to listOf('ハ','ヒ','フ','ヘ','ホ','バ','ビ','ブ','ベ','ボ','パ','ピ','プ','ペ','ポ'),
            "マ" to listOf('マ','ミ','ム','メ','モ'),
            "ヤ" to listOf('ヤ','ユ','ヨ'),
            "ラ" to listOf('ラ','リ','ル','レ','ロ'),
            "ワ" to listOf('ワ','ヲ','ン')
        )
        for ((label, chars) in groups) {
            if (c in chars) return label
        }
        return "ア"
    }

    /** 索引排序键 */
    private fun indexSortKey(key: String): Int {
        return when {
            key == "#" -> 0
            key == "0-9" -> 1
            key.length == 1 && key[0] in 'A'..'Z' -> 2 + (key[0] - 'A')
            key in listOf("あ","か","さ","た","な","は","ま","や","ら","わ") -> 30 + listOf("あ","か","さ","た","な","は","ま","や","ら","わ").indexOf(key)
            key in listOf("ア","カ","サ","タ","ナ","ハ","マ","ヤ","ラ","ワ") -> 40 + listOf("ア","カ","サ","タ","ナ","ハ","マ","ヤ","ラ","ワ").indexOf(key)
            else -> 60
        }
    }

    private fun defaultLetters(): List<String> {
        return listOf("#", "A", "B", "C", "D", "E", "F", "G", "H", "I", "J", "K", "L", "M",
            "N", "O", "P", "Q", "R", "S", "T", "U", "V", "W", "X", "Y", "Z")
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = (16 * density).toInt() // 进一步压缩宽度，确保#号显示
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
            heightMeasureSpec
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        val capsulePadding = 4 * density
        capsuleRect.set(
            capsulePadding,
            capsulePadding,
            width - capsulePadding,
            height - capsulePadding
        )
        canvas.drawRoundRect(capsuleRect, 12 * density, 12 * density, capsulePaint)

        if (letters.isEmpty()) return

        val contentTop = capsulePadding + 4 * density
        val contentHeight = height - 2 * capsulePadding - 8 * density
        val itemHeight = contentHeight / letters.size

        letters.forEachIndexed { index, letter ->
            val x = width / 2f
            val y = contentTop + itemHeight * index + itemHeight / 2f - (letterPaint.descent() + letterPaint.ascent()) / 2f
            val paint = if (index == selectedIndex) highlightPaint else letterPaint

            // CJK字符需要更小字号
            val isCJK = letter.any { it.code > 0x3000 }
            if (isCJK && index != selectedIndex) {
                paint.textSize = 7f * density
            } else if (isCJK && index == selectedIndex) {
                highlightPaint.textSize = 8f * density
            }

            canvas.drawText(letter, x, y, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (letters.isEmpty()) return false
        val capsulePadding = 4 * density
        val contentTop = capsulePadding + 4 * density
        val contentHeight = height - 2 * capsulePadding - 8 * density
        val itemHeight = contentHeight / letters.size
        val index = ((event.y - contentTop) / itemHeight).toInt().coerceIn(0, letters.size - 1)

        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                if (index != selectedIndex) {
                    selectedIndex = index
                    onLetterSelected?.invoke(letters[index])
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                selectedIndex = -1
                invalidate()
                return true
            }
        }
        return super.onTouchEvent(event)
    }
}
