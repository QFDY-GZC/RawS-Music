package com.rawsmusic.module.player.lyrics

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import java.util.Locale

/**
 * Public lyric payload consumed by ColorOS/OPlus lock-screen lyric surfaces.
 *
 * This uses the app's real MediaSession identity. ColorOS receives the timeline through the
 * standard MediaMetadata["lyricInfo"] compatibility payload.
 */
internal object ColorOsLyricMetadata {
    const val METADATA_KEY = "lyricInfo"

    fun build(song: AudioFile, lyrics: LyricData): String? {
        val lines = lyrics.lines
            .asSequence()
            .filter { it.timeStamp >= 0L && it.text.isNotBlank() }
            .sortedBy { it.timeStamp }
            .toList()
        if (lines.isEmpty()) return null

        val lrc = lines.joinToString("\n") { line ->
            "${line.timeStamp.toTimestamp()}${line.text.singleLine()}"
        }
        val translationLrc = lines.mapNotNull { line ->
            line.translation
                .singleLine()
                .takeIf { it.isNotBlank() }
                ?.let { "${line.timeStamp.toTimestamp()}$it" }
        }.joinToString("\n").takeIf { it.isNotBlank() }
        val rawLyric = lines.mapNotNull { it.toRawLine() }
            .joinToString("\n")
            .takeIf { it.isNotBlank() }

        val fields = linkedMapOf(
            "songName" to song.title,
            "artist" to song.artist,
            "songId" to stableSongId(song),
            "lyric" to lrc,
        )
        translationLrc?.let { fields["translationLyric"] = it }
        rawLyric?.let { fields["rawLyric"] = it }
        return fields.entries.joinToString(prefix = "{", postfix = "}") { (key, value) ->
            "\"${key.escapeJson()}\":\"${value.escapeJson()}\""
        }
    }

    private fun LyricLine.toRawLine(): String? {
        val text = text.singleLine().takeIf { it.isNotBlank() } ?: return null
        val words = words
            .asSequence()
            .filter { it.text.isNotBlank() && it.end > it.begin }
            .sortedBy { it.begin }
            .toList()
        if (words.isEmpty()) return "${timeStamp.toTimestamp(millis = true)}$text"

        val raw = buildString(text.length + words.size * 14) {
            words.forEach { word ->
                append(word.begin.coerceAtLeast(0L).toTimestamp(millis = true))
                append(word.text.singleLine())
            }
            val lineEnd = maxOf(
                endTime,
                words.maxOfOrNull { it.end } ?: 0L,
            )
            if (lineEnd > words.last().begin) append(lineEnd.toTimestamp(millis = true))
        }
        return raw.takeIf { it.isNotBlank() }
    }

    private fun stableSongId(song: AudioFile): String = buildString {
        if (song.id > 0L) append(song.id)
        else append(song.path)
        append('|').append(song.fileSize)
        append('|').append(song.dateModified)
        append('|').append(song.cueTrackIndex)
    }

    private fun Long.toTimestamp(millis: Boolean = false): String {
        val safeMs = coerceAtLeast(0L)
        val minutes = safeMs / 60_000L
        val seconds = (safeMs % 60_000L) / 1_000L
        return if (millis) {
            "[%02d:%02d.%03d]".format(Locale.US, minutes, seconds, safeMs % 1_000L)
        } else {
            "[%02d:%02d.%02d]".format(Locale.US, minutes, seconds, (safeMs % 1_000L) / 10L)
        }
    }

    private fun String.singleLine(): String =
        replace(Regex("[\\r\\n\\t]+"), " ").trim()

    private fun String.escapeJson(): String = buildString(length + 16) {
        for (char in this@escapeJson) {
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\b' -> append("\\b")
                '\u000C' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) {
                    append("\\u%04x".format(Locale.US, char.code))
                } else {
                    append(char)
                }
            }
        }
    }
}
