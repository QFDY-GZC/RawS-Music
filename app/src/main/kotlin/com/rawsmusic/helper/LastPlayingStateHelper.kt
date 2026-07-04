package com.rawsmusic.helper

import com.rawsmusic.module.player.PlayerController

class LastPlayingStateHelper(
    private val getPlayerController: () -> PlayerController?,
    private val resolveCoverUri: (com.rawsmusic.core.common.model.AudioFile) -> String,
    private val syncMirrorCover: (String?) -> Unit,
    private val loadCoverBackground: (String) -> Unit
) {
    fun restore() {
        val controller = getPlayerController()
        val restoredSong = controller?.restoreLastSong()
        restoredSong?.let { song ->
            val coverUri = resolveCoverUri(song)
            val playCoverUri = coverUri
            syncMirrorCover(playCoverUri.ifBlank { null })
            loadCoverBackground(playCoverUri)
        }
    }
}
