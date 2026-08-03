package com.rawsmusic.module.scanner.parser

import com.rawsmusic.core.common.model.LyricData

object TtmlParser {
    fun isTtml(content: String): Boolean {
        val trimmed = content.trimStart()
        return (trimmed.startsWith("<?xml") && trimmed.contains("<tt", ignoreCase = true)) ||
                trimmed.startsWith("<tt", ignoreCase = true)
    }

    fun parse(content: String): LyricData = RawSLyricsParser.parse(content)
}
