package com.rawsmusic.module.scanner

import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine
import com.rawsmusic.core.common.model.LyricWord
import com.rawsmusic.module.scanner.parser.KrcParser
import com.rawsmusic.module.scanner.parser.RawSLyricsParser
import com.rawsmusic.module.scanner.parser.LyricTextNormalizer
import java.io.File
import java.io.FileInputStream
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.text.Normalizer

object LyricReader {

    private const val TAG = "LyricReader"
    private const val CUE_BOUNDARY_TOLERANCE_MS = 600L

    fun readLyrics(song: AudioFile): LyricData {
        val rawSOverride = readRawSOverride(song)
        if (!rawSOverride.isEmpty) return rawSOverride
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
        val rawSOverride = readRawSOverride(songPath)
        if (!rawSOverride.isEmpty) return rawSOverride
        return readAlbumLevelLyrics(songPath)
    }

    private fun readRawSOverride(song: AudioFile): LyricData {
        val audio = File(song.path)
        val suffixes = buildList {
            if (song.isCueTrack()) add(".track${song.cueTrackIndex}.raws.ttml")
            add(".raws.ttml")
        }
        return readRawSOverride(audio, suffixes)
    }

    private fun readRawSOverride(songPath: String): LyricData =
        readRawSOverride(File(songPath), listOf(".raws.ttml"))

    private fun readRawSOverride(audio: File, suffixes: List<String>): LyricData {
        val parent = audio.parentFile ?: return LyricData()
        suffixes.forEach { suffix ->
            val file = File(parent, audio.nameWithoutExtension + suffix)
            if (file.isFile) {
                val parsed = readTextAndParse(file) { RawSLyricsParser.parse(it) }
                if (!parsed.isEmpty) {
                    Log.i(TAG, "LYRIC_TRACE raws_override file=${file.name} lines=${parsed.lines.size}")
                    return parsed
                }
            }
        }
        return LyricData()
    }

    private fun readAlbumLevelLyrics(songPath: String): LyricData {
        val songFile = File(songPath)
        Log.i(TAG, "LYRIC_TRACE start path=${songFile.name} readable=${songFile.canRead()} parent=${songFile.parentFile?.canRead()}")
        val embedded = readEmbeddedLyrics(songPath)
        if (!embedded.isEmpty) {
            Log.d(TAG, "  embedded: ${embedded.lines.size} lines")
            return embedded
        }

        for (finder in fileFinders) {
            val file = findLyricFile(songPath, finder.extensions)
            if (file != null) {
                val parsed = LyricTextNormalizer.normalize(finder.parse(file))
                if (!parsed.isEmpty) {
                    Log.d(TAG, "  ${finder.name}: ${parsed.lines.size} lines from ${file.name}")
                    return parsed
                }
            }
        }

        Log.d(TAG, "  no lyrics found")
        Log.i(TAG, "LYRIC_TRACE no_match path=${songFile.name}")
        return LyricData()
    }

    private fun readCueTrackSpecificLyrics(song: AudioFile): LyricData {
        val dir = File(song.path).parentFile ?: return LyricData()
        val candidates = buildCueTrackLyricBaseNames(song)
        if (candidates.isEmpty()) return LyricData()

        for (finder in fileFinders) {
            val file = findLyricFileByBaseNames(dir, candidates, finder.extensions)
            if (file != null) {
                val parsed = LyricTextNormalizer.normalize(finder.parse(file))
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
            val bytes = FileInputStream(file).use { it.readBytes() }
            val decoded = decodeLyricText(bytes)
            val result = parser(decoded.text)
            Log.i(
                TAG,
                "LYRIC_TRACE parsed file=${file.name} encoding=${decoded.charset} bytes=${bytes.size} lines=${result.lines.size}"
            )
            result
        } catch (e: Exception) {
            Log.w(TAG, "LYRIC_TRACE parse_failed file=${file.name} readable=${file.canRead()}", e)
            LyricData()
        }
    }

    private data class DecodedLyricText(val text: String, val charset: String)

    /** Handles common lyric encodings rather than treating every sidecar as UTF-8. */
    private fun decodeLyricText(bytes: ByteArray): DecodedLyricText {
        if (bytes.startsWith(UTF8_BOM)) return DecodedLyricText(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8 BOM")
        if (bytes.startsWith(UTF16_LE_BOM)) return DecodedLyricText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE), "UTF-16LE BOM")
        if (bytes.startsWith(UTF16_BE_BOM)) return DecodedLyricText(String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE), "UTF-16BE BOM")

        detectUtf16WithoutBom(bytes)?.let { charset ->
            return DecodedLyricText(String(bytes, charset), charset.name())
        }
        strictDecode(bytes, Charsets.UTF_8)?.let { return DecodedLyricText(it, "UTF-8") }
        strictDecode(bytes, Charset.forName("GB18030"))?.let { return DecodedLyricText(it, "GB18030") }
        return DecodedLyricText(String(bytes, Charsets.UTF_8), "UTF-8 replacement")
    }

    private fun strictDecode(bytes: ByteArray, charset: Charset): String? = try {
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private fun detectUtf16WithoutBom(bytes: ByteArray): Charset? {
        val sample = bytes.take(128).toByteArray()
        if (sample.size < 8) return null
        val evenNulls = sample.indices.count { it % 2 == 0 && sample[it] == 0.toByte() }
        val oddNulls = sample.indices.count { it % 2 != 0 && sample[it] == 0.toByte() }
        val pairs = sample.size / 2
        return when {
            oddNulls >= pairs / 3 -> Charsets.UTF_16LE
            evenNulls >= pairs / 3 -> Charsets.UTF_16BE
            else -> null
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

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    private val UTF16_LE_BOM = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
    private val UTF16_BE_BOM = byteArrayOf(0xFE.toByte(), 0xFF.toByte())
}
