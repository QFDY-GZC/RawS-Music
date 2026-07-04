package com.rawsmusic.module.scanner.parser

import android.util.Log
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.scanner.parser.LyricParserBase.applyWordSpacing
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

object KrcParser {

    private const val TAG = "KrcParser"
    private val offsetRegex = Regex("""\[offset:(-?\d+)]""", RegexOption.IGNORE_CASE)
    private val krcLineRegex = Regex("""\[(\d+),(\d+)\]""")
    private val krcWordRegex = Regex("""<(\d+),(\d+),(\d+)>([^<]*)""")

    fun isKrc(bytes: ByteArray): Boolean {
        return bytes.size >= 4 && String(bytes, 0, 4, Charsets.US_ASCII) == "krc1"
    }

    fun isKrcFile(file: java.io.File): Boolean {
        return file.extension.equals("krc", ignoreCase = true)
    }

    fun parse(bytes: ByteArray): LyricData {
        return try {
            if (!isKrc(bytes)) return LyricData()

            val compressed = bytes.copyOfRange(4, bytes.size)
            val decompressor = Inflater()
            decompressor.setInput(compressed)
            val buffer = ByteArray(65536)
            val output = ByteArrayOutputStream()
            var resultLength = decompressor.inflate(buffer)
            while (resultLength > 0) {
                output.write(buffer, 0, resultLength)
                resultLength = decompressor.inflate(buffer)
            }
            decompressor.end()
            val decompressed = output.toString("UTF-8")

            parseKrcText(decompressed)
        } catch (e: Exception) {
            Log.e(TAG, "KRC decompression failed", e)
            LyricData()
        }
    }

    private fun parseKrcText(text: String): LyricData {
        val lines = text.lines().filter { it.isNotBlank() }
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

            val lineMatch = krcLineRegex.find(trimmed)
            if (lineMatch == null) continue

            val lineBegin = lineMatch.groupValues[1].toLongOrNull() ?: continue
            val lineDuration = lineMatch.groupValues[2].toLongOrNull() ?: continue
            val lineEnd = lineBegin + lineDuration

            val afterLineTag = lineMatch.range.last + 1
            val contentPart = if (afterLineTag < trimmed.length) trimmed.substring(afterLineTag) else ""

            val wordMatches = krcWordRegex.findAll(contentPart).toList()
            val words = mutableListOf<LyricWord>()
            val textBuilder = StringBuilder()

            for (wm in wordMatches) {
                val wordBegin = wm.groupValues[1].toLongOrNull() ?: 0L
                val wordDuration = wm.groupValues[2].toLongOrNull() ?: 0L
                val wordText = wm.groupValues[4]
                if (wordText.isNotEmpty()) {
                    words.add(LyricWord(begin = wordBegin, end = wordBegin + wordDuration, text = wordText))
                    textBuilder.append(wordText)
                }
            }

            val lineText = textBuilder.toString().trim()
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

        Log.d(TAG, "KRC: ${result.size} lines, offset=$offset")
        return LyricData(lines = result, offset = offset)
    }
}
