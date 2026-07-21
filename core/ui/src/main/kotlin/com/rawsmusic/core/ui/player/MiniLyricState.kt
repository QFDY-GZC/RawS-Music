package com.rawsmusic.core.ui.widget.player

import io.github.proify.lyricon.lyric.model.interfaces.IRichLyricLine

internal data class MiniLyricLineState(
    val index: Int,
    val beginMs: Long,
    val primary: String,
    val secondaryParts: List<String>,
    val active: Boolean,
    val allowWrap: Boolean
) {
    val secondaryText: String = secondaryParts.joinToString(separator = " · ")
}

internal data class MiniLyricPresentation(
    val lines: List<MiniLyricLineState> = emptyList(),
    val activeLineIndex: Int = -1,
    val anchorLineIndex: Int = -1,
    val isInterlude: Boolean = false
) {
    val currentLine: MiniLyricLineState?
        get() = lines.firstOrNull { it.active }
}

internal data class MiniLyricSource(
    val timingLines: List<IRichLyricLine>,
    val displayLines: List<MiniLyricLineState>,
    val interludes: List<LyricInterlude>
)

internal fun prepareMiniLyricSource(
    lines: List<IRichLyricLine>,
    displayTranslation: Boolean,
    displayRoma: Boolean
): MiniLyricSource = MiniLyricSource(
    timingLines = lines,
    displayLines = lines.mapIndexedNotNull { index, line ->
        line.toMiniLyricDisplay(index, displayTranslation, displayRoma)
    },
    interludes = calculateLyricInterludes(lines)
)

internal fun resolveMiniLyricPresentation(
    lines: List<IRichLyricLine>,
    positionMs: Long,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    maxPrimaryRows: Int,
    interludes: List<LyricInterlude> = calculateLyricInterludes(lines)
): MiniLyricPresentation = resolveMiniLyricPresentation(
    source = MiniLyricSource(
        timingLines = lines,
        displayLines = lines.mapIndexedNotNull { index, line ->
            line.toMiniLyricDisplay(index, displayTranslation, displayRoma)
        },
        interludes = interludes
    ),
    positionMs = positionMs,
    maxPrimaryRows = maxPrimaryRows
)

internal fun resolveMiniLyricPresentation(
    source: MiniLyricSource,
    positionMs: Long,
    maxPrimaryRows: Int
): MiniLyricPresentation {
    if (source.timingLines.isEmpty() || source.displayLines.isEmpty()) {
        return MiniLyricPresentation()
    }

    val displayLines = source.displayLines
    val playback = calculateLyricPlaybackState(
        source.timingLines,
        positionMs,
        source.interludes
    )
    val activeIndex = playback.currentLineIndex.takeIf { current ->
        displayLines.any { it.index == current }
    } ?: -1
    val requestedAnchor = activeIndex.takeIf { it >= 0 } ?: playback.anchorLineIndex
    val anchorPosition = displayLines.indexOfFirst { it.index == requestedAnchor }
        .takeIf { it >= 0 }
        ?: displayLines.indexOfFirst { it.index > requestedAnchor }.takeIf { it >= 0 }
        ?: displayLines.lastIndex

    val anchor = displayLines[anchorPosition]
    val rowLimit = maxPrimaryRows.coerceAtLeast(1).coerceAtMost(
        if (anchor.secondaryParts.isEmpty()) 5 else 3
    )
    val preferredBefore = when {
        rowLimit == 1 -> 0
        rowLimit == 2 || anchor.secondaryParts.isNotEmpty() -> 1
        else -> 2
    }
    var start = (anchorPosition - preferredBefore).coerceAtLeast(0)
    var endExclusive = (start + rowLimit).coerceAtMost(displayLines.size)
    start = (endExclusive - rowLimit).coerceAtLeast(0)
    endExclusive = (start + rowLimit).coerceAtMost(displayLines.size)

    return MiniLyricPresentation(
        lines = displayLines.subList(start, endExclusive).map { line ->
            line.copy(
                active = line.index == activeIndex,
                allowWrap = line.index == activeIndex && line.primary.length > 28
            )
        },
        activeLineIndex = activeIndex,
        anchorLineIndex = anchor.index,
        isInterlude = playback.activeInterlude != null
    )
}

private fun IRichLyricLine.toMiniLyricDisplay(
    index: Int,
    displayTranslation: Boolean,
    displayRoma: Boolean
): MiniLyricLineState? {
    val main = text.displayableMiniLyricText()
    val background = secondary.displayableMiniLyricText()
    val translationText = translation.displayableMiniLyricText()
    val romaText = roma.displayableMiniLyricText()
    val backgroundTranslationText = backgroundTranslation.displayableMiniLyricText()

    val primary = main
        ?: background
        ?: translationText.takeIf { displayTranslation }
        ?: romaText.takeIf { displayRoma }
        ?: return null

    val secondaryParts = buildList {
        if (displayRoma) addDistinct(romaText, primary)
        if (displayTranslation) addDistinct(translationText, primary)
        addDistinct(background, primary)
        if (displayTranslation) addDistinct(backgroundTranslationText, primary)
    }

    return MiniLyricLineState(
        index = index,
        beginMs = begin,
        primary = primary,
        secondaryParts = secondaryParts,
        active = false,
        allowWrap = false
    )
}

private fun MutableList<String>.addDistinct(value: String?, primary: String) {
    if (value != null && value != primary && none { it == value }) add(value)
}

private fun String?.displayableMiniLyricText(): String? {
    val value = this?.trim()?.takeIf(String::isNotEmpty) ?: return null
    if (MINI_LYRIC_TIMESTAMP_ONLY.matches(value.replace(',', '.'))) return null
    if (value.all { it.isWhitespace() || it in MINI_LYRIC_MUSIC_SYMBOLS }) return null
    return value
}

private val MINI_LYRIC_MUSIC_SYMBOLS = setOf(
    '♪', '♫', '♬', '♩', '♭', '♮', '♯', '☆', '★', '·', '.', '。', '…'
)

private val MINI_LYRIC_TIMESTAMP_ONLY = Regex(
    """^(?:(?:\[|<)\d{1,2}:\d{2}(?:[.:]\d{1,3})?(?:]|>))+$"""
)
