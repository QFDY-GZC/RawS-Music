package com.rawsmusic.core.common.model

import android.util.Log
import io.github.proify.lyricon.lyric.model.LyricWord as LyriconWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

fun LyricData.toLyriconSong(name: String? = null, artist: String? = null): Song {
    val o = offset
    val lyricLines = lines.map { line ->
        val words = if (line.words.isNotEmpty()) {
            line.words.map { word ->
                LyriconWord(
                    begin = word.begin + o,
                    end = word.end + o,
                    duration = word.duration,
                    text = word.text
                )
            }
        } else null

        val begin = line.timeStamp + o
        val end = if (line.endTime > 0) line.endTime + o else begin
        RichLyricLine(
            begin = begin,
            end = end,
            duration = if (end > begin) end - begin else 0L,
            text = line.text,
            words = words,
            translation = line.translation.ifBlank { null },
            roma = line.romanization.ifBlank { null }
        )
    }

    Log.d("LyricDebug", "toLyriconSong: ${lines.size} lines → ${lyricLines.size} richLines, " +
            "offset=$o ms, words count: ${lyricLines.count { it.words != null }}, " +
            "translations: ${lyricLines.count { it.translation != null }}")

    return Song(
        name = name,
        artist = artist,
        duration = if (lines.isNotEmpty()) {
            val last = lines.last()
            (if (last.endTime > 0) last.endTime + o else last.timeStamp + o)
        } else 0L,
        lyrics = lyricLines
    )
}
