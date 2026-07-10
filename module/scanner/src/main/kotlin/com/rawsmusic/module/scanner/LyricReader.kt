package com.rawsmusic.module.scanner

import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.scanner.parser.KrcParser
import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import java.io.BufferedReader
import java.io.File
import java.io.FileInputStream
import java.io.InputStreamReader
import java.text.Normalizer

object LyricReader {

    private const val TAG = "LyricReader"
    private const val CUE_BOUNDARY_TOLERANCE_MS = 600L

    fun readLyrics(song: AudioFile): LyricData {
        if (!song.isCueTrack()) return readLyrics(song.path)

        val trackSpecific = readCueTrackSpecificLyrics(song)
        if (!trackSpecific.isEmpty) {
            Log.d(TAG, "readLyrics: cue track-specific lyrics, track=${song.cueTrackIndex}, lines=${trackSpecific.lines.size}")
            return trackSpecific
        }

        val albumLevel = readAlbumLevelLyrics(song.path)
        if (albumLevel.isEmpty) return albumLevel

        val normalized = albumLevel.sliceForCueTrack(song)
        Log.d(
            TAG,
            "readLyrics: cue album lyrics normalized, track=${song.cueTrackIndex}, " +
                "offset=${song.cueOffsetMs}, end=${song.cueEndMs}, " +
                "source=${albumLevel.lines.size}, result=${normalized.lines.size}"
        )
        return normalized
    }

    fun readLyrics(songPath: String): LyricData {
        val songName = File(songPath).name
        Log.d(TAG, "readLyrics: $songName")
        return readAlbumLevelLyrics(songPath)
    }

    private fun readAlbumLevelLyrics(songPath: String): LyricData {
        val embedded = readEmbeddedLyrics(songPath)
        if (!embedded.isEmpty) {
            Log.d(TAG, "  embedded: ${embedded.lines.size} lines")
            return embedded
        }

        for (finder in fileFinders) {
            val file = findLyricFile(songPath, finder.extensions)
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

    private fun readCueTrackSpecificLyrics(song: AudioFile): LyricData {
        val dir = File(song.path).parentFile ?: return LyricData()
        val candidates = buildCueTrackLyricBaseNames(song)
        if (candidates.isEmpty()) return LyricData()

        for (finder in fileFinders) {
            val file = findLyricFileByBaseNames(dir, candidates, finder.extensions)
            if (file != null) {
                val parsed = finder.parse(file)
                if (!parsed.isEmpty) {
                    Log.d(TAG, "  cue ${finder.name}: ${parsed.lines.size} lines from ${file.name}")
                    return parsed
                }
            }
        }
        return LyricData()
    }

    private data class LyricFileFinder(
        val name: String,
        val extensions: List<String>,
        val parse: (File) -> LyricData
    )

    private val fileFinders = listOf(
        LyricFileFinder(
            name = "KRC",
            extensions = listOf("krc", "KRC"),
            parse = { file -> KrcParser.parse(file.readBytes()) }
        ),
        LyricFileFinder(
            name = "Lyrics",
            extensions = listOf("lrc", "LRC", "txt", "TXT", "ttml", "TTML", "dfxp", "xml", "qrc", "QRC"),
            parse = { file -> readTextAndParse(file) { RawSLyricsParser.parse(it) } }
        )
    )

    private fun readTextAndParse(file: File, parser: (String) -> LyricData): LyricData {
        return try {
            val content = BufferedReader(InputStreamReader(FileInputStream(file), "UTF-8")).readText()
            parser(content)
        } catch (_: Exception) {
            LyricData()
        }
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

        findLyricFileByBaseNames(dir, listOf(baseName), extensions)?.let { return it }

        val dashIdx = baseName.indexOf(" - ")
        if (dashIdx > 0) {
            val titlePart = baseName.substring(dashIdx + 3).trim()
            if (titlePart.isNotEmpty()) {
                findLyricFileByBaseNames(dir, listOf(titlePart), extensions)?.let { return it }
            }
        }

        return fuzzyFindLyricFile(dir, baseName, extensions)
    }

    private fun findLyricFileByBaseNames(
        dir: File,
        baseNames: List<String>,
        extensions: List<String>
    ): File? {
        val normalizedBases = baseNames
            .flatMap { base -> listOf(base, sanitizeFileBaseName(base)) }
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }

        for (base in normalizedBases) {
            for (ext in extensions) {
                val exact = File(dir, "$base.$ext")
                if (exact.exists() && exact.canRead()) return exact
            }
        }

        val files = dir.listFiles()?.filter { it.isFile && it.canRead() }.orEmpty()
        for (base in normalizedBases) {
            val target = base.normalizedLyricBase()
            for (file in files) {
                if (!extensions.any { it.equals(file.extension, ignoreCase = true) }) continue
                if (file.nameWithoutExtension.normalizedLyricBase() == target) return file
            }
        }

        return null
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

    private fun buildCueTrackLyricBaseNames(song: AudioFile): List<String> {
        val title = song.title.ifBlank { song.displayName }.trim()
        val artist = song.artist.trim()
        val trackNo = song.cueTrackIndex.takeIf { it > 0 } ?: song.trackNumber
        val bases = mutableListOf<String>()

        if (title.isNotBlank()) {
            bases += title
            if (artist.isNotBlank()) bases += "$artist - $title"
            if (trackNo > 0) {
                bases += "%02d - %s".format(trackNo, title)
                bases += "%02d. %s".format(trackNo, title)
                bases += "$trackNo - $title"
                bases += "$trackNo. $title"
                if (artist.isNotBlank()) bases += "%02d - %s - %s".format(trackNo, artist, title)
            }
        }

        return bases.distinctBy { it.normalizedLyricBase() }
    }

    private fun AudioFile.isCueTrack(): Boolean = cueTrackIndex > 0 || cueOffsetMs > 0L

    private fun LyricData.sliceForCueTrack(song: AudioFile): LyricData {
        val cueStart = song.cueOffsetMs.coerceAtLeast(0L)
        if (cueStart <= 0L && song.cueEndMs <= 0L) return this

        val cueEnd = when {
            song.cueEndMs > cueStart -> song.cueEndMs
            song.duration > 0L -> cueStart + song.duration
            else -> Long.MAX_VALUE
        }

        val shiftedLines = lines.mapIndexedNotNull { index, line ->
            val nextLine = lines.getOrNull(index + 1)
            val rawBegin = line.timeStamp
            val rawEnd = line.effectiveRawEnd(nextLine)
            val effectiveBegin = rawBegin + offset
            val effectiveEnd = rawEnd + offset

            val overlapsCue = effectiveEnd > cueStart - CUE_BOUNDARY_TOLERANCE_MS &&
                effectiveBegin < cueEnd + CUE_BOUNDARY_TOLERANCE_MS
            if (!overlapsCue) return@mapIndexedNotNull null

            line.shiftForCueTrack(cueStart, cueEnd)
        }.dropWhile { line ->
            line.text.isBlank() && line.translation.isBlank() && line.romanization.isBlank()
        }

        return copy(lines = shiftedLines)
    }

    private fun LyricLine.effectiveRawEnd(nextLine: LyricLine?): Long {
        if (endTime > timeStamp) return endTime
        val lastWordEnd = words.maxOfOrNull { it.end } ?: 0L
        val lastBackgroundWordEnd = backgroundWords.maxOfOrNull { it.end } ?: 0L
        val wordEnd = maxOf(lastWordEnd, lastBackgroundWordEnd)
        if (wordEnd > timeStamp) return wordEnd
        if (nextLine != null && nextLine.timeStamp > timeStamp) return nextLine.timeStamp
        return timeStamp + 3000L
    }

    private fun LyricLine.shiftForCueTrack(cueStart: Long, cueEnd: Long): LyricLine {
        fun shiftTime(value: Long): Long = (value - cueStart).coerceAtLeast(0L)
        fun shiftEnd(value: Long): Long = when {
            value <= 0L -> 0L
            else -> (value - cueStart).coerceAtLeast(1L)
        }
        fun shiftWords(words: List<LyricWord>): List<LyricWord> {
            return words.mapNotNull { word ->
                val begin = shiftTime(word.begin)
                val rawEnd = word.end.takeIf { it > word.begin } ?: word.begin
                val end = shiftEnd(rawEnd).coerceAtLeast(begin + 1L)
                val maxRelativeEnd = (cueEnd - cueStart).takeIf { it > 0L }
                val clippedEnd = maxRelativeEnd?.let { end.coerceAtMost(it) } ?: end
                if (clippedEnd <= begin && word.begin >= cueEnd) null
                else word.copy(begin = begin, end = clippedEnd)
            }
        }

        val maxRelativeEnd = (cueEnd - cueStart).takeIf { it > 0L }
        val shiftedEnd = shiftEnd(endTime).let { shifted ->
            maxRelativeEnd?.let { shifted.coerceAtMost(it) } ?: shifted
        }

        return copy(
            timeStamp = shiftTime(timeStamp),
            words = shiftWords(words),
            endTime = shiftedEnd,
            backgroundWords = shiftWords(backgroundWords)
        )
    }

    private fun sanitizeFileBaseName(value: String): String {
        return value
            .replace(Regex("[\\\\/:*?\"<>|]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun String.normalizedLyricBase(): String {
        val normalized = Normalizer.normalize(this, Normalizer.Form.NFKC)
        return normalized
            .lowercase()
            .replace(Regex("[\\s_–—.·-]+"), " ")
            .replace(Regex("[\\[\\]【】()（）{}]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }
}
