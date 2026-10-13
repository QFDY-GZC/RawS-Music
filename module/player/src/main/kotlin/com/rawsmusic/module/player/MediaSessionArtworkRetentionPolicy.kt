package com.rawsmusic.module.player

/**
 * An accepted metadata update is a complete replacement, not a field-level patch.
 * Keep previously published artwork only for the identical committed song.
 */
internal enum class MediaSessionArtworkSource {
    INCOMING,
    PREVIOUS_SAME_SONG,
    CURRENT_SONG_COVER,
    NONE,
}

internal fun resolveMediaSessionArtworkSource(
    requestedIdentity: String,
    incomingAvailable: Boolean,
    previousIdentity: String?,
    previousAvailable: Boolean,
    coverIdentity: String?,
    coverAvailable: Boolean,
): MediaSessionArtworkSource = when {
    incomingAvailable -> MediaSessionArtworkSource.INCOMING
    requestedIdentity.isNotEmpty() &&
        previousIdentity == requestedIdentity && previousAvailable ->
        MediaSessionArtworkSource.PREVIOUS_SAME_SONG
    requestedIdentity.isNotEmpty() &&
        coverIdentity == requestedIdentity && coverAvailable ->
        MediaSessionArtworkSource.CURRENT_SONG_COVER
    else -> MediaSessionArtworkSource.NONE
}
