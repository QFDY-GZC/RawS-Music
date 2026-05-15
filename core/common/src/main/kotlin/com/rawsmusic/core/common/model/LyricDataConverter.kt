package com.rawsmusic.core.common.model

import android.util.Log
import io.github.proify.lyricon.lyric.model.LyricWord as LyriconWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

fun LyricData.toLyriconSong(name: String? = null, artist: String? = null): Song {
    val lyricLines = lines.map { line ->
        val words = if (line.words.isNotEmpty()) {
            line.words.map { word ->
                LyriconWord(
                    begin = word.begin,
                    end = word.end,
                    duration = word.duration,
                    text = word.text
                )
            }
        } else null

        RichLyricLine(
            begin = line.timeStamp,
            end = if (line.endTime > 0) line.endTime else line.timeStamp,
            duration = if (line.endTime > 0) line.endTime - line.timeStamp else 0L,
            text = line.text,
            words = words,
            translation = line.translation.ifBlank { null },
            roma = line.romanization.ifBlank { null }
        )
    }

    Log.d("LyricDebug", "toLyriconSong: ${lines.size} lines → ${lyricLines.size} richLines, " +
            "words count: ${lyricLines.count { it.words != null }}, " +
            "translations: ${lyricLines.count { it.translation != null }}")

    return Song(
        name = name,
        artist = artist,
        duration = if (lines.isNotEmpty()) {
            val last = lines.last()
            (if (last.endTime > 0) last.endTime else last.timeStamp)
        } else 0L,
        lyrics = lyricLines
    )
}
