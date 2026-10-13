package com.rawsmusic.core.ui.widget.player

internal class PlayerFavoriteCelebrationGate(private val songKey: String?, initialRevision: Long) {
    private var lastRevision = initialRevision
    private var pending = false
    fun update(revision: Long, eventSongKey: String?, playlistMounted: Boolean): Boolean {
        if (revision > lastRevision) {
            lastRevision = revision
            if (songKey != null && songKey == eventSongKey) pending = true
        }
        if (playlistMounted || !pending) return false
        pending = false
        return true
    }
}
