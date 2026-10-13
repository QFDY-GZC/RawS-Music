package com.rawsmusic.core.ui.widget.export

internal object LyricPosterPolicy {
    const val MAX_LINES = 6

    fun canGenerate(hasSong: Boolean, hasLyrics: Boolean): Boolean = hasSong

    fun toggle(selected: Set<Int>, index: Int): Set<Int> = when {
        index in selected -> selected - index
        selected.size >= MAX_LINES -> selected
        else -> selected + index
    }

    fun ordered(selected: Set<Int>): List<Int> = selected.sorted()

    fun height(minimum: Int, body: Int, top: Int, gap: Int, footer: Int, bottom: Int): Int =
        maxOf(minimum, top + body + gap + footer + bottom)

    fun signature(value: String): String = value.trim().take(40)
}
