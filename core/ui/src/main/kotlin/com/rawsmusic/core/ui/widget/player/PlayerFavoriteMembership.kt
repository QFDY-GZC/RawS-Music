package com.rawsmusic.core.ui.widget.player

/** This shortcut saves to user-selected playlists, not the independent audio rating flag. */
internal fun playerSavedPlaylistIds(
    songKey: String?,
    playlists: Sequence<Pair<String, Iterable<String>>>,
): List<String> = if (songKey == null) emptyList() else playlists
    .filter { (_, keys) -> keys.any { it == songKey } }
    .map { it.first }
    .distinct()
    .toList()
