package com.rawsmusic.core.common.model

data class LyricWord(
    val text: String = "",
    val begin: Long = 0,
    val end: Long = 0,
    val duration: Long = if (end > begin) end - begin else 0
)

data class LyricLine(
    val timeStamp: Long,
    val text: String,
    val translation: String = "",
    val romanization: String = "",
    val words: List<LyricWord> = emptyList(),
    val endTime: Long = 0L,
    val skipAnimation: Boolean = false,
    val agent: String? = null,
    val backgroundText: String? = null,
    val backgroundWords: List<LyricWord> = emptyList(),
    val backgroundTranslation: String? = null,
    val isTtml: Boolean = false
) : Comparable<LyricLine> {

    override fun compareTo(other: LyricLine): Int {
        return timeStamp.compareTo(other.timeStamp)
    }

    fun isWithin(currentMs: Long, nextLineMs: Long): Boolean {
        return currentMs in timeStamp until nextLineMs
    }

    val hasWordTiming: Boolean get() = words.isNotEmpty()

    fun getWordProgress(positionMs: Long): Float {
        if (words.isEmpty()) {
            val end = if (endTime > 0) endTime else timeStamp + 3000L
            if (end <= timeStamp) return 1f
            val progress = (positionMs - timeStamp).toFloat() / (end - timeStamp).toFloat()
            return progress.coerceIn(0f, 1f)
        }
        val lastWord = words.last()
        val end = lastWord.end.takeIf { it > 0 } ?: (timeStamp + 3000L)
        if (end <= timeStamp) return 1f
        val progress = (positionMs - timeStamp).toFloat() / (end - timeStamp).toFloat()
        return progress.coerceIn(0f, 1f)
    }

    fun getHighlightedCharCount(positionMs: Long): Int {
        if (words.isEmpty()) return if (positionMs >= timeStamp) text.length else 0
        var count = 0
        var lastEnd = 0L
        for (word in words) {
            val timeGap = word.begin - lastEnd
            val isTooClose = timeGap in 0..999

            if (positionMs >= word.end) {
                count += word.text.length
            } else if (positionMs >= word.begin) {
                if (isTooClose) {
                    count += word.text.length
                } else {
                    val wordProgress = if (word.duration > 0) {
                        (positionMs - word.begin).toFloat() / word.duration.toFloat()
                    } else 1f
                    count += (word.text.length * wordProgress.coerceIn(0f, 1f)).toInt()
                }
                break
            } else {
                if (isTooClose && count > 0 && positionMs >= lastEnd) {
                    count += word.text.length
                } else {
                    break
                }
            }
            lastEnd = word.end
        }
        return count.coerceAtMost(text.length)
    }

    fun getCurrentWordText(positionMs: Long): String {
        if (words.isEmpty()) return text
        val sb = StringBuilder()
        for (word in words) {
            if (positionMs >= word.begin) {
                sb.append(word.text)
            } else {
                break
            }
        }
        return sb.toString().trim()
    }
}

enum class LyricMode {
    SIMPLE,
    IMMERSIVE
}

data class LyricData(
    val lines: List<LyricLine> = emptyList(),
    val offset: Long = 0
) {
    val isEmpty: Boolean get() = lines.isEmpty()

    /**
     * 预处理动画标记：计算哪些行与上一行 timeStamp 间隔<1秒，需要跳过动画
     * 返回新的 LyricData，其中每行的 skipAnimation 字段已设置
     */
    fun withAnimationFlags(): LyricData {
        if (lines.size <= 1) return this
        // 飞入动画时长1200ms，行持续时间小于此值时跳过动画
        val flyInDuration = 1200L
        val processedLines = lines.mapIndexed { index, line ->
            if (index == 0) {
                // 第一行总是有动画
                line
            } else {
                val prevLine = lines[index - 1]
                // 条件1: 与上一行 timeStamp 间隔 < 1秒
                val timeGap = line.timeStamp - prevLine.timeStamp
                val gapTooShort = timeGap in 0 until 1000
                // 条件2: 行持续时间 < 飞入动画时长（动画还没播完歌词就结束了）
                val lineDuration = line.getEffectiveDuration()
                val durationTooShort = lineDuration in 1 until flyInDuration
                val shouldSkip = gapTooShort || durationTooShort
                if (shouldSkip) line.copy(skipAnimation = true) else line
            }
        }
        return copy(lines = processedLines)
    }

    /**
     * 获取歌词行的有效持续时间（毫秒）
     * 优先使用 endTime，其次使用最后一个 word 的 end，否则返回 0（未知）
     */
    private fun LyricLine.getEffectiveDuration(): Long {
        if (endTime > timeStamp) return endTime - timeStamp
        if (words.isNotEmpty()) {
            val lastWordEnd = words.last().end
            if (lastWordEnd > timeStamp) return lastWordEnd - timeStamp
        }
        return 0L  // 无法确定持续时间，不参与判断
    }

    fun findCurrentLine(positionMs: Long, advanceMs: Long = 0L): Int {
        if (lines.isEmpty()) return -1
        val adjusted = positionMs + advanceMs
        var low = 0
        var high = lines.size - 1
        var result = -1
        while (low <= high) {
            val mid = (low + high) ushr 1
            if (lines[mid].timeStamp + offset <= adjusted) {
                result = mid
                low = mid + 1
            } else {
                high = mid - 1
            }
        }
        return result
    }

    fun getLine(index: Int): LyricLine? {
        return if (index in lines.indices) lines[index] else null
    }
}
