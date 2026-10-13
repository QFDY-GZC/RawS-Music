package com.rawsmusic.lyric

import com.rawsmusic.core.common.model.LyricWord
import kotlin.math.PI
import kotlin.math.sin

internal data class DesktopLyricWordSpan(
    val start: Int, val end: Int, val progress: Float, val lift: Float,
)

internal fun desktopLyricWordSpans(
    text: String, words: List<LyricWord>, positionMs: Long,
    liftEnabled: Boolean, liftPx: Float,
): List<DesktopLyricWordSpan> {
    if (text.isEmpty()) return emptyList()
    val spans = ArrayList<DesktopLyricWordSpan>(words.size * 2 + 1)
    var cursor = 0
    var precedingProgress = 0f
    for (word in words) {
        if (word.text.isEmpty()) continue
        val start = text.indexOf(word.text, cursor)
        if (start < 0) continue // A merged secondary line/ellipsis is not a timed word.
        if (start > cursor) spans += DesktopLyricWordSpan(cursor, start, precedingProgress, 0f)
        val progress = when {
            positionMs < word.begin -> 0f
            positionMs >= word.end -> 1f
            else -> ((positionMs - word.begin).toFloat() /
                (word.end - word.begin).coerceAtLeast(1L)).coerceIn(0f, 1f)
        }
        val active = positionMs >= word.begin && positionMs < word.end
        val lift = if (liftEnabled && active) liftPx * sin(PI * progress).toFloat() else 0f
        cursor = start + word.text.length
        spans += DesktopLyricWordSpan(start, cursor, progress, lift)
        precedingProgress = progress
    }
    if (cursor < text.length) spans += DesktopLyricWordSpan(cursor, text.length, precedingProgress, 0f)
    return spans
}
