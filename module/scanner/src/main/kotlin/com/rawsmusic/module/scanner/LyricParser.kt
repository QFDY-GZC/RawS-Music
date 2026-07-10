package com.rawsmusic.module.scanner

import android.util.Log
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import java.io.BufferedReader
import java.io.File
import java.io.StringReader
import java.util.regex.Pattern

object LyricParser {

    private val TIME_PATTERN = Pattern.compile(
        "\\[(\\d{2}):(\\d{2})[.:](\\d{2,3})]"
    )
    private val OFFSET_PATTERN = Pattern.compile(
        "\\[offset:(-?\\d+)]"
    )
    private val WORD_TIME_PATTERN = Pattern.compile(
        "<(\\d{1,2}):(\\d{2})[.:](\\d{2,3})>"
    )
    private val TIMESTAMP_ONLY_PATTERN = Pattern.compile(
        "^\\d+(?::\\d+){0,2}(?:[.:]\\d+)?$"
    )

    private fun parseTimeStamp(m: java.util.regex.Matcher): Long {
        val minutes = m.group(1)?.toLongOrNull() ?: 0L
        val seconds = m.group(2)?.toLongOrNull() ?: 0L
        val millisStr = m.group(3) ?: "0"
        val millis = if (millisStr.length == 2) {
            (millisStr.toLongOrNull() ?: 0L) * 10
        } else {
            millisStr.toLongOrNull() ?: 0L
        }
        return minutes * 60000 + seconds * 1000 + millis
    }

    private fun parseTimeStamp(m: java.util.regex.MatchResult): Long {
        val minutes = m.group(1)?.toLongOrNull() ?: 0L
        val seconds = m.group(2)?.toLongOrNull() ?: 0L
        val millisStr = m.group(3) ?: "0"
        val millis = if (millisStr.length == 2) {
            (millisStr.toLongOrNull() ?: 0L) * 10
        } else {
            millisStr.toLongOrNull() ?: 0L
        }
        return minutes * 60000 + seconds * 1000 + millis
    }

    private data class RawLine(
        val timeStamp: Long,
        val text: String,
        val words: List<LyricWord> = emptyList(),
        val endTime: Long = -1L
    )

    private fun parseEnhancedText(rawText: String): Pair<String, List<LyricWord>> {
        val wordMatcher = WORD_TIME_PATTERN.matcher(rawText)
        if (!wordMatcher.find()) {
            return Pair(rawText.trim(), emptyList())
        }

        val segments = mutableListOf<Pair<Long, String>>()
        wordMatcher.reset()
        while (wordMatcher.find()) {
            val time = parseTimeStamp(wordMatcher)
            val afterTag = wordMatcher.end()
            val nextTag = rawText.indexOf('<', afterTag)
            val charText = if (nextTag > afterTag) {
                rawText.substring(afterTag, nextTag)
            } else if (nextTag < 0) {
                rawText.substring(afterTag)
            } else {
                ""
            }
            if (charText.isNotEmpty()) {
                segments.add(Pair(time, charText))
            }
        }

        val cleanText = segments.joinToString("") { it.second }.trim()

        val words = mutableListOf<LyricWord>()
        for (i in segments.indices) {
            val begin = segments[i].first
            val end = if (i + 1 < segments.size) segments[i + 1].first else begin + 1000L
            words.add(LyricWord(begin = begin, end = end, text = segments[i].second))
        }

        return Pair(cleanText, words)
    }

    private fun isWordByWordLine(trimmed: String, matches: List<java.util.regex.MatchResult>): Boolean {
        if (matches.size < 2) return false
        val first = matches[0]
        val afterFirst = first.end()
        if (afterFirst >= trimmed.length) return false
        val nextChar = trimmed[afterFirst]
        if (Character.isWhitespace(nextChar) || nextChar == '[') return false
        var textBetweenCount = 0
        for (i in 0 until matches.size - 1) {
            val currentEnd = matches[i].end()
            val nextStart = matches[i + 1].start()
            if (nextStart > currentEnd) {
                val between = trimmed.substring(currentEnd, nextStart).trim()
                if (between.isNotEmpty()) textBetweenCount++
            }
        }
        return textBetweenCount >= 1
    }

    private fun parseWordByWordLine(trimmed: String, matches: List<java.util.regex.MatchResult>): RawLine? {
        val rawSegments = mutableListOf<Pair<Long, String>>()
        var lastEndTimestamp = -1L

        for (i in matches.indices) {
            val m = matches[i]
            val ts = parseTimeStamp(m)
            val afterTag = m.end()
            val nextTagStart = if (i + 1 < matches.size) matches[i + 1].start() else trimmed.length
            val text = if (afterTag < nextTagStart) trimmed.substring(afterTag, nextTagStart) else ""
            if (text.isNotEmpty()) {
                rawSegments.add(Pair(ts, text))
            } else {
                lastEndTimestamp = ts
            }
        }

        if (rawSegments.isEmpty()) return null

        val mergedSegments = mutableListOf<Pair<Long, String>>()
        for ((ts, text) in rawSegments) {
            if (text.trim().isEmpty() && mergedSegments.isNotEmpty()) {
                val prev = mergedSegments.removeLast()
                mergedSegments.add(Pair(prev.first, prev.second + text))
            } else {
                mergedSegments.add(Pair(ts, text))
            }
        }

        val words = mutableListOf<LyricWord>()
        for (i in mergedSegments.indices) {
            val (begin, text) = mergedSegments[i]
            val coreText = text.trimEnd()
            if (coreText.isEmpty()) continue
            val hasTrailingSpace = text.length > coreText.length
            val needsSpace = !isCjkText(coreText) && (hasTrailingSpace || i + 1 < mergedSegments.size)
            val finalText = if (needsSpace && i < mergedSegments.lastIndex) "$coreText " else coreText
            words.add(LyricWord(begin = begin, end = 0L, text = finalText))
        }

        if (words.isEmpty()) return null

        for (i in words.indices) {
            if (i + 1 < words.size) {
                words[i] = words[i].copy(end = words[i + 1].begin)
            } else {
                val end = if (lastEndTimestamp > words[i].begin) {
                    lastEndTimestamp
                } else {
                    words[i].begin + estimateWordDuration(words[i].text)
                }
                words[i] = words[i].copy(end = end)
            }
        }

        val lineText = words.joinToString("") { it.text }.trim()
        val lineBegin = words.first().begin
        val lineEnd = words.last().end

        return RawLine(
            timeStamp = lineBegin,
            text = lineText,
            words = words,
            endTime = lineEnd
        )
    }

    private fun applyWordSpacing(words: List<LyricWord>): MutableList<LyricWord> {
        return words.map { word ->
            val text = word.text
            val needSpace = when {
                text.isBlank() -> false
                isCjkText(text) -> false
                else -> true
            }
            word.copy(text = if (needSpace) "$text " else text)
        }.toMutableList()
    }

    private fun isCjkText(text: String): Boolean {
        return text.any { ch ->
            val block = Character.UnicodeBlock.of(ch)
            block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                    block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                    block == Character.UnicodeBlock.HIRAGANA ||
                    block == Character.UnicodeBlock.KATAKANA
        }
    }

    private fun estimateWordDuration(text: String): Long {
        val cleaned = text.replace(Regex("[ \\t\\r\\n]+"), " ").trim()
        return (cleaned.length * 150L).coerceIn(200L, 2000L)
    }

    fun parseFromFile(lrcFile: File): LyricData {
        if (!lrcFile.exists() || !lrcFile.isFile) return LyricData()
        return parseFromString(lrcFile.readText(charset = Charsets.UTF_8))
    }

    fun parseFromString(lrcContent: String): LyricData {
        val rawLines = mutableListOf<RawLine>()
        var offset = 0L

        val reader = BufferedReader(StringReader(lrcContent))
        var line: String? = reader.readLine()

        while (line != null) {
            val trimmed = line.trim()
            if (trimmed.isEmpty()) {
                line = reader.readLine()
                continue
            }

            val offsetMatcher = OFFSET_PATTERN.matcher(trimmed)
            if (offsetMatcher.find()) {
                offset = offsetMatcher.group(1)?.toLongOrNull() ?: 0L
                line = reader.readLine()
                continue
            }

            if (trimmed.startsWith("[ti:") || trimmed.startsWith("[ar:") ||
                trimmed.startsWith("[al:") || trimmed.startsWith("[by:") ||
                trimmed.startsWith("[re:") || trimmed.startsWith("[ve:")
            ) {
                line = reader.readLine()
                continue
            }

            val matcher = TIME_PATTERN.matcher(trimmed)
            val matches = mutableListOf<java.util.regex.MatchResult>()
            while (matcher.find()) {
                matches.add(matcher.toMatchResult())
            }

            if (matches.isEmpty()) {
                line = reader.readLine()
                continue
            }

            if (isWordByWordLine(trimmed, matches)) {
                val rawLine = parseWordByWordLine(trimmed, matches)
                if (rawLine != null) {
                    rawLines.add(rawLine)
                }
                line = reader.readLine()
                continue
            }

            val timeStamps = mutableListOf<Long>()
            var textStart = 0
            for (m in matches) {
                timeStamps.add(parseTimeStampFromResult(m))
                textStart = m.end()
            }

            if (timeStamps.isNotEmpty()) {
                val rawText = trimmed.substring(textStart)
                val (cleanText, words) = parseEnhancedText(rawText)
                for (ts in timeStamps) {
                    rawLines.add(RawLine(timeStamp = ts, text = cleanText, words = words))
                }
            }

            line = reader.readLine()
        }

        rawLines.sortBy { it.timeStamp }

        val filteredLines = rawLines.filter { raw ->
            !isCopyrightLine(raw.text) && !isCreditsLine(raw.text)
        }

        val mergedLines = mergeSameTimeStamp(filteredLines)
        val translatedLines = mergeCloseTimestampTranslations(mergedLines)
        val finalLines = fillEndTimes(translatedLines)
        return LyricData(lines = finalLines, offset = offset)
    }

    private fun parseTimeStampFromResult(m: java.util.regex.MatchResult): Long {
        val minutes = m.group(1)?.toLongOrNull() ?: 0L
        val seconds = m.group(2)?.toLongOrNull() ?: 0L
        val millisStr = m.group(3) ?: "0"
        val millis = if (millisStr.length == 2) {
            (millisStr.toLongOrNull() ?: 0L) * 10
        } else {
            millisStr.toLongOrNull() ?: 0L
        }
        return minutes * 60000 + seconds * 1000 + millis
    }

    fun findLrcFile(audioFilePath: String): File? {
        val audioFile = File(audioFilePath)
        val parent = audioFile.parentFile ?: return null
        val nameWithoutExt = audioFile.nameWithoutExtension

        val lrcNames = listOf(
            "$nameWithoutExt.lrc",
            "$nameWithoutExt.LRC",
            "${nameWithoutExt.lowercase()}.lrc",
            "${nameWithoutExt.uppercase()}.LRC"
        )

        for (lrcName in lrcNames) {
            val lrcFile = File(parent, lrcName)
            if (lrcFile.exists() && lrcFile.isFile) return lrcFile
        }

        val lrcDir = File(parent, ".lyrics")
        if (lrcDir.exists() && lrcDir.isDirectory) {
            for (lrcName in lrcNames) {
                val lrcFile = File(lrcDir, lrcName)
                if (lrcFile.exists() && lrcFile.isFile) return lrcFile
            }
        }

        return null
    }

    private fun mergeSameTimeStamp(rawLines: List<RawLine>): List<LyricLine> {
        if (rawLines.isEmpty()) return emptyList()
        val result = mutableListOf<LyricLine>()
        var i = 0
        while (i < rawLines.size) {
            val current = rawLines[i]
            var translation = ""
            var romanization = ""
            var bestEndTime = current.endTime
            var bestWords = current.words
            var j = i + 1

            val sameTimeLines = mutableListOf<RawLine>()
            while (j < rawLines.size && rawLines[j].timeStamp == current.timeStamp) {
                sameTimeLines.add(rawLines[j])
                j++
            }

            for (extra in sameTimeLines) {
                val extraText = extra.text
                if (extra.endTime > bestEndTime) bestEndTime = extra.endTime
                when {
                    isRomajiText(extraText) && !isRomajiText(current.text) -> {
                        romanization = extraText
                    }
                    isCJKTranslation(current.text, extraText) -> {
                        translation = extraText
                    }
                    sameTimeLines.size == 1 && current.text != extraText -> {
                        translation = extraText
                    }
                }
            }

            if (current.text.isBlank() && sameTimeLines.isNotEmpty()) {
                val primary = sameTimeLines.firstOrNull { it.text.isNotBlank() }
                if (primary != null) {
                    val otherLines = sameTimeLines.filter { it != primary }
                    val transFromOther = otherLines.firstOrNull {
                        it.text != primary.text && isCJKTranslation(primary.text, it.text)
                    }?.text ?: otherLines.firstOrNull { it.text != primary.text }?.text ?: ""
                    result.add(LyricLine(
                        timeStamp = primary.timeStamp,
                        text = primary.text,
                        translation = transFromOther,
                        romanization = romanization,
                        words = primary.words,
                        endTime = bestEndTime
                    ))
                    i = j
                    continue
                }
            }

            result.add(LyricLine(
                timeStamp = current.timeStamp,
                text = current.text,
                translation = translation,
                romanization = romanization,
                words = bestWords,
                endTime = bestEndTime
            ))
            i = j
        }
        return result
    }

    private fun isCJKText(text: String): Boolean {
        if (text.isBlank()) return false
        var cjkCount = 0
        for (c in text) {
            val block = Character.UnicodeBlock.of(c)
            if (block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                block == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS ||
                block == Character.UnicodeBlock.HIRAGANA ||
                block == Character.UnicodeBlock.KATAKANA) {
                cjkCount++
            }
        }
        return cjkCount * 2 > text.length
    }

    private fun isRomajiText(text: String): Boolean {
        if (text.isBlank()) return false
        return Regex("^[a-zA-Z\\s'.\\-]+$").matches(text)
    }

    private fun mergeCloseTimestampTranslations(lines: List<LyricLine>): List<LyricLine> {
        if (lines.size < 3) return lines

        val result = mutableListOf<LyricLine>()
        var i = 0
        while (i < lines.size) {
            val current = lines[i]

            if (i + 1 < lines.size && i + 2 < lines.size) {
                val nextLine = lines[i + 1]
                val nextNextLine = lines[i + 2]

                val gapToPrev = nextLine.timeStamp - current.timeStamp
                val gapToNext = nextNextLine.timeStamp - nextLine.timeStamp

                if (gapToPrev > 500 && gapToNext in 0..100) {
                    val mergedLine = if (current.translation.isEmpty()) {
                        current.copy(translation = nextLine.text)
                    } else {
                        current
                    }
                    result.add(mergedLine)
                    i += 2
                    continue
                }
            }

            result.add(current)
            i++
        }

        if (result.size < lines.size) {
            Log.d("LyricDebug", "  mergeCloseTimestampTranslations: ${lines.size} → ${result.size} lines")
        }
        return result
    }

    private fun isCJKTranslation(original: String, candidate: String): Boolean {
        if (candidate == original) return false
        val origCJK = isCJKText(original)
        val candCJK = isCJKText(candidate)
        val candRomaji = isRomajiText(candidate)
        if (candRomaji) return false
        if (origCJK && !candCJK) return true
        if (origCJK && candCJK && original != candidate) return true
        if (!origCJK && candCJK) return true
        return false
    }

    private fun fillEndTimes(lines: List<LyricLine>): List<LyricLine> {
        val result = lines.toMutableList()
        for (k in result.indices) {
            if (result[k].endTime <= 0L) {
                val nextTs = if (k + 1 < result.size) result[k + 1].timeStamp else result[k].timeStamp + 5000L
                result[k] = result[k].copy(endTime = nextTs)
            }
        }
        return result
    }

    private fun isCopyrightLine(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        val patterns = listOf(
            "著作权", "版权", "copyright", "©", "®",
            "享有本翻译", "qq音乐享有", "网易云音乐享有",
            "提供歌词", "歌词来源", "来自.*歌词",
            "仅供参考", "请勿用于商业"
        )
        return patterns.any { p -> Regex(p, RegexOption.IGNORE_CASE).containsMatchIn(t) }
    }

    private fun isCreditsLine(text: String): Boolean {
        val t = text.trim()
        if (t.isBlank()) return false
        if (!t.contains("by") && !t.contains("By") && !t.contains("BY")) return false
        val creditsPattern = Regex(
            """(?:Written|Composed|Arranged|Lyrics|Music|Produced|Mixed|Remixed)\s*(?:by|By)\s*""",
            RegexOption.IGNORE_CASE
        )
        return creditsPattern.containsMatchIn(t)
    }

    fun isLrc(content: String): Boolean {
        val trimmed = content.trimStart()
        return TIME_PATTERN.matcher(trimmed).find()
    }
}
