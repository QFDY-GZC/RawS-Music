package com.rawsmusic.module.scanner.parser

data class CueTrack(
    val number: Int,
    val title: String = "",
    val performer: String = "",
    val startIndexMs: Long = 0,
    val endIndexMs: Long = -1,
    val isrc: String = ""
)

data class CueSheet(
    val title: String = "",
    val performer: String = "",
    val songwriter: String = "",
    val catalog: String = "",
    val fileName: String = "",
    val tracks: List<CueTrack> = emptyList()
)

object CueParser {

    fun parse(cueText: String): CueSheet {
        val text = cueText.trimStart('\uFEFF', '\uFFFE', '\u200B')
        val lines = text.lines().map { it.trim() }
        var sheetTitle = ""
        var sheetPerformer = ""
        var sheetSongwriter = ""
        var sheetCatalog = ""
        var sheetFileName = ""
        val tracks = mutableListOf<CueTrack>()
        var currentTrackNum = 0
        var currentTitle = ""
        var currentPerformer = ""
        var currentIsrc = ""
        var currentIndex00Ms = -1L
        var currentIndex01Ms = -1L

        fun flushTrack() {
            if (currentTrackNum > 0) {
                val startMs = if (currentIndex01Ms >= 0) currentIndex01Ms else currentIndex00Ms
                if (startMs >= 0) {
                    tracks.add(CueTrack(
                        number = currentTrackNum,
                        title = currentTitle,
                        performer = currentPerformer,
                        startIndexMs = startMs,
                        isrc = currentIsrc
                    ))
                }
                currentTitle = ""
                currentPerformer = ""
                currentIsrc = ""
                currentIndex00Ms = -1L
                currentIndex01Ms = -1L
            }
        }

        for (line in lines) {
            val upper = line.uppercase()
            when {
                upper.startsWith("FILE ") -> {
                    val value = extractQuoted(line) ?: line.substringAfter("FILE ", "").trim().substringBefore(" ").trim()
                    if (sheetFileName.isBlank()) sheetFileName = value
                }
                upper.startsWith("TITLE ") -> {
                    val value = extractQuoted(line) ?: line.substringAfter("TITLE ", "").trim()
                    if (currentTrackNum > 0) currentTitle = value else sheetTitle = value
                }
                upper.startsWith("PERFORMER ") -> {
                    val value = extractQuoted(line) ?: line.substringAfter("PERFORMER ", "").trim()
                    if (currentTrackNum > 0) currentPerformer = value else sheetPerformer = value
                }
                upper.startsWith("SONGWRITER ") -> {
                    if (currentTrackNum == 0) {
                        sheetSongwriter = extractQuoted(line) ?: line.substringAfter("SONGWRITER ", "").trim()
                    }
                }
                upper.startsWith("CATALOG ") -> {
                    sheetCatalog = line.substringAfter("CATALOG ", "").trim()
                }
                upper.startsWith("ISRC ") -> {
                    currentIsrc = line.substringAfter("ISRC ", "").trim()
                }
                upper.startsWith("TRACK ") -> {
                    flushTrack()
                    val parts = line.substringAfter("TRACK ", "").trim().split("\\s+".toRegex())
                    currentTrackNum = parts.firstOrNull()?.toIntOrNull() ?: 0
                }
                upper.startsWith("INDEX 00 ") -> {
                    currentIndex00Ms = parseIndexTime(line.substringAfter("INDEX 00 ", "").trim())
                }
                upper.startsWith("INDEX 01 ") -> {
                    currentIndex01Ms = parseIndexTime(line.substringAfter("INDEX 01 ", "").trim())
                }
                upper.startsWith("INDEX ") -> {
                    if (currentIndex01Ms < 0) {
                        val timeStr = line.substringAfter("INDEX ", "").trim()
                            .substringAfter(" ", "").trim()
                        val ms = parseIndexTime(timeStr)
                        if (ms >= 0 && currentIndex01Ms < 0) currentIndex01Ms = ms
                    }
                }
            }
        }
        flushTrack()

        for (i in tracks.indices) {
            if (i + 1 < tracks.size) {
                tracks[i] = tracks[i].copy(endIndexMs = tracks[i + 1].startIndexMs)
            }
        }

        return CueSheet(
            title = sheetTitle,
            performer = sheetPerformer,
            songwriter = sheetSongwriter,
            catalog = sheetCatalog,
            fileName = sheetFileName,
            tracks = tracks
        )
    }

    private fun extractQuoted(line: String): String? {
        val firstQuote = line.indexOf('"')
        if (firstQuote < 0) return null
        val lastQuote = line.lastIndexOf('"')
        if (lastQuote <= firstQuote) return null
        return line.substring(firstQuote + 1, lastQuote)
    }

    private fun parseIndexTime(timeStr: String): Long {
        val parts = timeStr.split(":")
        if (parts.size != 3) return -1L
        val minutes = parts[0].toIntOrNull() ?: return -1L
        val seconds = parts[1].toIntOrNull() ?: return -1L
        val frames = parts[2].toIntOrNull() ?: return -1L
        return minutes * 60_000L + seconds * 1_000L + (frames * 1000L / 75)
    }
}
