package com.rawsmusic.core.common.model

const val FAVORITES_PLAYLIST_ID = "favorites"

data class UserPlaylist(
    val id: String,
    val name: String,
    val songs: List<UserPlaylistSong>,
    val createdAt: Long,
    val updatedAt: Long
) {
    val isFavorites: Boolean get() = id == FAVORITES_PLAYLIST_ID
}

data class UserPlaylistSong(
    val key: String,
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val duration: Long,
    val path: String,
    val fileSize: Long,
    val format: String,
    val sampleRate: Int,
    val bitRate: Int,
    val bitsPerSample: Int,
    val albumArtPath: String,
    val addedAt: Long
)

fun AudioFile.playlistIdentityKey(): String {
    if (path.isNotBlank()) return "path|${path.replace('\\', '/').lowercase()}"
    return "media|$id|$title|$artist|$album|$duration"
}

fun AudioFile.toUserPlaylistSong(): UserPlaylistSong {
    return UserPlaylistSong(
        key = playlistIdentityKey(),
        id = id,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        path = path,
        fileSize = fileSize,
        format = format,
        sampleRate = sampleRate,
        bitRate = bitRate,
        bitsPerSample = bitsPerSample,
        albumArtPath = albumArtPath,
        addedAt = System.currentTimeMillis()
    )
}

fun UserPlaylistSong.toAudioFile(): AudioFile {
    return AudioFile(
        id = id,
        path = path,
        title = title,
        artist = artist,
        album = album,
        albumId = albumId,
        duration = duration,
        fileSize = fileSize,
        format = format,
        sampleRate = sampleRate,
        bitRate = bitRate,
        bitsPerSample = bitsPerSample,
        albumArtPath = albumArtPath
    )
}
