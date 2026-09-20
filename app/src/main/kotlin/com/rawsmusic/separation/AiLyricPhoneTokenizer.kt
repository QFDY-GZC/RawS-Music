package com.rawsmusic.separation

import android.icu.text.Transliterator
import com.rawsmusic.core.common.model.CtcTargetToken
import com.rawsmusic.core.common.model.CtcVocabulary
import com.rawsmusic.core.common.model.LyricData

/**
 * Small, dependency-free text front end for the Charsiu acoustic models.
 *
 * The model repository owns the acoustic graph and vocabulary. Keeping this bounded G2P layer in
 * the APK makes the downloaded model usable without shipping Python, dictionaries, or a second
 * runtime. It is intentionally conservative: unknown text becomes the model's UNK token instead
 * of being silently aligned as graphemes.
 */
data class AiLyricPhoneTarget(
    val tokens: List<CtcTargetToken>,
    val wordTexts: Map<Pair<Int, Int>, String>,
)

object AiLyricPhoneTokenizer {
    fun tokenize(
        lyrics: LyricData,
        vocabulary: CtcVocabulary,
        language: String,
        maximumTextUnits: Int,
    ): Result<AiLyricPhoneTarget> = runCatching {
        require(maximumTextUnits > 0) { "音素歌词文本上限无效" }
        val tokens = mutableListOf<CtcTargetToken>()
        val wordTexts = linkedMapOf<Pair<Int, Int>, String>()
        lyrics.lines.forEachIndexed { lineIndex, line ->
            val words = segments(line.text, language)
            words.forEachIndexed { wordIndex, word ->
                if (word.isBlank()) return@forEachIndexed
                wordTexts[lineIndex to wordIndex] = word
                phones(word, language).forEach { phone ->
                    if (tokens.size >= maximumTextUnits) error("歌词超过音素模型文本上限")
                    val token = phoneToken(phone, vocabulary, language)
                    tokens += CtcTargetToken(
                        tokenId = token,
                        lineIndex = lineIndex,
                        wordIndex = wordIndex,
                        text = phone,
                    )
                }
            }
        }
        require(tokens.isNotEmpty()) { "歌词没有可用于音素对齐的文本" }
        AiLyricPhoneTarget(tokens = tokens, wordTexts = wordTexts)
    }

    private fun segments(text: String, language: String): List<String> = when (language.lowercase()) {
        "en" -> Regex("[A-Za-z]+(?:'[A-Za-z]+)?|[0-9]+")
            .findAll(text)
            .map { it.value }
            .toList()
            .ifEmpty { text.filterNot(Char::isWhitespace).map(Char::toString) }
        "zh" -> text.filterNot(Char::isWhitespace).map(Char::toString)
        "ja" -> text.filterNot(Char::isWhitespace).map(Char::toString)
        else -> text.filterNot(Char::isWhitespace).map(Char::toString)
    }

    private fun phones(word: String, language: String): List<String> = when (language.lowercase()) {
        "en" -> englishPhones(word)
        "zh" -> chinesePhones(word)
        "ja" -> japanesePhones(word)
        else -> listOf(word)
    }

    private fun phoneToken(phone: String, vocabulary: CtcVocabulary, language: String): Int {
        val normalized = phone.trim()
        val direct = vocabulary.idFor(normalized)
            ?: vocabulary.idFor(normalized.uppercase())
            ?: vocabulary.idFor(normalized.lowercase())
        if (direct != null) return direct
        val unknown = when (language.lowercase()) {
            "en", "zh" -> vocabulary.idFor("[UNK]")
            else -> vocabulary.idFor("UNK")
        }
        return requireNotNull(unknown) { "音素词表缺少未知 token" }
    }

    private fun englishPhones(word: String): List<String> {
        val value = word.lowercase()
        val result = mutableListOf<String>()
        var index = 0
        while (index < value.length) {
            val pair = value.substring(index, (index + 2).coerceAtMost(value.length))
            val digraph = ENGLISH_DIGRAPHS[pair]
            if (digraph != null) {
                result += digraph
                index += 2
                continue
            }
            val phone = ENGLISH_LETTERS[value[index]]
            if (phone != null) result += phone
            index++
        }
        return result.ifEmpty { listOf("[UNK]") }
    }

    private fun chinesePhones(text: String): List<String> {
        val result = mutableListOf<String>()
        text.forEach { character ->
            val pinyin = HAN_TO_LATIN.transliterate(character.toString())
                .lowercase()
                .replace(Regex("[^a-zü]"), "")
            if (pinyin.isBlank()) {
                result += "[UNK]"
                return@forEach
            }
            val initial = CHINESE_INITIALS.firstOrNull { pinyin.startsWith(it) }.orEmpty()
            val final = pinyin.removePrefix(initial)
            if (initial.isNotEmpty()) result += initial
            if (final.isNotEmpty()) result += "$final${if (final.last().isDigit()) "" else "5"}"
        }
        return result.ifEmpty { listOf("[UNK]") }
    }

    private fun japanesePhones(text: String): List<String> {
        val result = mutableListOf<String>()
        text.forEach { character ->
            val romanized = JAPANESE_TO_LATIN.transliterate(character.toString())
                .lowercase()
                .replace(Regex("[^a-z]"), "")
            if (romanized.isBlank()) {
                result += "pau"
                return@forEach
            }
            var remaining = romanized
            while (remaining.isNotEmpty()) {
                val match = JAPANESE_PHONES.firstOrNull { remaining.startsWith(it) }
                if (match == null) {
                    result += remaining.take(1)
                    remaining = remaining.drop(1)
                } else {
                    result += match
                    remaining = remaining.removePrefix(match)
                }
            }
        }
        return result.ifEmpty { listOf("sil") }
    }

    private val HAN_TO_LATIN = Transliterator.getInstance("Han-Latin")
    private val JAPANESE_TO_LATIN = Transliterator.getInstance("Hiragana-Latin; Katakana-Latin")

    private val CHINESE_INITIALS = listOf(
        "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h",
        "j", "q", "x", "r", "z", "c", "s",
    )

    private val JAPANESE_PHONES = listOf(
        "gw", "ky", "gy", "kw", "by", "dy", "py", "my", "ny", "ry", "hy", "fy",
        "ch", "ts", "sh", "cl", "a", "i", "u", "e", "o", "k", "g", "s", "z", "t",
        "d", "n", "h", "b", "p", "m", "y", "r", "w",
    )

    private val ENGLISH_DIGRAPHS = mapOf(
        "ch" to "CH", "sh" to "SH", "th" to "TH", "ph" to "F", "ng" to "NG", "wh" to "W",
    )

    private val ENGLISH_LETTERS = mapOf(
        'a' to "AE", 'b' to "B", 'c' to "K", 'd' to "D", 'e' to "EH", 'f' to "F", 'g' to "G",
        'h' to "HH", 'i' to "IH", 'j' to "JH", 'k' to "K", 'l' to "L", 'm' to "M", 'n' to "N",
        'o' to "OW", 'p' to "P", 'q' to "K", 'r' to "R", 's' to "S", 't' to "T", 'u' to "UH",
        'v' to "V", 'w' to "W", 'x' to "K", 'y' to "Y", 'z' to "Z",
    )
}
