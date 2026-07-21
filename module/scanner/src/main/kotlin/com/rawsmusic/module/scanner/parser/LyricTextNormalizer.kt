package com.rawsmusic.module.scanner.parser

import com.rawsmusic.core.common.model.LyricData

/** Normalizes escaped text after format parsing so XML markup remains intact. */
object LyricTextNormalizer {

    private val numericEntityRegex = Regex("&#(?:x([0-9a-f]{1,6})|([0-9]{1,7}))[;；]", RegexOption.IGNORE_CASE)
    private val namedEntityRegex = Regex("&(amp|lt|gt|quot|apos|nbsp)[;；]", RegexOption.IGNORE_CASE)

    fun normalize(data: LyricData): LyricData {
        if (data.lines.isEmpty()) return data
        return data.copy(
            lines = data.lines.map { line ->
                line.copy(
                    text = decodeEntities(line.text),
                    translation = decodeEntities(line.translation),
                    romanization = decodeEntities(line.romanization),
                    words = line.words.map { word -> word.copy(text = decodeEntities(word.text)) },
                    backgroundText = line.backgroundText?.let(::decodeEntities),
                    backgroundTranslation = line.backgroundTranslation?.let(::decodeEntities),
                    backgroundWords = line.backgroundWords.map { word ->
                        word.copy(text = decodeEntities(word.text))
                    }
                )
            }
        )
    }

    fun decodeEntities(value: String): String {
        if ('&' !in value) return value
        var decoded = value
        // Some providers return entities twice escaped, for example &amp;apos;.
        repeat(3) {
            val before = decoded
            decoded = numericEntityRegex.replace(decoded) { match ->
                val radix = if (match.groupValues[1].isNotEmpty()) 16 else 10
                val rawCodePoint = match.groupValues[1].ifEmpty { match.groupValues[2] }
                val codePoint = rawCodePoint.toIntOrNull(radix)
                if (codePoint == null || !Character.isValidCodePoint(codePoint)) {
                    match.value
                } else {
                    String(Character.toChars(codePoint))
                }
            }
            decoded = namedEntityRegex.replace(decoded) { match ->
                when (match.groupValues[1].lowercase()) {
                    "amp" -> "&"
                    "lt" -> "<"
                    "gt" -> ">"
                    "quot" -> "\""
                    "apos" -> "'"
                    "nbsp" -> " "
                    else -> match.value
                }
            }
            if (decoded == before) return decoded
        }
        return decoded
    }
}
