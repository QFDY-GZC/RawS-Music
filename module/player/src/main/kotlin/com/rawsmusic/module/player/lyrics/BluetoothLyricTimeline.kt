package com.rawsmusic.module.player.lyrics

import com.rawsmusic.core.common.model.LyricData
import com.rawsmusic.core.common.model.LyricLine

/** Select from the service-owned tree; never reuse an outgoing track's lyric payload. */
internal fun bluetoothLyricLine(
    enabled: Boolean,
    songIdentity: String?,
    lyricsIdentity: String?,
    lyrics: LyricData?,
    lyricPositionMs: Long,
): LyricLine? {
    if (!enabled || songIdentity == null || songIdentity != lyricsIdentity || lyrics == null) return null
    return lyrics.getLine(lyrics.findCurrentLine(lyricPositionMs))?.takeIf { it.text.isNotBlank() }
}

/**
 * The next *distinct* timed lyric for an optional two-field AVRCP display. An untimed,
 * empty lyric must not be advertised as a second line. This is a next-line preview,
 * not an imminent-line countdown: a long intro must not replace it with the singer.
 */
internal fun bluetoothLyricNextLine(
    enabled: Boolean,
    songIdentity: String?,
    lyricsIdentity: String?,
    lyrics: LyricData?,
    lyricPositionMs: Long,
): String? {
    if (!enabled || songIdentity == null || songIdentity != lyricsIdentity || lyrics == null) return null
    val index = lyrics.findCurrentLine(lyricPositionMs)
    val current = lyrics.getLine(index)?.takeIf { it.text.isNotBlank() } ?: return null
    for (nextIndex in (index + 1) until lyrics.lines.size) {
        val next = lyrics.lines[nextIndex]
        if (next.timeStamp <= current.timeStamp) continue
        if (next.text.isNotBlank()) return next.text
    }
    return null
}

internal fun bluetoothLyricDisplayArtist(
    enabled: Boolean,
    songIdentity: String?,
    payloadIdentity: String?,
    text: String,
    translation: String,
    showTranslation: Boolean,
): String? {
    if (!enabled || songIdentity == null || payloadIdentity != songIdentity || text.isBlank()) return null
    return if (showTranslation && translation.isNotBlank()) "$text / $translation" else text
}

/** Bluetooth AVRCP implementations disagree about which metadata field to re-query mid-track. */
enum class BluetoothLyricMetadataField(val id: String) {
    ARTIST("artist"),
    TITLE("title"),
    /** Experimental, V1-like minimal metadata: current line in TITLE, secondary line in ARTIST. */
    DUAL("dual");

    companion object {
        fun fromId(id: String?): BluetoothLyricMetadataField =
            when (id) {
                TITLE.id -> TITLE
                DUAL.id -> DUAL
                else -> ARTIST
            }
    }
}

internal data class BluetoothLyricMetadataText(val title: String, val artist: String)

internal fun bluetoothLyricSecondaryLine(
    translationEnabled: Boolean,
    translation: String,
    nextLine: String,
): String? = if (translationEnabled && translation.isNotBlank()) translation else nextLine.ifBlank { null }

/** A lyric must never replace the genuine track title unless the user opts into TITLE mode. */
internal fun bluetoothLyricMetadataText(
    realTitle: String,
    realArtist: String,
    line: String?,
    field: BluetoothLyricMetadataField,
    secondLine: String? = null,
): BluetoothLyricMetadataText = when {
    line.isNullOrBlank() -> BluetoothLyricMetadataText(realTitle, realArtist)
    field == BluetoothLyricMetadataField.DUAL ->
        BluetoothLyricMetadataText(line, secondLine?.takeIf(String::isNotBlank).orEmpty())
    field == BluetoothLyricMetadataField.TITLE -> BluetoothLyricMetadataText(line, realArtist)
    else -> BluetoothLyricMetadataText(realTitle, line)
}
