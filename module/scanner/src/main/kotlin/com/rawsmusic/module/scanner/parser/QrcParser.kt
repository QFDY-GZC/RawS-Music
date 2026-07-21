package com.rawsmusic.module.scanner.parser

import android.util.Log
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.scanner.parser.LyricParserBase.applyWordSpacing

object QrcParser {

    private const val TAG = "QrcParser"
    private val offsetRegex = Regex("""\[offset:(-?\d+)]""", RegexOption.IGNORE_CASE)
    private val metaRegex = Regex("""\[(ti|ar|al|by|offset):.*]""", RegexOption.IGNORE_CASE)
    private val qrcLineRegex = Regex("""\[(\d+),(\d+)\]""")
    private val qrcWordRegex = Regex("""\((\d+),(\d+)\)([^(]*)""")

    fun isQrc(content: String): Boolean {
        val trimmed = content.trimStart()
        return (trimmed.startsWith("[offset:") && trimmed.contains("@@")) || trimmed.contains("&#")
    }

    fun isQrcFile(file: java.io.File): Boolean {
        return file.extension.equals("qrc", ignoreCase = true)
    }

    fun parse(content: String): LyricData {
        val decoded = LyricTextNormalizer.decodeEntities(content)

        val lines = decoded.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return LyricData()

        var offset = 0L
        val result = mutableListOf<LyricLine>()

        for (line in lines) {
            val trimmed = line.trim()

            val offsetMatch = offsetRegex.find(trimmed)
            if (offsetMatch != null) {
                offset = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            if (metaRegex.matches(trimmed)) continue

            val lineMatch = qrcLineRegex.find(trimmed)
            if (lineMatch == null) continue

            val lineBegin = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = lineMatch.groupValues[2].toLongOrNull() ?: continue
            val lineEnd = lineBegin + lineDuration

            val afterLineTag = lineMatch.range.last + 1
            val contentPart = if (afterLineTag < trimmed.length) trimmed.substring(afterLineTag) else ""

            val wordMatches = qrcWordRegex.findAll(contentPart).toList()
            val words = mutableListOf<LyricWord>()
            val textBuilder = StringBuilder()

            for (wm in wordMatches) {
                val wordBegin = wm.groupValues[1].toLongOrNull() ?: 0L
                val wordDuration = wm.groupValues[2].toLongOrNull() ?: 0L
                val wordText = wm.groupValues[3]
                if (wordText.isNotEmpty()) {
                    words.add(LyricWord(begin = wordBegin, end = wordBegin + wordDuration, text = wordText))
                    textBuilder.append(wordText)
                }
            }

            val lineText = if (textBuilder.isEmpty()) contentPart.trim() else textBuilder.toString().trim()
            if (lineText.isEmpty()) continue

            val spacedWords = applyWordSpacing(words)
            val spacedText = spacedWords.joinToString("") { it.text }.trim()

            result.add(LyricLine(
                timeStamp = lineBegin,
                text = spacedText.ifBlank { lineText },
                endTime = lineEnd,
                words = spacedWords
            ))
        }

        Log.d(TAG, "QRC: ${result.size} lines, offset=$offset")
        return LyricTextNormalizer.normalize(LyricData(lines = result, offset = offset))
    }
}
