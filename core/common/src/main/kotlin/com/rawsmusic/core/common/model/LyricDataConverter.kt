package com.rawsmusic.core.common.model

import android.util.Log
import io.github.proify.lyricon.lyric.model.LyricWord as LyriconWord
import io.github.proify.lyricon.lyric.model.RichLyricLine
import io.github.proify.lyricon.lyric.model.Song

/**
 * LyricData → Lyricon Song 转换。
 *
 * 关键修复：
 * 1. 普通 LRC 没有 endTime → 用下一行 begin 兜底，保证 duration > 0
 * 2. 只有源歌词真实包含逐字时间轴时才写入 words；普通逐行 LRC 保持 words 为空，
 *    避免 UI 把整行时间误判成逐字扫光。
 */
fun LyricData.toLyriconSong(
    id: String? = null,
    name: String? = null,
    artist: String? = null,
    durationMs: Long = 0L
): Song {
    val offsetMs = offset

    val agents = lines
        .mapNotNull { it.agent?.takeIf { a -> a.isNotBlank() } }
        .distinct()
    val isDuet = agents.size >= 2
    val rightAgents = if (isDuet) agents.drop(1).toSet() else emptySet()

    fun shiftedTime(timeMs: Long): Long = (timeMs + offsetMs).coerceAtLeast(0L)

    val lyricLines = lines.mapIndexed { index, line ->
        val begin = shiftedTime(line.timeStamp)

        val explicitEnd = line.endTime
            .takeIf { it > line.timeStamp }
            ?.let { shiftedTime(it) }

        val nextBegin = lines
            .getOrNull(index + 1)
            ?.timeStamp
            ?.let { shiftedTime(it) }
            ?.takeIf { it > begin }

        val end = when {
            explicitEnd != null && explicitEnd > begin -> explicitEnd
            nextBegin != null && nextBegin > begin -> nextBegin
            durationMs > begin -> durationMs
            else -> begin + 3000L
        }.coerceAtLeast(begin + 1L)

        // 只保留源歌词真实提供的逐字时间。普通逐行歌词必须保持空 words，
        // ComposeLyricView 才能可靠地区分逐行高亮和逐字羽化扫光。
        val words = line.words.map { word ->
            val wordBegin = shiftedTime(word.begin)
            val wordEnd = shiftedTime(word.end).coerceAtLeast(wordBegin + 1L)
            LyriconWord(
                begin = wordBegin,
                end = wordEnd,
                duration = (wordEnd - wordBegin).coerceAtLeast(1L),
                text = word.text
            )
        }

        val bgWords = line.backgroundWords.map { word ->
            val wordBegin = shiftedTime(word.begin)
            val wordEnd = shiftedTime(word.end).coerceAtLeast(wordBegin + 1L)
            LyriconWord(
                begin = wordBegin,
                end = wordEnd,
                duration = (wordEnd - wordBegin).coerceAtLeast(1L),
                text = word.text
            )
        }

        val agentId = line.agent?.takeIf { it.isNotBlank() }

        RichLyricLine(
            begin = begin,
            end = end,
            duration = (end - begin).coerceAtLeast(1L),
            isAlignedRight = isDuet && agentId in rightAgents,
            text = line.text,
            words = words,
            secondary = line.backgroundText?.takeIf { it.isNotBlank() },
            secondaryWords = bgWords,
            translation = line.translation.ifBlank { null },
            roma = line.romanization.ifBlank { null }
        )
    }

    val resolvedDuration = when {
        durationMs > 0L -> durationMs
        lyricLines.isNotEmpty() -> lyricLines.last().end
        else -> 0L
    }

    Log.d(
        "LyricDebug",
        "toLyriconSong: ${lines.size} lines → ${lyricLines.size} richLines, " +
            "offset=$offsetMs ms, " +
            "wordLines=${lyricLines.count { !it.words.isNullOrEmpty() }}, " +
            "wordTotal=${lyricLines.sumOf { it.words?.size ?: 0 }}, " +
            "translations=${lyricLines.count { it.translation != null }}, " +
            "zeroDuration=${lyricLines.count { it.duration <= 0L }}, " +
            "duration=$resolvedDuration"
    )

    return Song(id = id, name = name, artist = artist, duration = resolvedDuration, lyrics = lyricLines)
}
