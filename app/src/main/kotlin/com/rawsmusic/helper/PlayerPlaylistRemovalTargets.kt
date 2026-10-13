package com.rawsmusic.helper

/** A removal is scoped to checked, still-existing memberships of the clicked song. */
internal fun playerPlaylistRemovalTargets(
    songKey: String,
    selectedIds: Set<String>,
    playlistKeys: Sequence<Pair<String, Iterable<String>>>,
): List<String> = playlistKeys.filter { (id, keys) ->
    id in selectedIds && keys.any { it == songKey }
}.map { it.first }.distinct().toList()
