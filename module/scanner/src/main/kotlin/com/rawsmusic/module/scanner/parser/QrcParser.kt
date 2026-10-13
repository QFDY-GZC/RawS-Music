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

            val words = parseQrcTimedWords(contentPart)
            val textBuilder = StringBuilder().apply {
                words.forEach { append(it.text) }
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

private val QRC_WORD_TAG_REGEX = Regex("""\((\d+),(\d+)\)""")

/**
 * QQ QRC stores a word's `(start,duration)` tag *after* the text it times:
 * `word(start,duration)next(start,duration)`.
 *
 * Older RawSMusic code consumed the text after each tag, shifting every timing slot one word to
 * the right. A long duration therefore became attached to the following visible word, making its
 * glow appear early and its karaoke sweep look already completed when that word was actually sung.
 *
 * Keep a compatibility lane for non-standard prefix-tag files that start directly with a tag.
 */
internal fun parseQrcTimedWords(contentPart: String): List<LyricWord> {
    val matches = QRC_WORD_TAG_REGEX.findAll(contentPart).toList()
    if (matches.isEmpty()) return emptyList()

    val prefixTagged = matches.first().range.first == 0
    val words = ArrayList<LyricWord>(matches.size)

    if (prefixTagged) {
        matches.forEachIndexed { index, match ->
            val begin = match.groupValues[1].toLongOrNull() ?: return@forEachIndexed
            val duration = match.groupValues[2].toLongOrNull()?.coerceAtLeast(1L) ?: return@forEachIndexed
            val textStart = match.range.last + 1
            val textEnd = matches.getOrNull(index + 1)?.range?.first ?: contentPart.length
            val wordText = contentPart.substring(textStart, textEnd)
            if (wordText.isNotEmpty()) {
                words += LyricWord(
                    begin = begin,
                    end = begin + duration,
                    text = wordText,
                )
            }
        }
        return words
    }

    var textStart = 0
    matches.forEach { match ->
        val begin = match.groupValues[1].toLongOrNull()
        val duration = match.groupValues[2].toLongOrNull()?.coerceAtLeast(1L)
        val wordText = contentPart.substring(textStart, match.range.first)
        if (begin != null && duration != null && wordText.isNotEmpty()) {
            words += LyricWord(
                begin = begin,
                end = begin + duration,
                text = wordText,
            )
        }
        textStart = match.range.last + 1
    }

    // Preserve an untimed suffix (punctuation / provider decoration) with the final timed word.
    if (textStart < contentPart.length && words.isNotEmpty()) {
        val suffix = contentPart.substring(textStart)
        if (suffix.isNotEmpty()) {
            val last = words.last()
            words[words.lastIndex] = last.copy(text = last.text + suffix)
        }
    }
    return words
}
