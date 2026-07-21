package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isFileBackedArtworkSource

/**
 * Single playback/lyrics/overlay artwork identity.
 *
 * The design keeps page transitions, lyrics, fullscreen and notification surfaces bound to the same
 * artwork type/id and only changes bounds/tier. RawSMusic does not have a scanner-backed artwork type/id yet,
 * so these surfaces must converge on the current audio file's versioned artwork key instead of
 * mixing albumArtPath/content-uri keys with audio-file keys.
 */
fun AudioFile?.resolvePlaybackArtworkKey(fallback: String? = null): String? {
    val song = this
    val fileKey = song?.fileArtworkKeyOrNull()
    if (!fileKey.isNullOrBlank()) return fileKey
    return fallback?.takeIf { it.isNotBlank() }
        ?: song?.albumArtPath?.takeIf { it.isNotBlank() }
}

fun AudioFile.fileArtworkKeyOrNull(): String? {
    val sourcePath = path.takeIf { it.isFileBackedArtworkSource() } ?: return null
    val stamp = buildString {
        append(fileSize)
        append('|')
        append(dateModified)
        if (cueTrackIndex >= 0) {
            append('|')
            append(cueTrackIndex)
        }
    }
    return "audio://$sourcePath|$stamp"
}

fun String.isLocalArtworkSource(): Boolean = isFileBackedArtworkSource()
