package com.rawsmusic.core.ui.widget.bitmaps

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.isFileBackedArtworkSource
import com.rawsmusic.core.common.model.resolveAudioFirstArtworkKey

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
    if (song == null) return fallback?.takeIf { it.isNotBlank() }
    val external = fallback?.takeIf { it.isNotBlank() } ?: song.albumArtPath
    return resolveAudioFirstArtworkKey(
        audioPath = song.path,
        fileSize = song.fileSize,
        dateModified = song.dateModified,
        externalArtworkPath = external,
    ).takeIf { it.isNotBlank() }
}

fun AudioFile.fileArtworkKeyOrNull(): String? {
    if (!path.isFileBackedArtworkSource()) return null
    // Artwork belongs to the physical source file, not to a logical CUE row.  AudioFile.coverKey
    // already provides the canonical file-version identity used by list holders/provider caches.
    // Reuse that exact key here so playback, fullscreen, queue and list surfaces share one source
    // authority instead of creating a second "...|0" identity for ordinary non-CUE songs.
    return coverKey.takeIf { it.isNotBlank() }
}

fun String.isLocalArtworkSource(): Boolean = isFileBackedArtworkSource()
