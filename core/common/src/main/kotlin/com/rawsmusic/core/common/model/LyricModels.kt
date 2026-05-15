package com.rawsmusic.core.common.model

/**
 * 逐字/逐词时间信息（增强型LRC、TTML）
 */
data class LyricWord(
    val begin: Long = 0,
    val end: Long = 0,
    val duration: Long = if (end > begin) end - begin else 0,
    val text: String = ""
)

data class LyricLine(
    val timeStamp: Long,
    val text: String,
    val translation: String = "",
    val romanization: String = "",
    /** 逐字/逐词时间轴（增强型LRC <mm:ss.xx>、TTML <span>） */
    val words: List<LyricWord> = emptyList(),
    /** 结束时间（TTML/增强型LRC可用） */
    val endTime: Long = 0L
) : Comparable<LyricLine> {

    override fun compareTo(other: LyricLine): Int {
        return timeStamp.compareTo(other.timeStamp)
    }

    fun isWithin(currentMs: Long, nextLineMs: Long): Boolean {
        return currentMs in timeStamp until nextLineMs
    }

    /** 是否有逐字时间轴 */
    val hasWordTiming: Boolean get() = words.isNotEmpty()

    /**
     * 根据播放位置获取当前行内的逐字高亮进度 [0f, 1f]
     */
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

    /**
     * 根据播放位置获取当前行内已高亮的字符数
     */
    fun getHighlightedCharCount(positionMs: Long): Int {
        if (words.isEmpty()) return if (positionMs >= timeStamp) text.length else 0
        var count = 0
        for (word in words) {
            if (positionMs >= word.end) {
                count += word.text.length
            } else if (positionMs >= word.begin) {
                // 部分高亮
                val wordProgress = if (word.duration > 0) {
                    (positionMs - word.begin).toFloat() / word.duration.toFloat()
                } else 1f
                count += (word.text.length * wordProgress.coerceIn(0f, 1f)).toInt()
                break
            } else {
                break
            }
        }
        return count.coerceAtMost(text.length)
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

    fun findCurrentLine(positionMs: Long): Int {
        if (lines.isEmpty()) return -1
        val adjusted = positionMs + offset
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeStamp <= adjusted) {
                index = i
            } else {
                break
            }
        }
        return index
    }

    fun getLine(index: Int): LyricLine? {
        return if (index in lines.indices) lines[index] else null
    }
}
