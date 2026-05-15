package com.rawsmusic.module.scanner

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

    private data class RawLine(
        val timeStamp: Long,
        val text: String,
        val words: List<LyricWord> = emptyList()
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

            val timeStamps = mutableListOf<Long>()
            var textStart = 0
            val matcher = TIME_PATTERN.matcher(trimmed)

            while (matcher.find()) {
                timeStamps.add(parseTimeStamp(matcher))
                textStart = matcher.end()
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

        val mergedLines = mergeSameTimeStamp(rawLines)
        return LyricData(lines = mergedLines, offset = offset)
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
            var j = i + 1

            val sameTimeLines = mutableListOf<RawLine>()
            while (j < rawLines.size && rawLines[j].timeStamp == current.timeStamp) {
                sameTimeLines.add(rawLines[j])
                j++
            }

            for (extra in sameTimeLines) {
                val extraText = extra.text
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

            result.add(LyricLine(
                timeStamp = current.timeStamp,
                text = current.text,
                translation = translation,
                romanization = romanization,
                words = current.words
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
}
