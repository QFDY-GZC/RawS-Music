package com.rawsmusic.core.ui.scene.pages

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.NavScene
import java.util.Locale

internal data class HomeCardStatisticsSnapshot(
    val songCount: Int,
    val recentSongCount: Int,
    val folderCount: Int,
    val albumCount: Int,
    val artistCount: Int,
    val genreCount: Int,
    val yearCount: Int,
    val composerCount: Int,
) {
    fun countFor(scene: NavScene, queueCount: Int = 0): Int? = when (scene) {
        NavScene.SONGS -> songCount
        NavScene.FOLDERS, NavScene.FOLDER_HIERARCHY -> folderCount
        NavScene.ALBUMS -> albumCount
        NavScene.ARTISTS -> artistCount
        NavScene.QUEUE -> queueCount
        NavScene.RECENTLY_ADDED -> recentSongCount
        NavScene.GENRE -> genreCount
        NavScene.YEAR -> yearCount
        NavScene.COMPOSER -> composerCount
        NavScene.ANALYTICS, NavScene.SONG_STATS -> songCount
        else -> null
    }
}

internal fun buildHomeCardStatisticsSnapshot(
    songs: List<AudioFile>,
    nowMs: Long = System.currentTimeMillis(),
): HomeCardStatisticsSnapshot {
    val recentCutoff = nowMs - 7L * 24L * 60L * 60L * 1000L
    val folders = HashSet<String>()
    val albums = HashSet<String>()
    val artists = HashSet<String>()
    val genres = HashSet<String>()
    val years = HashSet<Int>()
    val composers = HashSet<String>()
    var recentCount = 0

    songs.forEach { song ->
        val folder = song.path.substringBeforeLast('/', "").trimEnd('/')
        if (folder.isNotBlank()) folders += folder

        val albumName = song.album.trim()
        if (albumName.isNotBlank()) {
            val albumArtist = song.albumArtist.ifBlank { song.artist }.trim().lowercase(Locale.ROOT)
            albums += "$albumArtist\u001f${albumName.lowercase(Locale.ROOT)}"
        }

        song.artist.trim().takeIf { it.isNotBlank() }?.let { artists += it.lowercase(Locale.ROOT) }
        song.genre.trim().takeIf { it.isNotBlank() }?.let { genres += it.lowercase(Locale.ROOT) }
        song.year.takeIf { it > 0 }?.let(years::add)
        song.composer.trim().takeIf { it.isNotBlank() }?.let { composers += it.lowercase(Locale.ROOT) }

        if (song.dateAdded > recentCutoff || song.dateModified > recentCutoff) recentCount++
    }

    return HomeCardStatisticsSnapshot(
        songCount = songs.size,
        recentSongCount = recentCount,
        folderCount = folders.size,
        albumCount = albums.size,
        artistCount = artists.size,
        genreCount = genres.size,
        yearCount = years.size,
        composerCount = composers.size,
    )
}

internal fun buildHomeContentCounts(
    snapshot: HomeCardStatisticsSnapshot,
    queueCount: Int,
): Map<NavScene, Int> = buildMap {
    (HomeLibraryCardScenes + HomeToolCardScenes).forEach { scene ->
        snapshot.countFor(scene, queueCount)?.let { put(scene, it) }
    }
}
