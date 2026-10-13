package com.rawsmusic.core.common.model

import io.github.proify.lyricon.lyric.model.LyricWord as DisplayWord
import io.github.proify.lyricon.lyric.model.LyricMetadata

internal fun attachTimedPronunciation(words: List<LyricWord>, pronunciation: List<LyricWord>): List<LyricWord> {
    if (pronunciation.isEmpty()) return words
    val byTiming = pronunciation.filter { it.text.isNotBlank() && it.end > it.begin }
        .groupBy { it.begin to it.end }
    return words.map { word ->
        if (word.ruby.isNotEmpty()) word else {
            val match = byTiming[word.begin to word.end]?.singleOrNull()
            if (match == null) word else word.copy(
                ruby = listOf(LyricRubySyllable(match.begin, match.end, match.text)),
            )
        }
    }
}

internal fun LyricWord.toDisplayLyricWord(): DisplayWord = DisplayWord(
    text = text, begin = begin, end = end, duration = (end - begin).coerceAtLeast(1L),
    metadata = ruby.takeIf { it.isNotEmpty() }?.let { syllables ->
        LyricMetadata(buildMap {
            put("raws.ruby.count", syllables.size.toString())
            syllables.forEachIndexed { index, syllable ->
                put("raws.ruby.$index.text", syllable.text)
                put("raws.ruby.$index.begin", syllable.begin?.toString())
                put("raws.ruby.$index.end", syllable.end?.toString())
            }
        })
    },
)

/** Stored in the existing serializable metadata so deep copies and notification models retain it. */
fun DisplayWord.rubySyllables(): List<LyricRubySyllable> {
    val values = metadata ?: return emptyList()
    val count = values["raws.ruby.count"]?.toIntOrNull()?.coerceIn(0, 128) ?: return emptyList()
    return (0 until count).mapNotNull { index ->
        val text = values["raws.ruby.$index.text"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
        LyricRubySyllable(values["raws.ruby.$index.begin"]?.toLongOrNull(),
            values["raws.ruby.$index.end"]?.toLongOrNull(), text)
    }
}
