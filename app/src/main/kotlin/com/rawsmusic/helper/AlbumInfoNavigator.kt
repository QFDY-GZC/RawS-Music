package com.rawsmusic.helper

import com.rawsmusic.core.common.model.AudioFile

class AlbumInfoNavigator(
    private val resolveCoverUri: (AudioFile) -> String,
    private val openAlbumDetail: (albumName: String, albumArtist: String, coverPath: String) -> Unit
) {
    fun open(song: AudioFile?) {
        song ?: return
        try {
            val albumName = song.album.trim()
            if (albumName.isBlank()) return
            openAlbumDetail(albumName, song.artist, resolveCoverUri(song))
        } catch (_: Exception) {
        }
    }
}
