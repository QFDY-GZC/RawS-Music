package com.rawsmusic.module.scanner.parser

import android.util.Log
import android.util.Base64
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.scanner.parser.LyricParserBase.applyWordSpacing
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import org.json.JSONObject

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

            LyricTextNormalizer.normalize(parseKrcText(decompressed))
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
        val metadata = decodeLanguageMetadata(
            lines.firstOrNull { it.trimStart().startsWith("[language:", ignoreCase = true) }
        )

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

        val enriched = result.mapIndexed { index, line ->
            line.copy(
                translation = metadata.translations.getOrNull(index).orEmpty(),
                romanization = metadata.phonetics.getOrNull(index)
                    ?.filter { it.isNotBlank() }
                    ?.joinToString(" ")
                    .orEmpty()
            )
        }
        Log.d(
            TAG,
            "KRC: ${enriched.size} lines, offset=$offset, " +
                "translations=${metadata.translations.size}, phonetics=${metadata.phonetics.size}"
        )
        return LyricData(lines = enriched, offset = offset)
    }

    private data class LanguageMetadata(
        val translations: List<String> = emptyList(),
        val phonetics: List<List<String>> = emptyList()
    )

    private fun decodeLanguageMetadata(header: String?): LanguageMetadata {
        if (header.isNullOrBlank()) return LanguageMetadata()
        return runCatching {
            val encoded = header.substringAfter(':').substringBeforeLast(']').trim()
            if (encoded.isEmpty()) return@runCatching LanguageMetadata()
            val root = JSONObject(String(Base64.decode(encoded, Base64.DEFAULT), Charsets.UTF_8))
            val content = root.optJSONArray("content") ?: return@runCatching LanguageMetadata()
            val translations = mutableListOf<String>()
            val phonetics = mutableListOf<List<String>>()
            for (index in 0 until content.length()) {
                val block = content.optJSONObject(index) ?: continue
                val rows = block.optJSONArray("lyricContent") ?: continue
                when (block.optInt("type", -1)) {
                    1 -> for (rowIndex in 0 until rows.length()) {
                        val row = rows.optJSONArray(rowIndex) ?: continue
                        translations += buildString {
                            for (partIndex in 0 until row.length()) append(row.optString(partIndex))
                        }
                    }
                    0 -> for (rowIndex in 0 until rows.length()) {
                        val row = rows.optJSONArray(rowIndex) ?: continue
                        val linePhonetics = mutableListOf<String>()
                        for (partIndex in 0 until row.length()) {
                            val syllable = row.optJSONArray(partIndex)
                            if (syllable != null) {
                                linePhonetics.add(buildString {
                                    for (valueIndex in 0 until syllable.length()) {
                                        append(syllable.optString(valueIndex))
                                    }
                                })
                            } else {
                                linePhonetics.add(row.optString(partIndex))
                            }
                        }
                        phonetics.add(linePhonetics)
                    }
                }
            }
            LanguageMetadata(translations = translations, phonetics = phonetics)
        }.onFailure { error ->
            Log.w(TAG, "Unable to decode KRC language metadata", error)
        }.getOrDefault(LanguageMetadata())
    }
}
