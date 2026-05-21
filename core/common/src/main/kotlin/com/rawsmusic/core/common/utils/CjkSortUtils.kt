package com.rawsmusic.core.common.utils

import android.os.Build
import java.text.Collator
import java.util.Locale

object CjkSortUtils {

    private val transliterator: android.icu.text.Transliterator? by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                android.icu.text.Transliterator.getInstance("Any-Latin; Latin-ASCII; NFD")
            } catch (_: Exception) {
                null
            }
        } else {
            null
        }
    }

    private val cache = object : LinkedHashMap<String, String>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean = size > 4096
    }

    private val collator: Collator by lazy {
        Collator.getInstance(Locale.CHINESE).apply {
            strength = Collator.PRIMARY
        }
    }

    val hasTransliterator: Boolean
        get() = transliterator != null

    fun transliterate(input: String): String {
        if (input.isBlank()) return input
        return cache.getOrPut(input) {
            transliterator?.transliterate(input) ?: input
        }
    }

    fun sortKey(input: String): String {
        return if (transliterator != null) {
            transliterate(input).lowercase()
        } else {
            input.lowercase()
        }
    }

    fun compare(a: String, b: String): Int {
        return if (transliterator != null) {
            sortKey(a).compareTo(sortKey(b))
        } else {
            collator.compare(a, b)
        }
    }

    fun getPinyinInitial(c: Char): String {
        if (transliterator != null) {
            val latin = transliterate(c.toString()).trim()
            val first = latin.firstOrNull()
            if (first != null && first.uppercaseChar() in 'A'..'Z') {
                return first.uppercaseChar().toString()
            }
        }
        return getPinyinInitialByRange(c)
    }

    private fun getPinyinInitialByRange(c: Char): String {
        val code = c.code
        return when {
            code in 0x4E00..0x4E53 -> "A"
            code in 0x4E54..0x4E87 -> "B"
            code in 0x4E88..0x4EA0 -> "C"
            code in 0x4EA1..0x4EFB -> "D"
            code in 0x4EFC..0x4F15 -> "E"
            code in 0x4F16..0x4F59 -> "F"
            code in 0x4F5A..0x4FAD -> "G"
            code in 0x4FAE..0x4FDF -> "H"
            code in 0x4FE0..0x4FF9 -> "J"
            code in 0x4FFA..0x503F -> "K"
            code in 0x5040..0x5085 -> "L"
            code in 0x5086..0x50BD -> "M"
            code in 0x50BE..0x5101 -> "N"
            code in 0x5102..0x5148 -> "O"
            code in 0x5149..0x5175 -> "P"
            code in 0x5176..0x5199 -> "Q"
            code in 0x519A..0x51CF -> "R"
            code in 0x51D0..0x5235 -> "S"
            code in 0x5236..0x5269 -> "T"
            code in 0x526A..0x5291 -> "W"
            code in 0x5292..0x52C2 -> "X"
            code in 0x52C3..0x52F2 -> "Y"
            code in 0x52F3..0x5394 -> "Z"
            else -> "#"
        }
    }
}
