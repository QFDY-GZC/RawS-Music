package com.rawsmusic.module.scanner

import android.util.Log
import android.util.Xml
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.core.common.model.LyricData
import org.xmlpull.v1.XmlPullParser
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.FileInputStream
import java.io.StringReader

object LyricReader {

    fun readLyrics(songPath: String): LyricData {
        val songName = File(songPath).name
        Log.d("LyricDebug", "readLyrics: $songName")

        val embedded = readEmbeddedLyricsWithFfmpeg(songPath)
        if (!embedded.isEmpty) {
            Log.d("LyricDebug", "  embedded lyrics: ${embedded.lines.size} lines")
            return embedded
        } else {
            Log.d("LyricDebug", "  no embedded lyrics")
        }

        val lrcFile = findLrcFile(songPath)
        if (lrcFile != null) {
            Log.d("LyricDebug", "  LRC file found: ${lrcFile.name}")
            val parsed = parseLrcFile(lrcFile)
            if (!parsed.isEmpty) {
                Log.d("LyricDebug", "  LRC parsed: ${parsed.lines.size} lines")
                return parsed
            } else {
                Log.d("LyricDebug", "  LRC parsed empty")
            }
        } else {
            Log.d("LyricDebug", "  no LRC file")
        }

        val ttmlFile = findTtmlFile(songPath)
        if (ttmlFile != null) {
            Log.d("LyricDebug", "  TTML file found: ${ttmlFile.name}")
            val parsed = parseTtmlFile(ttmlFile)
            if (!parsed.isEmpty) {
                Log.d("LyricDebug", "  TTML parsed: ${parsed.lines.size} lines")
                return parsed
            } else {
                Log.d("LyricDebug", "  TTML parsed empty")
            }
        } else {
            Log.d("LyricDebug", "  no TTML file")
        }

        Log.d("LyricDebug", "  no lyrics found for: $songName")
        return LyricData()
    }

    private fun findLrcFile(songPath: String): File? {
        val songFile = File(songPath)
        val dir = songFile.parentFile ?: return null
        val baseName = songFile.nameWithoutExtension
        val candidates = listOf(
            File(dir, "$baseName.lrc"), File(dir, "$baseName.LRC"),
            File(dir, "${baseName.lowercase()}.lrc"), File(dir, "${baseName.uppercase()}.lrc")
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    private fun findTtmlFile(songPath: String): File? {
        val songFile = File(songPath)
        val dir = songFile.parentFile ?: return null
        val baseName = songFile.nameWithoutExtension
        val candidates = listOf(
            File(dir, "$baseName.ttml"), File(dir, "$baseName.TTML"),
            File(dir, "$baseName.dfxp"), File(dir, "$baseName.xml")
        )
        return candidates.firstOrNull { it.exists() && it.canRead() }
    }

    private fun readEmbeddedLyricsWithFfmpeg(songPath: String): LyricData {
        return try {
            val info = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.getMediaInfo(songPath) ?: return LyricData()

            var lyricsString: String? = null
            val candidateKeys = setOf("LYRICS", "LYRICS-ENG", "UNSYNCEDLYRICS", "TXXX:LYRICS")

            for ((key, value) in info) {
                val upperKey = key.uppercase()
                if (candidateKeys.contains(upperKey) || upperKey.contains("LYRIC")) {
                    if (value.isNotBlank()) {
                        lyricsString = value
                        break
                    }
                }
            }

            if (lyricsString == null) {
                Log.d("LyricDebug", "FFprobe found no lyrics in traversed tags")
                return LyricData()
            }

            Log.d("LyricDebug", "FFprobe successfully extracted lyrics (${lyricsString.length} chars)")
            Log.d("LyricDebug", "  embedded lyrics preview: ${lyricsString.take(300).replace("\n", "\\n")}")
            return if (lyricsString.trimStart().startsWith("<")) {
                parseTtmlContent(lyricsString)
            } else {
                parseLrcContent(lyricsString)
            }
        } catch (e: Exception) {
            Log.e("LyricDebug", "FFprobeKit general exception", e)
            LyricData()
        }
    }

    private fun parsePlainTextLyrics(lyrics: String): LyricData {
        val lines = lyrics.lines().filter { it.isNotBlank() }
        if (lines.isEmpty()) return LyricData()
        return LyricData(lines = lines.map {
            LyricLine(timeStamp = 0L, text = it.trim())
        })
    }

    private fun parseLrcFile(file: File): LyricData {
        return try {
            val content = BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8")).readText()
            Log.d("LyricDebug", "  LRC file content preview (${content.length} chars): ${content.take(300).replace("\n", "\\n")}")
            parseLrcContent(content)
        } catch (_: Exception) { LyricData() }
    }

    private val enhancedWordRegex = Regex("""<(\d{1,2}):(\d{2})[.:](\d{2,3})>""")

    private fun parseEnhancedText(rawText: String): Pair<String, List<LyricWord>> {
        val angleMatches = enhancedWordRegex.findAll(rawText).toList()
        if (angleMatches.isNotEmpty()) {
            val segments = mutableListOf<Triple<Long, String, Long>>()
            for (i in angleMatches.indices) {
                val m = angleMatches[i]
                val mins = m.groupValues[1].toIntOrNull() ?: 0
                val secs = m.groupValues[2].toIntOrNull() ?: 0
                val msStr = m.groupValues[3]
                val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
                val begin = mins * 60_000L + secs * 1000L + ms.toLong()

                val afterTag = m.range.last + 1
                val nextTagStart = if (i + 1 < angleMatches.size) angleMatches[i + 1].range.first else rawText.length
                val charText = rawText.substring(afterTag, nextTagStart)
                if (charText.isNotEmpty()) {
                    segments.add(Triple(begin, charText, 0L))
                }
            }

            for (i in segments.indices) {
                val end = if (i + 1 < segments.size) segments[i + 1].first else segments[i].first + 1000L
                segments[i] = Triple(segments[i].first, segments[i].second, end)
            }

            val cleanText = segments.joinToString("") { it.second }.trim()
            val words = segments.map { LyricWord(begin = it.first, end = it.third, text = it.second) }
            return Pair(cleanText, words)
        }

        return Pair(rawText.trim(), emptyList())
    }

    private fun parseLrcContent(content: String): LyricData {
        val rawLines = content.lines().filter { it.isNotBlank() }
        Log.d("LyricDebug", "parseLrcContent: ${rawLines.size} non-blank lines")
        if (rawLines.isEmpty()) return LyricData()

        val lrcRegex = Regex("""\[(\d{2}):(\d{2})[.:](\d{2,3})]""")
        val rawData = mutableListOf<RawLine>()
        var skippedLines = 0
        for (line in rawLines) {
            val matches = lrcRegex.findAll(line).toList()
            if (matches.isEmpty()) {
                skippedLines++
                continue
            }

            // 检测是否为逐词时间戳格式：[00:00.000]Keeping[00:00.206] [00:00.235]Your...
            // 特征：第一个时间戳后紧跟非空白字符（即时间戳和单词之间没有空格）
            val isWordByWord = matches.size >= 2 && let {
                val afterFirst = matches.first().range.last + 1
                afterFirst < line.length && !line[afterFirst].isWhitespace()
            }

            if (isWordByWord) {
                val words = parseWordByWordLine(line, matches)
                if (words.isNotEmpty()) {
                    val lineText = words.joinToString("") { it.text }
                    val lineBegin = words.first().begin
                    val lineEnd = words.last().end
                    rawData.add(RawLine(lineBegin, lineText, endTime = lineEnd, words = words))
                }
                continue
            }

            val lastMatch = matches.last()
            val afterLast = lastMatch.range.last + 1
            val hasEndTimestamp = matches.size >= 2 && afterLast >= line.length - 1

            var endTime = -1L
            val beginMatches: List<MatchResult>
            val tail: String

            if (hasEndTimestamp) {
                val eMins = lastMatch.groupValues[1].toIntOrNull() ?: 0
                val eSecs = lastMatch.groupValues[2].toIntOrNull() ?: 0
                val eMsStr = lastMatch.groupValues[3]
                val eMs = if (eMsStr.length == 2) eMsStr.toInt() * 10 else eMsStr.toInt()
                endTime = eMins * 60_000L + eSecs * 1000L + eMs.toLong()
                beginMatches = matches.dropLast(1)
                val textStart = beginMatches.last().range.last + 1
                val textEnd = lastMatch.range.first
                tail = if (textStart < textEnd) line.substring(textStart, textEnd) else ""
                Log.d("LyricDebug", "  line with end ts: begin=${beginMatches.map { it.value }}, end=${lastMatch.value}, tail='$tail'")
            } else {
                beginMatches = matches
                val textStart = lastMatch.range.last + 1
                tail = if (textStart < line.length) line.substring(textStart) else ""
            }

            val (cleanText, words) = parseEnhancedText(tail)
            if (cleanText.isEmpty()) continue

            for (bm in beginMatches) {
                val mins = bm.groupValues[1].toIntOrNull() ?: continue
                val secs = bm.groupValues[2].toIntOrNull() ?: continue
                val msStr = bm.groupValues[3]
                val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
                val ts = mins * 60_000L + secs * 1000L + ms.toLong()
                rawData.add(RawLine(ts, cleanText, endTime = endTime, words = words))
            }
        }

        Log.d("LyricDebug", "parseLrcContent: $skippedLines skipped, ${rawData.size} raw lines with timestamps")
        if (rawData.isEmpty()) return LyricData()

        val mergedLines = mutableListOf<LyricLine>()
        var i = 0
        while (i < rawData.size) {
            val base = rawData[i]
            var translation = ""
            var romanization = ""
            var bestEndTime = base.endTime
            var j = i + 1
            while (j < rawData.size && rawData[j].ts == base.ts) {
                val extraText = rawData[j].text
                if (rawData[j].endTime > bestEndTime) bestEndTime = rawData[j].endTime
                when {
                    isRomajiText(extraText) && !isRomajiText(base.text) -> {
                        romanization = extraText
                    }
                    extraText != base.text && extraText != romanization -> {
                        if (translation.isEmpty()) translation = extraText
                    }
                }
                j++
            }
            mergedLines.add(LyricLine(
                timeStamp = base.ts,
                text = base.text,
                endTime = bestEndTime,
                translation = translation,
                romanization = romanization,
                words = base.words
            ))
            i = j
        }

        for (k in 0 until mergedLines.size - 1) {
            if (mergedLines[k].endTime <= 0L) {
                mergedLines[k] = mergedLines[k].copy(endTime = mergedLines[k + 1].timeStamp)
            }
        }
        if (mergedLines.isNotEmpty() && mergedLines.last().endTime <= 0L) {
            mergedLines[mergedLines.lastIndex] = mergedLines.last().copy(
                endTime = mergedLines.last().timeStamp + 5000L
            )
        }
        Log.d("LyricDebug", "  LRC: ${rawData.size} raw → ${mergedLines.size} merged")
        return LyricData(lines = mergedLines)
    }

    /** 解析逐词时间戳格式：[00:00.000]Keeping[00:00.206] [00:00.235]Your... */
    private fun parseWordByWordLine(line: String, matches: List<MatchResult>): List<LyricWord> {
        val words = mutableListOf<LyricWord>()
        for (i in matches.indices) {
            val m = matches[i]
            val mins = m.groupValues[1].toIntOrNull() ?: 0
            val secs = m.groupValues[2].toIntOrNull() ?: 0
            val msStr = m.groupValues[3]
            val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
            val begin = mins * 60_000L + secs * 1000L + ms.toLong()

            val afterTag = m.range.last + 1
            val nextTagStart = if (i + 1 < matches.size) matches[i + 1].range.first else line.length
            val text = if (afterTag < nextTagStart) line.substring(afterTag, nextTagStart) else ""

            if (text.isNotEmpty()) {
                words.add(LyricWord(begin = begin, end = 0L, text = text))
            }
        }
        // 填充 end 时间
        for (i in words.indices) {
            val end = if (i + 1 < words.size) words[i + 1].begin else words[i].begin + 1000L
            words[i] = words[i].copy(end = end)
        }
        return words
    }

    private fun isRomajiText(text: String): Boolean {
        if (text.isBlank()) return false
        // 允许空格、半角标点、全角标点、括号等常见罗马音中的字符
        val romajiPattern = Regex("""^[a-zA-Z\s'.\-？?！!「」『』()（）\[\]]+$""")
        if (!romajiPattern.matches(text)) return false
        // 必须包含至少一个字母，避免纯标点被误判
        return text.any { it.isLetter() }
    }

    private data class RawLine(val ts: Long, val text: String, val endTime: Long = -1L, val words: List<LyricWord> = emptyList())

    private fun parseKrcWords(text: String): List<LyricWord> {
        val regex = Regex("""<(\d+),(\d+)>([^<]*)""")
        val matches = regex.findAll(text).toList()
        if (matches.isEmpty()) return emptyList()
        return matches.map {
            val begin = it.groupValues[1].toLongOrNull() ?: 0L
            val duration = it.groupValues[2].toLongOrNull() ?: 0L
            LyricWord(begin = begin, end = begin + duration, text = it.groupValues[3])
        }
    }

    private fun parseTtmlFile(file: File): LyricData {
        return try {
            val content = BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8")).readText()
            parseTtmlContent(content)
        } catch (_: Exception) { LyricData() }
    }

    private fun parseTtmlContent(content: String): LyricData {
        return try {
            val parser = Xml.newPullParser()
            parser.setInput(StringReader(content))
            val ttmlLines = mutableListOf<LyricLine>()
            var currentLine: LyricLine? = null
            var currentWords = mutableListOf<LyricWord>()
            var wordBegin = 0L
            var wordEnd = 0L
            var spanText = StringBuilder()
            var romanizationText: String? = null
            var translationText: String? = null
            var isRomanizationSpan = false
            var isTranslationSpan = false

            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> when (parser.name) {
                        "p" -> {
                            val begin = parseTtmlTime(parser.getAttributeValue(null, "begin") ?: "00:00.000")
                            val end = parseTtmlTime(parser.getAttributeValue(null, "end") ?: "00:00.000")
                            currentLine = LyricLine(timeStamp = begin, text = "", endTime = end)
                            currentWords = mutableListOf()
                            romanizationText = null
                            translationText = null
                        }
                        "span" -> {
                            val role = parser.getAttributeValue("http://www.w3.org/ns/ttml#metadata", "role")
                                ?: parser.getAttributeValue(null, "ttm:role")
                            isRomanizationSpan = role == "x-romanization"
                            isTranslationSpan = role == "x-translation"
                            if (!isRomanizationSpan && !isTranslationSpan) {
                                val b = parser.getAttributeValue(null, "begin")
                                val e = parser.getAttributeValue(null, "end")
                                if (b != null) wordBegin = parseTtmlTime(b)
                                if (e != null) wordEnd = parseTtmlTime(e)
                            }
                            spanText = StringBuilder()
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val t = parser.text?.trim() ?: ""
                        if (t.isNotEmpty()) spanText.append(t)
                    }
                    XmlPullParser.END_TAG -> when (parser.name) {
                        "span" -> {
                            val txt = spanText.toString().trim()
                            if (txt.isEmpty()) {}
                            else if (isRomanizationSpan) romanizationText = txt
                            else if (isTranslationSpan) translationText = txt
                            else currentWords.add(LyricWord(begin = wordBegin, end = wordEnd, text = txt))
                        }
                        "p" -> {
                            currentLine?.let { line ->
                                val words = currentWords.toList()
                                val fullText = words.joinToString("") { it.text }
                                ttmlLines.add(line.copy(
                                    text = fullText,
                                    words = words,
                                    romanization = romanizationText ?: "",
                                    translation = translationText ?: ""
                                ))
                            }
                            currentLine = null
                        }
                    }
                }
                eventType = parser.next()
            }
            if (ttmlLines.isEmpty()) return LyricData()
            Log.d("LyricDebug", "parseTtmlContent: ${ttmlLines.size} lines parsed")
            LyricData(lines = ttmlLines)
        } catch (e: Exception) {
            Log.e("LyricDebug", "parseTtmlContent error", e)
            LyricData()
        }
    }

    private fun parseTtmlTime(time: String): Long {
        val parts = time.split(":")
        return when (parts.size) {
            3 -> {
                parts[0].toLongOrNull()?.let { h -> parts[1].toLongOrNull()?.let { m ->
                    (h * 3600_000L + m * 60_000L + (parts[2].replace(",", ".").toDoubleOrNull() ?: 0.0) * 1000).toLong()
                } } ?: 0L
            }
            2 -> {
                parts[0].toLongOrNull()?.let { m ->
                    (m * 60_000L + (parts[1].replace(",", ".").toDoubleOrNull() ?: 0.0) * 1000).toLong()
                } ?: 0L
            }
            1 -> ((parts[0].replace(",", ".").toDoubleOrNull() ?: 0.0) * 1000).toLong()
            else -> 0L
        }
    }
}
