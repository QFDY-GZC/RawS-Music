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
    val agents = lines
        .mapNotNull { it.agent?.takeIf { a -> a.isNotBlank() } }
        .distinct()
    val isDuet = agents.size >= 2
    val rightAgents = if (isDuet) agents.drop(1).toSet() else emptySet()

    fun shiftedTime(timeMs: Long): Long = timeMs.coerceAtLeast(0L)

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
        //
        // Some providers (notably a subset of QRC/syllable exporters) store each word time as
        // an offset inside the line even though the line tag itself is absolute. Treat that shape
        // as line-relative here, once, before it reaches every lyric surface. Absolute TTML/QRC
        // timelines are left untouched. This avoids a 60s line carrying words at 0..3000ms, which
        // would otherwise make the entire karaoke sweep look completed as soon as the line loads.
        val words = normalizeSourceWordTimeline(
            sourceWords = line.words,
            absoluteBaseMs = begin,
            absoluteLineEndMs = end
        ).map { word ->
            val wordBegin = shiftedTime(word.begin)
            val wordEnd = shiftedTime(word.end).coerceAtLeast(wordBegin + 1L)
            LyriconWord(
                begin = wordBegin,
                end = wordEnd,
                duration = (wordEnd - wordBegin).coerceAtLeast(1L),
                text = word.text
            )
        }

        val backgroundBase = line.backgroundStartTime
            ?.let(::shiftedTime)
            ?.takeIf { it >= begin }
            ?: begin
        val bgWords = normalizeSourceWordTimeline(
            sourceWords = line.backgroundWords,
            absoluteBaseMs = backgroundBase,
            absoluteLineEndMs = end
        ).map { word ->
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
            roma = line.romanization.ifBlank { null },
            backgroundTranslation = line.backgroundTranslation?.takeIf { it.isNotBlank() }
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
        "sourceOffsetIgnored=$offset ms, " +
            "wordLines=${lyricLines.count { !it.words.isNullOrEmpty() }}, " +
            "wordTotal=${lyricLines.sumOf { it.words?.size ?: 0 }}, " +
            "translations=${lyricLines.count { it.translation != null }}, " +
            "zeroDuration=${lyricLines.count { it.duration <= 0L }}, " +
            "duration=$resolvedDuration"
    )

    return Song(id = id, name = name, artist = artist, duration = resolvedDuration, lyrics = lyricLines)
}
private fun normalizeSourceWordTimeline(
    sourceWords: List<LyricWord>,
    absoluteBaseMs: Long,
    absoluteLineEndMs: Long
): List<LyricWord> {
    if (sourceWords.isEmpty() || absoluteBaseMs <= 0L) return sourceWords

    val lineDurationMs = (absoluteLineEndMs - absoluteBaseMs).coerceAtLeast(1L)
    val finiteWords = sourceWords.filter { it.begin >= 0L && it.end >= it.begin }
    if (finiteWords.isEmpty()) return sourceWords

    val firstBegin = finiteWords.minOf { it.begin }
    val lastEnd = finiteWords.maxOf { maxOf(it.begin, it.end) }

    // Absolute timelines cluster around the line's song position. Relative timelines cluster near
    // zero and fit inside approximately one line duration. Keep a generous 2s allowance for
    // provider rounding / trailing syllables, but never rewrite an ambiguous near-absolute line.
    val relativeAllowanceMs = 2_000L
    val looksLineRelative =
        firstBegin < absoluteBaseMs &&
            firstBegin <= lineDurationMs + relativeAllowanceMs &&
            lastEnd <= lineDurationMs + relativeAllowanceMs

    if (!looksLineRelative) return sourceWords

    return sourceWords.map { word ->
        val begin = (absoluteBaseMs + word.begin.coerceAtLeast(0L)).coerceAtLeast(absoluteBaseMs)
        val rawDuration = when {
            word.end > word.begin -> word.end - word.begin
            word.duration > 0L -> word.duration
            else -> 1L
        }
        word.copy(
            begin = begin,
            end = begin + rawDuration.coerceAtLeast(1L),
            duration = rawDuration.coerceAtLeast(1L)
        )
    }
}

