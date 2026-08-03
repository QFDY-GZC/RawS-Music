package com.rawsmusic.module.scanner.parser

import com.rawsmusic.core.common.model.LyricData

object LrcParser {
    fun isLrc(content: String): Boolean {
        val trimmed = content.trimStart()
        return Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{1,3}))?]""").containsMatchIn(trimmed)
    }

    fun parse(content: String): LyricData = RawSLyricsParser.parse(content)
}
