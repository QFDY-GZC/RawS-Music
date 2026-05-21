package com.rawsmusic.module.scanner

import android.util.Log
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import com.rawsmusic.module.scanner.parser.KrcParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader

object LyricReader {

    private const val TAG = "LyricReader"

    fun readLyrics(songPath: String): LyricData {
        val songName = File(songPath).name
        Log.d(TAG, "readLyrics: $songName")

        val embedded = readEmbeddedLyrics(songPath)
        if (!embedded.isEmpty) {
            Log.d(TAG, "  embedded: ${embedded.lines.size} lines")
            return embedded
        }

        for (finder in fileFinders) {
            val file = finder.find(songPath)
            if (file != null) {
                val parsed = finder.parse(file)
                if (!parsed.isEmpty) {
                    Log.d(TAG, "  ${finder.name}: ${parsed.lines.size} lines from ${file.name}")
                    return parsed
                }
            }
        }

        Log.d(TAG, "  no lyrics found")
        return LyricData()
    }

    private interface LyricFileFinder {
        val name: String
        fun find(songPath: String): File?
        fun parse(file: File): LyricData
    }

    private val fileFinders = listOf(
        object : LyricFileFinder {
            override val name = "KRC"
            override fun find(songPath: String) = findLyricFile(songPath, listOf("krc", "KRC"))
            override fun parse(file: File) = KrcParser.parse(file.readBytes())
        },
        object : LyricFileFinder {
            override val name = "Lyrics"
            override fun find(songPath: String) = findLyricFile(songPath, listOf("lrc", "LRC", "txt", "TXT", "ttml", "TTML", "dfxp", "xml", "qrc", "QRC"))
            override fun parse(file: File) = readTextAndParse(file) { RawSLyricsParser.parse(it) }
        }
    )

    private fun readTextAndParse(file: File, parser: (String) -> LyricData): LyricData {
        return try {
            val content = BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8")).readText()
            parser(content)
        } catch (_: Exception) { LyricData() }
    }

    private fun readEmbeddedLyrics(songPath: String): LyricData {
        return try {
            val info = com.rawsmusic.core.common.ffmpeg.FFmpegBridge.getMediaInfo(songPath) ?: return LyricData()
            var lyricsString: String? = null
            val candidateKeys = setOf("LYRICS", "LYRICS-ENG", "UNSYNCEDLYRICS", "TXXX:LYRICS")
            for ((key, value) in info) {
                val upperKey = key.uppercase()
                if (candidateKeys.contains(upperKey) || upperKey.contains("LYRIC")) {
                    if (value.isNotBlank()) {
                        lyricsString = value
                        break
                    }
                }
            }
            if (lyricsString == null) return LyricData()
            detectAndParse(lyricsString)
        } catch (e: Exception) {
            Log.e(TAG, "readEmbeddedLyrics error", e)
            LyricData()
        }
    }

    private fun detectAndParse(content: String): LyricData {
        return RawSLyricsParser.parse(content)
    }

    private fun findLyricFile(songPath: String, extensions: List<String>): File? {
        val songFile = File(songPath)
        val dir = songFile.parentFile ?: return null
        val baseName = songFile.nameWithoutExtension

        for (ext in extensions) {
            val exact = File(dir, "$baseName.$ext")
            if (exact.exists() && exact.canRead()) return exact
        }

        for (ext in extensions) {
            val lower = File(dir, "${baseName.lowercase()}.$ext")
            if (lower.exists() && lower.canRead()) return lower
            val upper = File(dir, "${baseName.uppercase()}.$ext")
            if (upper.exists() && upper.canRead()) return upper
        }

        val dashIdx = baseName.indexOf(" - ")
        if (dashIdx > 0) {
            val titlePart = baseName.substring(dashIdx + 3).trim()
            if (titlePart.isNotEmpty()) {
                for (ext in extensions) {
                    val f = File(dir, "$titlePart.$ext")
                    if (f.exists() && f.canRead()) return f
                }
            }
        }

        return fuzzyFindLyricFile(dir, baseName, extensions)
    }

    private fun fuzzyFindLyricFile(dir: File, songBaseName: String, extensions: List<String>): File? {
        val files = dir.listFiles() ?: return null
        val songLower = songBaseName.lowercase()
        for (f in files) {
            if (!f.isFile) continue
            val ext = f.extension
            if (!extensions.any { it.equals(ext, ignoreCase = true) }) continue
            val lyricBase = f.nameWithoutExtension.lowercase()
            if (lyricBase.contains(songLower) || songLower.contains(lyricBase)) {
                if (f.canRead()) return f
            }
            val dashIdx = lyricBase.indexOf(" - ")
            if (dashIdx > 0) {
                val lyricTitle = lyricBase.substring(dashIdx + 3).trim()
                if (lyricTitle.isNotEmpty() && (lyricTitle.contains(songLower) || songLower.contains(lyricTitle))) {
                    if (f.canRead()) return f
                }
            }
        }
        return null
    }
}
