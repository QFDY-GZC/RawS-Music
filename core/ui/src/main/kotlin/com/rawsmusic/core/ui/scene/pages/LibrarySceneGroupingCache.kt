package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.common.model.AudioFile

/** Caches only grouping metadata so first scene composition never groups the full library. */
object LibrarySceneGroupingWarmup {
    private val lock = Any()
    @Volatile
    private var source: List<AudioFile>? = null
    private var albumGroups: List<AlbumGroupUi> = emptyList()
    private var folderGroups: List<FolderGroupUi> = emptyList()
    private var artistGroups: List<ArtistGroupUi> = emptyList()

    fun warm(songs: List<AudioFile>) {
        if (source === songs) return
        val albums = songs.toAlbumGroups()
        val folders = songs.toFolderGroups()
        val artists = songs.toArtistGroups()
        synchronized(lock) {
            source = songs
            albumGroups = albums
            folderGroups = folders
            artistGroups = artists
        }
    }

    internal fun albums(songs: List<AudioFile>): List<AlbumGroupUi> {
        if (source !== songs) warm(songs)
        return synchronized(lock) { albumGroups }
    }

    internal fun folders(songs: List<AudioFile>): List<FolderGroupUi> {
        if (source !== songs) warm(songs)
        return synchronized(lock) { folderGroups }
    }

    internal fun artists(songs: List<AudioFile>): List<ArtistGroupUi> {
        if (source !== songs) warm(songs)
        return synchronized(lock) { artistGroups }
    }
}
