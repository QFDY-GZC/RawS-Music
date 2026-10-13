package com.rawsmusic.helper

import android.content.Context
import com.rawsmusic.R

/** Names represent only songs actually changed, not the requested selection. */
internal fun playlistSongChangeMessage(context: Context, songNames: List<String>, playlistName: String, removing: Boolean): String? {
    if (songNames.isEmpty()) return null
    return if (songNames.size == 1) {
        context.getString(if (removing) R.string.notice_playlist_removed_song else R.string.notice_playlist_added_song,
            songNames.first(), playlistName)
    } else {
        context.getString(if (removing) R.string.notice_playlist_removed_songs else R.string.notice_playlist_added_songs,
            songNames.size, playlistName)
    }
}
