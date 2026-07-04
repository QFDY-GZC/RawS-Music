package com.rawsmusic.module.scanner.parser

import com.rawsmusic.core.common.model.LyricWord

object LyricParserBase {

    private val LineBreakForbiddenChars = setOf(
        ',', '，', '.', '。', '!', '！', '?', '？', ';', '；', ':', '：',
        ')', '）', ']', '】', '}', '》', '>', '\u201C', '\u201D', '\u2019', '」', '』',
        '~', '～', '、', '…', '—', '-', '·'
    )

    private val JapaneseSmallKanaChars = setOf(
        '\u3083', '\u3085', '\u3087',
        '\u3041', '\u3043', '\u3045', '\u3047', '\u3049',
        '\u308E',
        '\u3063',
        '\u30E5', '\u30E7', '\u30E9',
        '\u30A1', '\u30A3', '\u30A5', '\u30A7', '\u30A9',
        '\u30EE',
        '\u30C3'
    )

    fun isCJKCharacter(c: Char): Boolean {
        val code = c.code
        return when {
            code in 0x4E00..0x9FFF -> true
            code in 0x3400..0x4DBF -> true
            code in 0x3000..0x303F -> true
            code in 0x3040..0x309F -> true
            code in 0x30A0..0x30FF -> true
            code in 0x31F0..0x31FF -> true
            code in 0xAC00..0xD7AF -> true
            Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> true
            Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_COMPATIBILITY_IDEOGRAPHS -> true
            Character.UnicodeBlock.of(c) == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A -> true
            else -> false
        }
    }

    fun isCjkText(text: String): Boolean {
        return text.any { isCJKCharacter(it) }
    }

    fun isAttachablePunctuation(c: Char): Boolean {
        val type = Character.getType(c)
        return type == Character.START_PUNCTUATION.toInt() ||
                type == Character.END_PUNCTUATION.toInt() ||
                type == Character.OTHER_PUNCTUATION.toInt() ||
                type == Character.MATH_SYMBOL.toInt() ||
                type == Character.CURRENCY_SYMBOL.toInt() ||
                type == Character.MODIFIER_SYMBOL.toInt()
    }

    fun isJapaneseSmallKana(c: Char): Boolean = c in JapaneseSmallKanaChars

    fun smartSegmentLyric(text: String): List<String> {
        val normalized = text.replace('\u00A0', ' ').replace('\u2009', ' ')
        val segments = mutableListOf<String>()
        var i = 0

        while (i < normalized.length) {
            var prefixSpaces = ""
            while (i < normalized.length && normalized[i] == ' ') {
                prefixSpaces += ' '
                i++
            }
            if (i >= normalized.length) {
                if (prefixSpaces.isNotEmpty()) {
                    segments.add(prefixSpaces)
                }
                break
            }

            val c = normalized[i]

            if (isCJKCharacter(c)) {
                if (isJapaneseSmallKana(c) && prefixSpaces.isEmpty() && segments.isNotEmpty()) {
                    val lastSeg = segments.last()
                    if (lastSeg.isNotEmpty() && isCJKCharacter(lastSeg.last())) {
                        segments[segments.lastIndex] = lastSeg + c
                        i++
                        continue
                    }
                }
                segments.add(prefixSpaces + c)
                i++
            } else if (c == ' ') {
                segments.add(prefixSpaces + c)
                i++
            } else if (isAttachablePunctuation(c) && segments.isNotEmpty()) {
                segments[segments.lastIndex] = segments.last() + c
                i++
            } else {
                val start = i
                while (i < normalized.length) {
                    val ch = normalized[i]
                    if (ch == ' ' || isCJKCharacter(ch)) break
                    if (ch != '\'' && isAttachablePunctuation(ch)) break
                    i++
                }
                segments.add(prefixSpaces + normalized.substring(start, i))
            }
        }

        return segments
    }

    fun expandTimeUnits(words: List<LyricWord>): List<LyricWord> {
        val expanded = mutableListOf<LyricWord>()
        for (word in words) {
            val text = word.text
            if (text.isEmpty()) {
                expanded.add(word)
                continue
            }

            val hasCjk = text.any { isCJKCharacter(it) }

            if (hasCjk) {
                val cjkChars = text.filter { isCJKCharacter(it) || it.isWhitespace() }
                if (cjkChars.length <= 1) {
                    expanded.add(word)
                } else {
                    val nonSpaceChars = cjkChars.filter { !it.isWhitespace() }
                    if (nonSpaceChars.isEmpty()) {
                        expanded.add(word)
                    } else {
                        val charDuration = if (nonSpaceChars.isNotEmpty() && word.duration > 0) {
                            word.duration / nonSpaceChars.length
                        } else 0L
                        var charIdx = 0
                        for (c in cjkChars) {
                            if (c.isWhitespace()) {
                                expanded.add(LyricWord(
                                    begin = word.begin,
                                    end = word.end,
                                    text = c.toString()
                                ))
                            } else {
                                val charBegin = word.begin + charIdx * charDuration
                                val charEnd = if (charIdx == nonSpaceChars.length - 1) word.end else charBegin + charDuration
                                expanded.add(LyricWord(
                                    begin = charBegin,
                                    end = charEnd,
                                    text = c.toString()
                                ))
                                charIdx++
                            }
                        }
                    }
                }
            } else {
                val trimmed = text.trim()
                if (trimmed.length <= 1) {
                    expanded.add(word)
                } else {
                    val charDuration = if (word.duration > 0) word.duration / trimmed.length else 0L
                    for (( idx, c) in trimmed.withIndex()) {
                        val charBegin = word.begin + idx * charDuration
                        val charEnd = if (idx == trimmed.length - 1) word.end else charBegin + charDuration
                        expanded.add(LyricWord(
                            begin = charBegin,
                            end = charEnd,
                            text = c.toString()
                        ))
                    }
                }
            }
        }
        return expanded
    }

    fun applyWordSpacing(words: List<LyricWord>): List<LyricWord> {
        if (words.isEmpty()) return words
        val hasAnyTrailingSpace = words.any { it.text.endsWith(' ') }
        val hasAnyInternalSpace = words.any { it.text.trim().contains(' ') }
        if (hasAnyTrailingSpace || hasAnyInternalSpace) {
            return words.map { word ->
                val trimmed = word.text.trimEnd()
                if (trimmed.isEmpty()) return@map word
                val needsSpace = !isCjkText(trimmed) && !trimmed.endsWith(' ')
                if (needsSpace && word === words.last()) {
                    word.copy(text = trimmed)
                } else if (needsSpace) {
                    word.copy(text = "$trimmed ")
                } else {
                    word.copy(text = trimmed)
                }
            }
        }
        return words.mapIndexed { index, word ->
            val text = word.text
            val needSpace = when {
                text.isBlank() -> false
                isCjkText(text) -> false
                index == words.lastIndex -> false
                else -> true
            }
            word.copy(text = if (needSpace) "$text " else text)
        }
    }

    fun isRomajiText(text: String): Boolean {
        if (text.isBlank()) return false
        val romajiPattern = Regex("""^[a-zA-Z\s'.\-？?！!「」『』()（）\[\]]+$""")
        if (!romajiPattern.matches(text)) return false
        return text.any { it.isLetter() }
    }

    fun parseTimestamp(match: MatchResult): Long? {
        val mins = match.groupValues[1].toIntOrNull() ?: return null
        val secs = match.groupValues[2].toIntOrNull() ?: return null
        val msStr = match.groupValues[3]
        val ms = if (msStr.length == 2) msStr.toInt() * 10 else msStr.toInt()
        return mins * 60_000L + secs * 1000L + ms.toLong()
    }

    fun fillEndTimes(lines: MutableList<com.rawsmusic.core.common.model.LyricLine>) {
        for (k in 0 until lines.size - 1) {
            if (lines[k].endTime <= 0L) {
                lines[k] = lines[k].copy(endTime = lines[k + 1].timeStamp)
            }
        }
        if (lines.isNotEmpty() && lines.last().endTime <= 0L) {
            lines[lines.lastIndex] = lines.last().copy(endTime = lines.last().timeStamp + 5000L)
        }
    }

    fun lineBreakSafeText(text: String): String {
        val sb = StringBuilder(text.length + 8)
        for (ch in text) {
            if (ch in LineBreakForbiddenChars) {
                sb.append('\u2060')
            }
            sb.append(ch)
        }
        return sb.toString()
    }

    fun applyLineBreakProtection(data: com.rawsmusic.core.common.model.LyricData): com.rawsmusic.core.common.model.LyricData {
        if (data.lines.isEmpty()) return data
        val protectedLines = data.lines.map { line ->
            line.copy(
                text = lineBreakSafeText(line.text),
                translation = if (line.translation.isNotBlank()) lineBreakSafeText(line.translation) else line.translation,
                romanization = if (line.romanization.isNotBlank()) lineBreakSafeText(line.romanization) else line.romanization,
                backgroundText = line.backgroundText?.let { if (it.isNotBlank()) lineBreakSafeText(it) else it },
                backgroundTranslation = line.backgroundTranslation?.let { if (it.isNotBlank()) lineBreakSafeText(it) else it }
            )
        }
        return data.copy(lines = protectedLines)
    }
}
