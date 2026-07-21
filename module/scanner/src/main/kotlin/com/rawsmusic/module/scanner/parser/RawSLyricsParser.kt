package com.rawsmusic.module.scanner.parser

import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord

object RawSLyricsParser {

    fun parse(content: String): LyricData {
        val trimmed = content.trimStart()
        val data = when {
            isTtml(trimmed) -> parseTtml(content)
            isLyricifySyllable(trimmed) -> parseLyricifySyllable(content)
            isQrc(trimmed) -> parseQrc(content)
            isEnhancedLrc(trimmed) -> parseEnhancedLrc(content)
            isLrc(trimmed) -> parseLrc(content)
            else -> LyricData()
        }
        return LyricParserBase.applyLineBreakProtection(LyricTextNormalizer.normalize(data))
    }

    private fun isTtml(content: String): Boolean {
        return (content.startsWith("<?xml") && content.contains("<tt", ignoreCase = true)) ||
                content.startsWith("<tt", ignoreCase = true)
    }

    private fun isLyricifySyllable(content: String): Boolean {
        val detector = Regex("""[a-zA-Z]+\s*\(\d+,\d+\)""")
        val hasLrcTimestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""").containsMatchIn(content)
        return hasLrcTimestamp && detector.containsMatchIn(content)
    }

    private fun isQrc(content: String): Boolean {
        return (content.startsWith("[offset:") && content.contains("@@")) || content.contains("&#")
    }

    private fun isEnhancedLrc(content: String): Boolean {
        val hasLrcTimestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""").containsMatchIn(content)
        val hasWordMarker = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))>""").containsMatchIn(content)
        return hasLrcTimestamp && hasWordMarker
    }

    private fun isLrc(content: String): Boolean {
        return Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""").containsMatchIn(content)
    }

    // ==================== LRC Parsing ====================

    private val lrcTimestampRegex = Regex("""\[(\d{1,2}):(\d{2})[.:](\d{2,3})]""")
    private val offsetRegex = Regex("""\[offset:(-?\d+)]""", RegexOption.IGNORE_CASE)
    private val metaRegex = Regex("""\[(ti|ar|al|by|offset):.*]""", RegexOption.IGNORE_CASE)

    private fun parseLrc(content: String): LyricData {
        val rawLines = content.lines().filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return LyricData()

        var offset = 0L
        val rawData = mutableListOf<RawLine>()

        for (line in rawLines) {
            val trimmed = line.trim()

            val offsetMatch = offsetRegex.find(trimmed)
            if (offsetMatch != null) {
                offset = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            if (metaRegex.matches(trimmed)) continue

            val matches = lrcTimestampRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            if (isWordByWordLine(trimmed, matches)) {
                val result = parseWordByWordLine(trimmed, matches)
                if (result != null) {
                    rawData.add(result)
                }
                continue
            }

            val lastMatch = matches.last()
            val afterLast = lastMatch.range.last + 1
            val hasEndTimestamp = matches.size >= 2 && afterLast >= trimmed.length - 1

            var endTime = -1L
            val beginMatches: List<MatchResult>
            val tail: String

            if (hasEndTimestamp) {
                endTime = parseTimestamp(lastMatch) ?: -1L
                beginMatches = matches.dropLast(1)
                val textStart = beginMatches.last().range.last + 1
                val textEnd = lastMatch.range.first
                tail = if (textStart < textEnd) trimmed.substring(textStart, textEnd) else ""
            } else {
                beginMatches = matches
                val textStart = lastMatch.range.last + 1
                tail = if (textStart < trimmed.length) trimmed.substring(textStart) else ""
            }

            val cleanText = tail.trim()
            if (cleanText.isEmpty()) continue

            // Split inline translation: "Japanese_text Chinese_translation" or "English_text Chinese_translation"
            val (originalText, translationText) = splitInlineTranslation(cleanText)

            for (bm in beginMatches) {
                val ts = parseTimestamp(bm) ?: continue
                rawData.add(RawLine(ts, originalText, endTime = endTime))
                if (translationText.isNotEmpty()) {
                    rawData.add(RawLine(ts, translationText, endTime = endTime))
                }
            }
        }

        if (rawData.isEmpty()) return LyricData()

        val mergedLines = mergeRawLines(rawData)
        val finalLines = mergeCloseTimestampTranslations(mergedLines)
        fillEndTimes(finalLines)

        return LyricData(lines = finalLines, offset = offset)
    }

    /**
     * Detect inline original+translation on the same line, separated by a space.
     * Pattern: "Japanese(kana) Chinese(no kana)" or "English Chinese(no kana, no latin)".
     * Returns (original, translation). If no split detected, translation is empty.
     */
    private fun splitInlineTranslation(text: String): Pair<String, String> {
        fun isKana(c: Char): Boolean =
            c.code in 0x3040..0x309F || c.code in 0x30A0..0x30FF

        fun isCJK(c: Char): Boolean =
            c.code in 0x4E00..0x9FFF || c.code in 0x3400..0x4DBF

        fun isLatin(c: Char): Boolean =
            c in 'a'..'z' || c in 'A'..'Z'

        // Covers regular space, thin space (U+2009), ideographic space (U+3000),
        // narrow no-break space (U+202F), and all Unicode space separators.
        fun isSpaceChar(c: Char): Boolean =
            c == ' ' || c.isWhitespace() || Character.isSpaceChar(c)

        val hasKana = text.any { isKana(it) }

        if (hasKana) {
            // Find first space where text before has kana and text after has no kana
            for (i in text.indices) {
                if (isSpaceChar(text[i])) {
                    val before = text.substring(0, i)
                    val after = text.substring(i + 1)
                    val beforeHasKana = before.any { isKana(it) }
                    val afterHasKana = after.any { isKana(it) }
                    if (beforeHasKana && !afterHasKana) {
                        return Pair(before.trim(), after.trim())
                    }
                }
            }
        } else {
            // No kana — try Latin/CJK split for English + Chinese
            for (i in text.indices) {
                if (isSpaceChar(text[i])) {
                    val before = text.substring(0, i)
                    val after = text.substring(i + 1).trimStart()
                    if (after.isEmpty()) continue
                    val beforeHasLatin = before.any { isLatin(it) }
                    val afterStartsWithCJK = isCJK(after.first())
                    val afterHasNoLatin = !after.any { isLatin(it) }
                    val afterHasNoKana = !after.any { isKana(it) }
                    if (beforeHasLatin && afterStartsWithCJK && afterHasNoLatin && afterHasNoKana) {
                        return Pair(before.trim(), after.trim())
                    }
                }
            }
        }

        return Pair(text, "")
    }

    private fun isWordByWordLine(trimmed: String, matches: List<MatchResult>): Boolean {
        if (matches.size < 3) return false
        var textBetweenCount = 0
        for (i in 0 until matches.size - 1) {
            val currentEnd = matches[i].range.last + 1
            val nextStart = matches[i + 1].range.first
            if (nextStart > currentEnd) {
                val between = trimmed.substring(currentEnd, nextStart).trim()
                if (between.isNotEmpty()) textBetweenCount++
            }
        }
        if (textBetweenCount < 2) return false
        val afterFirst = matches[0].range.last + 1
        if (afterFirst >= trimmed.length) return false
        return !trimmed[afterFirst].isWhitespace()
    }

    private fun parseWordByWordLine(trimmed: String, matches: List<MatchResult>): RawLine? {
        val rawSegments = mutableListOf<Pair<Long, String>>()
        var lastEndTimestamp = -1L

        for (i in matches.indices) {
            val m = matches[i]
            val begin = parseTimestamp(m) ?: continue
            val afterTag = m.range.last + 1
            val nextTagStart = if (i + 1 < matches.size) matches[i + 1].range.first else trimmed.length
            val text = if (afterTag < nextTagStart) trimmed.substring(afterTag, nextTagStart) else ""
            if (text.isNotEmpty()) {
                rawSegments.add(Pair(begin, text))
            } else {
                lastEndTimestamp = begin
            }
        }

        if (rawSegments.isEmpty()) return null

        val mergedSegments = mutableListOf<Pair<Long, String>>()
        for ((ts, text) in rawSegments) {
            if (text.trim().isEmpty() && mergedSegments.isNotEmpty()) {
                val prev = mergedSegments.removeAt(mergedSegments.lastIndex)
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
            val needsSpace = !LyricParserBase.isCjkText(coreText) && (hasTrailingSpace || i + 1 < mergedSegments.size)
            val finalText = if (needsSpace && i < mergedSegments.lastIndex) "$coreText " else coreText
            words.add(LyricWord(text = finalText, begin = begin, end = 0L))
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

        return RawLine(lineBegin, lineText, endTime = lineEnd, words = words)
    }

    private fun estimateWordDuration(text: String): Long {
        val cleaned = text.replace(Regex("[ \\t\\r\\n]+"), " ").trim()
        return (cleaned.length * 150L).coerceIn(200L, 2000L)
    }

    // ==================== Enhanced LRC Parsing ====================

    private val enhancedWordRegex = Regex("""<(\d{1,2}):(\d{2})[.:](\d{2,3})>""")

    private fun parseEnhancedLrc(content: String): LyricData {
        val rawLines = content.lines().filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return LyricData()

        var offset = 0L
        val rawData = mutableListOf<RawLine>()

        for (line in rawLines) {
            val trimmed = line.trim()

            val offsetMatch = offsetRegex.find(trimmed)
            if (offsetMatch != null) {
                offset = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            if (metaRegex.matches(trimmed)) continue

            val matches = lrcTimestampRegex.findAll(trimmed).toList()
            if (matches.isEmpty()) continue

            val isWordByWord = matches.size >= 2 && let {
                val afterFirst = matches.first().range.last + 1
                afterFirst < trimmed.length && !trimmed[afterFirst].isWhitespace()
            }

            if (isWordByWord) {
                val result = parseWordByWordLine(trimmed, matches)
                if (result != null) {
                    rawData.add(result)
                }
                continue
            }

            val lastMatch = matches.last()
            val afterLast = lastMatch.range.last + 1
            val hasEndTimestamp = matches.size >= 2 && afterLast >= trimmed.length - 1

            var endTime = -1L
            val beginMatches: List<MatchResult>
            val tail: String

            if (hasEndTimestamp) {
                endTime = parseTimestamp(lastMatch) ?: -1L
                beginMatches = matches.dropLast(1)
                val textStart = beginMatches.last().range.last + 1
                val textEnd = lastMatch.range.first
                tail = if (textStart < textEnd) trimmed.substring(textStart, textEnd) else ""
            } else {
                beginMatches = matches
                val textStart = lastMatch.range.last + 1
                tail = if (textStart < trimmed.length) trimmed.substring(textStart) else ""
            }

            val (cleanText, words) = parseEnhancedText(tail)
            if (cleanText.isEmpty()) continue

            for (bm in beginMatches) {
                val ts = parseTimestamp(bm) ?: continue
                rawData.add(RawLine(ts, cleanText, endTime = endTime, words = words))
            }
        }

        if (rawData.isEmpty()) return LyricData()

        val mergedLines = mergeRawLines(rawData)
        val finalLines = mergeCloseTimestampTranslations(mergedLines)
        fillEndTimes(finalLines)

        return LyricData(lines = finalLines, offset = offset)
    }

    private fun parseEnhancedText(rawText: String): Pair<String, List<LyricWord>> {
        val angleMatches = enhancedWordRegex.findAll(rawText).toList()
        if (angleMatches.isNotEmpty()) {
            val segments = mutableListOf<Triple<Long, String, Long>>()
            for (i in angleMatches.indices) {
                val m = angleMatches[i]
                val begin = parseTimestamp(m) ?: continue
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
            val words = LyricParserBase.applyWordSpacing(segments.map { LyricWord(text = it.second, begin = it.first, end = it.third) })
            val cleanText = words.joinToString("") { it.text }.trim()
            return Pair(cleanText, words)
        }
        return Pair(rawText.trim(), emptyList())
    }

    // ==================== QRC Parsing ====================

    private val qrcLineRegex = Regex("""\[(\d+),(\d+)\]""")
    private val qrcWordRegex = Regex("""\((\d+),(\d+)\)([^(]*)""")

    private fun parseQrc(content: String): LyricData {
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
                    words.add(LyricWord(text = wordText, begin = wordBegin, end = wordBegin + wordDuration))
                    textBuilder.append(wordText)
                }
            }

            val lineText = if (textBuilder.isEmpty()) contentPart.trim() else textBuilder.toString().trim()
            if (lineText.isEmpty()) continue

            val spacedWords = LyricParserBase.applyWordSpacing(words)
            val spacedText = spacedWords.joinToString("") { it.text }.trim()

            result.add(LyricLine(
                timeStamp = lineBegin,
                text = spacedText.ifBlank { lineText },
                endTime = lineEnd,
                words = spacedWords
            ))
        }

        return LyricData(lines = result, offset = offset)
    }

    // ==================== TTML Parsing ====================

    private fun parseTtml(content: String): LyricData {
        return try {
            val root = SimpleXmlParser.parse(content)
            val ttElement = findElementByName(root, "tt") ?: return LyricData()
            val transliterations = parseTtmlTransliterations(ttElement)

            val ttmlLines = mutableListOf<LyricLine>()
            val allPElements = mutableListOf<SimpleXmlParser.XmlElement>()
            collectElementsByName(ttElement, "p", allPElements)

            for (pElement in allPElements) {
                val begin = parseTtmlTime(pElement.getAttribute("begin") ?: "00:00.000")
                val end = parseTtmlTime(pElement.getAttribute("end") ?: "00:00.000")

                val agent = pElement.getAttribute("ttm:agent")
                val lineKey = pElement.getAttribute("itunes:key") ?: pElement.getAttribute("key")

                val currentWords = mutableListOf<LyricWord>()
                var romanizationText: String? = null
                var translationText: String? = null
                var bgText: String? = null
                var bgWords = mutableListOf<LyricWord>()
                var bgTranslation: String? = null

                for (child in pElement.children) {
                    val localName = child.name.substringAfterLast(':')
                    if (localName == "span") {
                        val role = child.getAttribute("ttm:role")
                            ?: child.getAttribute("role")
                            ?: ""

                        when {
                            role == "x-bg" -> {
                                val spanBegin = child.getAttribute("begin")?.let { parseTtmlTime(it) } ?: begin
                                val spanEnd = child.getAttribute("end")?.let { parseTtmlTime(it) } ?: end
                                val bgSpanWords = mutableListOf<LyricWord>()
                                collectSpanWords(child, spanBegin, spanEnd, bgSpanWords)
                                if (bgSpanWords.isNotEmpty()) {
                                    bgWords.addAll(bgSpanWords)
                                    bgText = bgSpanWords.joinToString("") { it.text }.trim()
                                } else {
                                    val txt = collectTextContent(child).trim()
                                    if (txt.isNotEmpty()) bgText = txt
                                }
                            }
                            role == "x-bg-translation" -> {
                                val txt = collectTextContent(child).trim()
                                if (txt.isNotEmpty()) bgTranslation = txt
                            }
                            role == "x-romanization" || role == "x-roman" -> {
                                val txt = collectTextContent(child).trim()
                                if (txt.isNotEmpty()) romanizationText = txt
                            }
                            role == "x-translation" -> {
                                val txt = collectTextContent(child).trim()
                                if (txt.isNotEmpty()) translationText = txt
                            }
                            else -> {
                                val spanBegin = child.getAttribute("begin")?.let { parseTtmlTime(it) } ?: begin
                                val spanEnd = child.getAttribute("end")?.let { parseTtmlTime(it) } ?: end
                                collectSpanWords(child, spanBegin, spanEnd, currentWords)
                            }
                        }
                    }
                }
                if (romanizationText.isNullOrBlank()) {
                    romanizationText = lineKey
                        ?.let(transliterations::get)
                        ?.filter { it.isNotBlank() }
                        ?.joinToString(" ")
                        ?.takeIf { it.isNotBlank() }
                }

                if (currentWords.isEmpty()) {
                    val fullText = collectMainTextContent(pElement).trim()
                    if (fullText.isNotEmpty()) {
                        ttmlLines.add(LyricLine(
                            timeStamp = begin,
                            text = fullText,
                            endTime = end,
                            romanization = romanizationText ?: "",
                            translation = translationText ?: "",
                            agent = agent,
                            backgroundText = bgText,
                            backgroundWords = bgWords,
                            backgroundTranslation = bgTranslation,
                            isTtml = true
                        ))
                    }
                } else {
                    val spacedWords = LyricParserBase.applyWordSpacing(currentWords)
                    val fullText = spacedWords.joinToString("") { it.text }.trim()
                    val spacedBgWords = if (bgWords.isNotEmpty()) LyricParserBase.applyWordSpacing(bgWords) else emptyList()

                    ttmlLines.add(LyricLine(
                        timeStamp = begin,
                        text = fullText,
                        endTime = end,
                        romanization = romanizationText ?: "",
                        translation = translationText ?: "",
                        words = spacedWords,
                        agent = agent,
                        backgroundText = bgText,
                        backgroundWords = spacedBgWords,
                        backgroundTranslation = bgTranslation,
                        isTtml = true
                    ))
                }
            }

            if (ttmlLines.isEmpty()) return LyricData()
            fixTtmlEndTimes(ttmlLines)
            LyricData(lines = ttmlLines)
        } catch (e: Exception) {
            LyricData()
        }
    }

    private fun collectSpanWords(
        element: SimpleXmlParser.XmlElement,
        defaultBegin: Long,
        defaultEnd: Long,
        words: MutableList<LyricWord>
    ) {
        val spanBegin = element.getAttribute("begin")?.let { parseTtmlTime(it) } ?: defaultBegin
        val spanEnd = element.getAttribute("end")?.let { parseTtmlTime(it) } ?: defaultEnd

        val hasChildSpans = element.children.any {
            it.name.substringAfterLast(':') == "span"
        }

        if (hasChildSpans) {
            for (child in element.children) {
                val localName = child.name.substringAfterLast(':')
                if (localName == "span") {
                    val role = child.getAttribute("ttm:role")
                        ?: child.getAttribute("role")
                        ?: ""
                    if (role in listOf("x-translation", "x-romanization", "x-roman", "x-bg", "x-bg-translation")) {
                        continue
                    }
                    collectSpanWords(child, spanBegin, spanEnd, words)
                }
            }
        } else {
            val txt = collectTextContent(element).trim()
            if (txt.isNotEmpty()) {
                words.add(LyricWord(text = txt, begin = spanBegin, end = spanEnd))
            }
        }
    }

    private fun collectTextContent(element: SimpleXmlParser.XmlElement): String {
        val sb = StringBuilder()
        if (element.text.isNotEmpty()) {
            sb.append(element.text)
        }
        for (child in element.children) {
            sb.append(collectTextContent(child))
        }
        return sb.toString()
    }

    /** 收集主文本内容，跳过翻译、罗马音、背景歌词等特殊 role 的 span */
    private fun collectMainTextContent(element: SimpleXmlParser.XmlElement): String {
        val sb = StringBuilder()
        if (element.text.isNotEmpty()) {
            sb.append(element.text)
        }
        for (child in element.children) {
            val localName = child.name.substringAfterLast(':')
            if (localName == "span") {
                val role = child.getAttribute("ttm:role")
                    ?: child.getAttribute("role")
                    ?: ""
                if (role in listOf("x-translation", "x-romanization", "x-roman", "x-bg", "x-bg-translation")) {
                    continue
                }
            }
            sb.append(collectMainTextContent(child))
        }
        return sb.toString()
    }

    private fun findElementByName(element: SimpleXmlParser.XmlElement, name: String): SimpleXmlParser.XmlElement? {
        if (element.name.equals(name, ignoreCase = true)) return element
        for (child in element.children) {
            val found = findElementByName(child, name)
            if (found != null) return found
        }
        return null
    }

    private fun parseTtmlTransliterations(
        root: SimpleXmlParser.XmlElement
    ): Map<String, List<String>> {
        val containers = mutableListOf<SimpleXmlParser.XmlElement>()
        collectElementsByName(root, "transliterations", containers)
        if (containers.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, List<String>>()
        for (container in containers) {
            val textElements = mutableListOf<SimpleXmlParser.XmlElement>()
            collectElementsByName(container, "text", textElements)
            for (textElement in textElements) {
                val key = textElement.getAttribute("for")?.takeIf { it.isNotBlank() } ?: continue
                val values = textElement.children
                    .filter { it.name.substringAfterLast(':').equals("span", ignoreCase = true) }
                    .map { collectTextContent(it).trim() }
                    .filter { it.isNotBlank() }
                if (values.isNotEmpty()) result[key] = values
            }
        }
        return result
    }

    private fun collectElementsByName(
        element: SimpleXmlParser.XmlElement,
        name: String,
        result: MutableList<SimpleXmlParser.XmlElement>
    ) {
        if (element.name.substringAfterLast(':').equals(name, ignoreCase = true)) {
            result.add(element)
        }
        for (child in element.children) {
            collectElementsByName(child, name, result)
        }
    }

    private fun parseTtmlTime(time: String): Long {
        val normalized = time.replace(',', '.')
        val parts = normalized.split(":")
        return when (parts.size) {
            3 -> {
                val h = parts[0].toLongOrNull() ?: 0L
                val m = parts[1].toLongOrNull() ?: 0L
                val (secs, ms) = parseSecondsAndMillis(parts[2])
                h * 3600_000L + m * 60_000L + secs * 1000L + ms
            }
            2 -> {
                val m = parts[0].toLongOrNull() ?: 0L
                val (secs, ms) = parseSecondsAndMillis(parts[1])
                m * 60_000L + secs * 1000L + ms
            }
            1 -> {
                when {
                    normalized.endsWith("ms") -> normalized.removeSuffix("ms").toLongOrNull() ?: 0L
                    normalized.endsWith("s") -> (normalized.removeSuffix("s").toDoubleOrNull() ?: 0.0 * 1000).toLong()
                    else -> {
                        val (secs, ms) = parseSecondsAndMillis(parts[0])
                        secs * 1000L + ms
                    }
                }
            }
            else -> 0L
        }
    }

    private fun parseSecondsAndMillis(secStr: String): Pair<Long, Long> {
        val normalized = secStr.replace(",", ".")
        val dotIdx = normalized.indexOf('.')
        return if (dotIdx >= 0) {
            val secs = normalized.substring(0, dotIdx).toLongOrNull() ?: 0L
            val frac = normalized.substring(dotIdx + 1).padEnd(3, '0').take(3)
            val ms = frac.toLongOrNull() ?: 0L
            Pair(secs, ms)
        } else {
            val secs = normalized.toLongOrNull() ?: 0L
            Pair(secs, 0L)
        }
    }

    // ==================== Lyricify Syllable Parsing ====================

    private val syllableRegex = Regex("(.*?)\\((\\d+),(\\d+)\\)")
    private val attributeRegex = Regex("\\[(\\d+)\\]")
    private val lyricifyLineTimestampRegex = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""")

    private fun parseLyricifySyllable(content: String): LyricData {
        val rawLines = content.lines().filter { it.isNotBlank() }
        if (rawLines.isEmpty()) return LyricData()

        var offset = 0L
        val data = mutableListOf<LyricLine>()

        for (line in rawLines) {
            val trimmed = line.trim()

            val offsetMatch = offsetRegex.find(trimmed)
            if (offsetMatch != null) {
                offset = offsetMatch.groupValues[1].toLongOrNull() ?: 0L
                continue
            }

            if (metaRegex.matches(trimmed)) continue

            val lineTimestampMatch = lyricifyLineTimestampRegex.find(trimmed)
            val lineTimestamp = lineTimestampMatch?.let { parseTimestamp(it) }

            val lineContent = if (lineTimestampMatch != null) {
                trimmed.substring(lineTimestampMatch.range.last + 1)
            } else {
                trimmed
            }

            if (lineContent.isBlank()) continue

            val parsed = parseLyricifyLine(lineContent, lineTimestamp ?: 0L)

            if (parsed.backgroundText != null && data.isNotEmpty()) {
                val last = data.last()
                if (!last.isTtml && last.backgroundText == null) {
                    data[data.size - 1] = last.copy(
                        backgroundText = parsed.backgroundText,
                        backgroundWords = parsed.backgroundWords,
                        backgroundTranslation = parsed.backgroundTranslation
                    )
                } else {
                    data.add(parsed)
                }
            } else {
                data.add(parsed)
            }
        }

        if (data.isEmpty()) return LyricData()
        fillEndTimes(data)
        return LyricData(lines = data, offset = offset)
    }

    private fun parseLyricifyLine(line: String, fallbackTimestamp: Long): LyricLine {
        var real: String
        var isBackground = false
        var agent: String? = null

        if (line.contains("]") && line.contains("[") &&
            (line.indexOf("]") - line.indexOf("[") == 2)) {
            real = line.substring(line.indexOf(']') + 1)
            val attribute = attributeRegex.find(line)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0

            if (attribute !in 0..5) {
                isBackground = true
            }
            agent = when (attribute) {
                0, 2 -> "v1"
                3, 5 -> "v2"
                else -> null
            }
        } else {
            real = line
            isBackground = false
            agent = null
        }

        val matchedSyllables = syllableRegex.findAll(real).toList()
        val syllables = matchedSyllables.map { matched ->
            val result = matched.groupValues
            if (result.size >= 4 && result[2].all { it.isDigit() } && result[3].all { it.isDigit() }) {
                val start = result[2].toLong()
                LyricWord(
                    text = result[1],
                    begin = start,
                    end = start + result[3].toLong()
                )
            } else {
                LyricWord(text = "Error", begin = 0, end = 0)
            }
        }

        val startTime = syllables.firstOrNull()?.begin ?: fallbackTimestamp
        val endTime = syllables.lastOrNull()?.end ?: 0L
        val lineText = syllables.joinToString("") { it.text }.trim()

        return LyricLine(
            timeStamp = startTime,
            text = lineText,
            endTime = if (endTime > startTime) endTime else 0L,
            words = syllables,
            agent = agent,
            backgroundText = if (isBackground) lineText else null,
            backgroundWords = if (isBackground) syllables else emptyList()
        )
    }

    // ==================== Merge Logic ====================

    private fun mergeRawLines(rawData: List<RawLine>): List<LyricLine> {
        val mergedLines = mutableListOf<LyricLine>()
        var i = 0
        while (i < rawData.size) {
            val base = rawData[i]
            var translation = ""
            var romanization = ""
            var bestEndTime = base.endTime
            var bestWords = base.words
            var j = i + 1
            while (j < rawData.size && rawData[j].ts == base.ts) {
                val extraText = rawData[j].text
                if (rawData[j].endTime > bestEndTime) bestEndTime = rawData[j].endTime
                if (rawData[j].words.isNotEmpty() && bestWords.isEmpty()) {
                    bestWords = rawData[j].words
                }
                when {
                    LyricParserBase.isRomajiText(extraText) && !LyricParserBase.isRomajiText(base.text) -> {
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
                words = bestWords
            ))
            i = j
        }
        return mergedLines
    }

    private fun mergeCloseTimestampTranslations(lines: List<LyricLine>): MutableList<LyricLine> {
        if (lines.size < 3) return lines.toMutableList()
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
        return result
    }

    private fun fillEndTimes(lines: MutableList<LyricLine>) {
        for (k in 0 until lines.size - 1) {
            if (lines[k].endTime <= 0L) {
                lines[k] = lines[k].copy(endTime = lines[k + 1].timeStamp)
            }
        }
        if (lines.isNotEmpty() && lines.last().endTime <= 0L) {
            lines[lines.lastIndex] = lines.last().copy(endTime = lines.last().timeStamp + 5000L)
        }
    }

    private fun fixTtmlEndTimes(lines: MutableList<LyricLine>) {
        for (k in 0 until lines.size - 1) {
            val nextBegin = lines[k + 1].timeStamp
            if (lines[k].endTime < nextBegin) {
                lines[k] = lines[k].copy(endTime = nextBegin)
            }
        }
        if (lines.isNotEmpty()) {
            val last = lines.last()
            if (last.endTime <= last.timeStamp + 1000L) {
                lines[lines.lastIndex] = last.copy(endTime = last.timeStamp + 5000L)
            }
        }
    }

    private fun parseTimestamp(match: MatchResult): Long? {
        val mins = match.groupValues[1].toIntOrNull() ?: return null
        val secs = match.groupValues[2].toIntOrNull() ?: return null
        val msStr = match.groupValues[3]
        val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
        return mins * 60_000L + secs * 1000L + ms.toLong()
    }

    private data class RawLine(
        val ts: Long,
        val text: String,
        val endTime: Long = -1L,
        val words: List<LyricWord> = emptyList()
    )

    // ==================== Simple XML Parser ====================

    private object SimpleXmlParser {

        data class XmlAttribute(
            val name: String,
            val value: String
        )

        data class XmlElement(
            val name: String,
            val attributes: List<XmlAttribute>,
            val children: List<XmlElement>,
            val text: String
        ) {
            fun getAttribute(name: String): String? {
                return attributes.find { it.name == name }?.value
                    ?: attributes.find { it.name.substringAfterLast(':') == name.substringAfterLast(':') }?.value
            }
        }

        fun parse(xml: String): XmlElement {
            val stack = ArrayDeque<MutableElement>()
            var i = 0

            while (i < xml.length) {
                val c = xml[i]
                when {
                    c == '<' -> {
                        if (i + 1 < xml.length && xml[i + 1] == '/') {
                            val endIndex = xml.indexOf('>', i + 1)
                            if (endIndex == -1) break

                            if (stack.size > 1) {
                                val current = stack.removeLast().toXmlElement()
                                stack.last().children.add(current)
                            }
                            i = endIndex + 1
                        } else if (i + 1 < xml.length && xml[i + 1] == '!' && xml.startsWith("!--", i + 1)) {
                            val endIndex = xml.indexOf("-->", i + 3)
                            i = if (endIndex == -1) xml.length else endIndex + 3
                        } else if (i + 1 < xml.length && xml[i + 1] == '?') {
                            val endIndex = xml.indexOf("?>", i + 2)
                            i = if (endIndex == -1) xml.length else endIndex + 2
                        } else {
                            val endIndex = xml.indexOf('>', i + 1)
                            if (endIndex == -1) break

                            val tagPart = xml.substring(i + 1, endIndex)
                            val isSelfClosing = tagPart.endsWith('/')

                            val (tagName, attributes) = parseTagAndAttributes(
                                if (isSelfClosing) tagPart.substring(0, tagPart.length - 1).trim() else tagPart
                            )
                            val newElement = MutableElement(tagName, attributes.toMutableList())

                            if (isSelfClosing) {
                                if (stack.isNotEmpty()) {
                                    stack.last().children.add(newElement.toXmlElement())
                                } else {
                                    return newElement.toXmlElement()
                                }
                            } else {
                                stack.addLast(newElement)
                            }
                            i = endIndex + 1
                        }
                    }
                    c.isWhitespace() -> {
                        var j = i
                        while (j < xml.length && xml[j].isWhitespace()) {
                            j++
                        }
                        val whitespace = xml.substring(i, j)
                        if (stack.isNotEmpty()) {
                            val textNode = XmlElement("#text", emptyList(), emptyList(), whitespace)
                            stack.last().children.add(textNode)
                        }
                        i = j
                    }
                    else -> {
                        val nextTagIndex = xml.indexOf('<', i)
                        val rawText = if (nextTagIndex == -1) xml.substring(i) else xml.substring(i, nextTagIndex)

                        if (rawText.isNotEmpty() && stack.isNotEmpty()) {
                            stack.last().textBuilder.append(rawText.trim())
                        }
                        i = if (nextTagIndex == -1) xml.length else nextTagIndex
                    }
                }
            }

            return if (stack.isNotEmpty()) stack.first().toXmlElement() else XmlElement("", emptyList(), emptyList(), "")
        }

        private fun parseTagAndAttributes(tagPart: String): Pair<String, List<XmlAttribute>> {
            val firstSpace = tagPart.indexOf(' ')
            if (firstSpace == -1) return tagPart to emptyList()

            val tagName = tagPart.substring(0, firstSpace)
            val attributes = mutableListOf<XmlAttribute>()

            var idx = firstSpace + 1
            while (idx < tagPart.length) {
                while (idx < tagPart.length && tagPart[idx].isWhitespace()) idx++
                if (idx >= tagPart.length) break

                val equalsIndex = tagPart.indexOf('=', idx)
                if (equalsIndex == -1) break

                val attrName = tagPart.substring(idx, equalsIndex).trim()
                idx = equalsIndex + 1

                while (idx < tagPart.length && tagPart[idx].isWhitespace()) idx++
                if (idx >= tagPart.length) break

                val quote = tagPart[idx]
                if (quote == '"' || quote == '\'') {
                    val nextQuote = tagPart.indexOf(quote, idx + 1)
                    if (nextQuote == -1) break
                    val attrValue = tagPart.substring(idx + 1, nextQuote)
                    attributes.add(XmlAttribute(attrName, attrValue))
                    idx = nextQuote + 1
                } else {
                    var nextSpace = idx
                    while (nextSpace < tagPart.length && !tagPart[nextSpace].isWhitespace()) nextSpace++
                    val attrValue = tagPart.substring(idx, nextSpace)
                    attributes.add(XmlAttribute(attrName, attrValue))
                    idx = nextSpace
                }
            }

            return tagName to attributes
        }

        private class MutableElement(
            val name: String,
            val attributes: MutableList<XmlAttribute> = mutableListOf(),
            val children: MutableList<XmlElement> = mutableListOf(),
            val textBuilder: StringBuilder = StringBuilder()
        ) {
            fun toXmlElement() = XmlElement(name, attributes, children, textBuilder.toString())
        }
    }
}
