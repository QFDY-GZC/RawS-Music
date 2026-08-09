package com.rawsmusic.lyric

/** Weight-aware split used by HyperOS so CJK and Latin lyrics occupy comparable space. */
internal object XiaomiSuperIslandLyricLayout {
    data class Split(val left: String, val right: String)

    fun splitFullLyric(text: String, showLeftCover: Boolean, leftMaxWeight: Int, rightMaxWeight: Int): Split {
        val normalized = text.replace(Regex("\\s+"), " ").trim()
        if (normalized.isBlank()) return Split("", "")
        val leftLimit = leftMaxWeight.coerceAtLeast(1)
        val rightLimit = rightMaxWeight.coerceAtLeast(1)
        var best = Split(takeByWeight(normalized, leftLimit), "")
        var bestScore = Int.MAX_VALUE
        for (split in normalized.indices) {
            val left = normalized.substring(0, split).trim()
            val right = normalized.substring(split).trim()
            if (left.isBlank() || right.isBlank()) continue
            val leftFit = takeByWeight(left, leftLimit)
            val rightFit = takeByWeight(right, rightLimit)
            val score = kotlin.math.abs(weight(leftFit) - weight(rightFit)) +
                (if (leftFit.length < left.length) 20 else 0) +
                (if (rightFit.length < right.length) 20 else 0)
            if (score < bestScore) {
                bestScore = score
                best = Split(leftFit, rightFit)
            }
        }
        if (best.right.isBlank()) {
            val right = takeByWeight(normalized, rightLimit)
            best = Split("", right)
        }
        return best
    }

    fun takeByWeight(text: String, maxWeight: Int): String {
        var used = 0
        return buildString {
            for (character in text) {
                val characterWeight = characterWeight(character)
                if (used + characterWeight > maxWeight) break
                append(character)
                used += characterWeight
            }
        }.trim()
    }

    fun weightForCharacters(characters: Int): Int = characters.coerceAtLeast(1) * 2

    private fun weight(text: String): Int = text.sumOf(::characterWeight)

    private fun characterWeight(character: Char): Int = when {
        character.isWhitespace() -> 0
        character in '\u3000'..'\u9fff' || character in '\u3040'..'\u30ff' ||
            character in '\uac00'..'\ud7af' -> 2
        else -> 1
    }
}
