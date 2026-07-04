package com.rawsmusic.module.scanner.parser

import com.rawsmusic.core.common.model.LyricData

object EnhancedLrcParser {
    fun isEnhancedLrc(content: String): Boolean {
        val trimmed = content.trimStart()
        val hasLrcTimestamp = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""").containsMatchIn(trimmed)
        val hasWordMarker = Regex("""<(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))>""").containsMatchIn(trimmed)
        return hasLrcTimestamp && hasWordMarker
    }

    fun parse(content: String): LyricData = RawSLyricsParser.parse(content)
}
