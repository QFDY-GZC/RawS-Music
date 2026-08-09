package com.rawsmusic.core.ui.widget.player

/** Chinese timed lyrics use previous/current/next; other languages keep a compact two-row view. */
internal fun fullCoverLyricVisibleRowLimit(primaryText: String): Int =
    if (primaryText.containsChineseFullCoverLyricText()) 3 else 2

internal fun String.containsChineseFullCoverLyricText(): Boolean = any { character ->
    character.code in 0x3400..0x4DBF ||
        character.code in 0x4E00..0x9FFF ||
        character.code in 0xF900..0xFAFF
}
