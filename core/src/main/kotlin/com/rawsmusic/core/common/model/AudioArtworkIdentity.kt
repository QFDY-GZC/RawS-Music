package com.rawsmusic.core.common.model

/**
 * Builds the stable artwork identity used by every UI/provider entry point.
 *
 * A file-backed audio path owns its artwork identity. External album-art metadata is only a fallback
 * for virtual/library rows that do not have a real audio source. This prevents a scanner-provided
 * folder.jpg/content URI from bypassing the embedded-artwork probe before BitmapProvider sees the
 * current track.
 */
fun resolveAudioFirstArtworkKey(
    audioPath: String,
    fileSize: Long,
    dateModified: Long,
    externalArtworkPath: String = ""
): String {
    if (audioPath.isFileBackedArtworkSource()) {
        return "audio://$audioPath|$fileSize|$dateModified"
    }
    return externalArtworkPath.takeIf { it.isNotBlank() }.orEmpty()
}

/** True only when BitmapProvider can open the audio source as a local file. */
fun String.isFileBackedArtworkSource(): Boolean {
    if (isBlank()) return false
    return !startsWith("content://", ignoreCase = true) &&
        !startsWith("http://", ignoreCase = true) &&
        !startsWith("https://", ignoreCase = true)
}
