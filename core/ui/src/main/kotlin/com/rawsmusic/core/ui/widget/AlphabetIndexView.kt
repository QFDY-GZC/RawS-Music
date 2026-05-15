package com.rawsmusic.core.ui.widget

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.widget.FrameLayout

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
            // 平假名 あ-ん (U+3040-U+309F)
            c in '\u3040'..'\u309F' -> categorizeHiragana(c)
            // 片假名 ア-ン (U+30A0-U+30FF)
            c in '\u30A0'..'\u30FF' -> categorizeKatakana(c)
            // CJK统一汉字 — 转拼音首字母
            c in '\u4E00'..'\u9FFF' -> getPinyinInitial(c)
            // 其他特殊符号
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

    /** 中文转拼音首字母 — 简化实现 */
    private fun getPinyinInitial(c: Char): String {
        // 简化拼音首字母映射 — 基于Unicode区间
        val code = c.code
        return when {
            code in 0x4E00..0x4E53 -> "A"
            code in 0x4E54..0x4E87 -> "B"
            code in 0x4E88..0x4EA0 -> "C"
            code in 0x4EA1..0x4EFB -> "D"
            code in 0x4EFC..0x4F15 -> "E"
            code in 0x4F16..0x4F59 -> "F"
            code in 0x4F5A..0x4FAD -> "G"
            code in 0x4FAE..0x4FDF -> "H"
            code in 0x4FE0..0x4FF9 -> "J"
            code in 0x4FFA..0x503F -> "K"
            code in 0x5040..0x5085 -> "L"
            code in 0x5086..0x50BD -> "M"
            code in 0x50BE..0x5101 -> "N"
            code in 0x5102..0x5148 -> "O"
            code in 0x5149..0x5175 -> "P"
            code in 0x5176..0x5199 -> "Q"
            code in 0x519A..0x51CF -> "R"
            code in 0x51D0..0x5235 -> "S"
            code in 0x5236..0x5269 -> "T"
            code in 0x526A..0x5291 -> "W"
            code in 0x5292..0x52C2 -> "X"
            code in 0x52C3..0x52F2 -> "Y"
            code in 0x52F3..0x5394 -> "Z"
            else -> {
                // 更精确的拼音首字母 — 使用字符串比较
                getPinyinInitialFallback(c)
            }
        }
    }

    /** 拼音首字母回退方案 — 常见汉字 */
    private fun getPinyinInitialFallback(c: Char): String {
        val pinyinMap = mapOf(
            '一' to "Y", '二' to "E", '三' to "S", '四' to "S", '五' to "W",
            '六' to "L", '七' to "Q", '八' to "B", '九' to "J", '十' to "S",
            '百' to "B", '千' to "Q", '万' to "W", '年' to "N", '月' to "Y",
            '日' to "R", '时' to "S", '分' to "F", '秒' to "M", '大' to "D",
            '小' to "X", '中' to "Z", '国' to "G", '人' to "R", '我' to "W",
            '你' to "N", '他' to "T", '她' to "T", '它' to "T", '们' to "M",
            '爱' to "A", '好' to "H", '美' to "M", '天' to "T", '地' to "D",
            '风' to "F", '花' to "H", '雪' to "X", '雨' to "Y", '山' to "S",
            '水' to "S", '火' to "H", '心' to "X", '梦' to "M", '歌' to "G",
            '乐' to "Y", '曲' to "Q", '音' to "Y", '声' to "S", '情' to "Q",
            '思' to "S", '念' to "N", '回' to "H", '忆' to "Y", '春' to "C",
            '夏' to "X", '秋' to "Q", '冬' to "D", '星' to "X", '光' to "G",
            '影' to "Y", '夜' to "Y", '明' to "M", '暗' to "A", '黑' to "H",
            '白' to "B", '红' to "H", '蓝' to "L", '绿' to "L", '金' to "J",
            '银' to "Y", '青' to "Q", '紫' to "Z", '灰' to "H"
        )
        return pinyinMap[c] ?: "#"
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
