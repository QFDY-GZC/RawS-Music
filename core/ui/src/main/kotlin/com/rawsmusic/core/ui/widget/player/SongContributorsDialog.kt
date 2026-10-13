package com.rawsmusic.core.ui.widget.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * Shared action entry, used by MiniPlayer, standard PLAYER and immersive PLAYER. The dialog
 * window is hosted exactly once at AppMainLayout; nothing is attached to a moving player holder.
 */
object SongContributorsDialogState {
    var requestedSong by mutableStateOf<AudioFile?>(null)
        private set

    fun show(song: AudioFile?) {
        if (song != null) requestedSong = song
    }

    fun dismiss() {
        requestedSong = null
    }
}

internal data class AlbumParticipant(
    val name: String,
    val songCount: Int,
    val representative: AudioFile,
)

internal data class SongContributorsSummary(
    val songArtists: List<String>,
    val albumArtists: List<String>,
    val composers: List<String>,
    val participants: List<AlbumParticipant>,
    val trackCount: Int,
    val collaborationTrackCount: Int,
)

/** Deliberately conservative: a comma in \"Tyler, The Creator\" is not an artist separator. */
internal fun splitCreditsArtists(raw: String): List<String> {
    if (raw.isBlank()) return emptyList()
    val separators = Regex(
        "(?i)\\s+(?:feat\\.?|ft\\.?|featuring|with|vs\\.?|x)\\s+|\\s*[;；、]\\s*|\\s+[/&×＋+]\\s+|\\s*/\\s*"
    )
    return raw.trim()
        .split(separators)
        .map(String::trim)
        .filter(String::isNotBlank)
        .distinctBy(String::lowercase)
}

/** A guest musician often has no standalone entry in the full-field library artist grouping. */
internal fun creditedSongsForArtist(name: String, librarySongs: List<AudioFile>): List<AudioFile> =
    librarySongs.filter { song ->
        splitCreditsArtists(song.artist).any { it.equals(name, ignoreCase = true) } ||
            splitCreditsArtists(song.albumArtist).any { it.equals(name, ignoreCase = true) }
    }

internal fun creditedSongsForComposer(name: String, librarySongs: List<AudioFile>): List<AudioFile> =
    librarySongs.filter { song ->
        splitCreditsArtists(song.composer).any { it.equals(name, ignoreCase = true) }
    }

/** The album list's actual provider identity: full album-artist field, then U+241F, then album. */
internal fun songCreditsAlbumRouteKey(
    song: AudioFile,
    librarySongs: List<AudioFile>,
): String? {
    if (song.album.isBlank()) return null
    val album = song.album
    val owner = song.albumArtist.ifBlank { song.artist }
        .ifBlank { com.rawsmusic.core.ui.widget.virtuallist.VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL }
    val route = "$owner␟$album"
    return route.takeIf {
        librarySongs.any { candidate ->
            candidate.album == album &&
                candidate.albumArtist.ifBlank { candidate.artist }
                    .ifBlank {
                        com.rawsmusic.core.ui.widget.virtuallist.VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL
                    } == owner
        }
    }
}

private fun belongsToSameAlbum(song: AudioFile, candidate: AudioFile): Boolean {
    if (song.album.isBlank() || candidate.album.isBlank()) return false
    if (!song.album.trim().equals(candidate.album.trim(), ignoreCase = true)) return false
    if (song.albumId > 0L && candidate.albumId > 0L) {
        return song.albumId == candidate.albumId
    }
    val leftArtist = song.albumArtist.trim()
    val rightArtist = candidate.albumArtist.trim()
    if (leftArtist.isNotBlank() && rightArtist.isNotBlank()) {
        return leftArtist.equals(rightArtist, ignoreCase = true)
    }
    // Missing album IDs/artists can produce thousands of false matches for \"Unknown Album\".
    // Keep that case within one real directory rather than grouping by album title alone.
    val songFolder = song.path.substringBeforeLast('/', "")
    val candidateFolder = candidate.path.substringBeforeLast('/', "")
    return songFolder.isNotBlank() && songFolder == candidateFolder
}

internal fun buildSongContributorsSummary(
    song: AudioFile,
    librarySongs: List<AudioFile>,
): SongContributorsSummary {
    val matching = if (song.album.isBlank()) emptyList() else librarySongs.filter {
        belongsToSameAlbum(song, it)
    }
    val albumSongs = (matching + song).distinctBy {
        "${it.path}|${it.cueTrackIndex}|${it.cueOffsetMs}"
    }
    data class MutableParticipant(
        val name: String,
        var count: Int,
        val example: AudioFile,
    )
    val participants = linkedMapOf<String, MutableParticipant>()
    var collaborationTracks = 0
    albumSongs.forEach { track ->
        val performers = splitCreditsArtists(track.artist)
        if (performers.size > 1) collaborationTracks++
        performers.forEach { artist ->
            val key = artist.lowercase()
            val existing = participants[key]
            if (existing != null) {
                existing.count++
            } else {
                participants[key] = MutableParticipant(artist, 1, track)
            }
        }
    }
    val currentNames = splitCreditsArtists(song.artist).map(String::lowercase).toSet()
    return SongContributorsSummary(
        songArtists = splitCreditsArtists(song.artist),
        albumArtists = splitCreditsArtists(song.albumArtist),
        composers = splitCreditsArtists(song.composer),
        participants = participants.values
            .sortedWith(
                compareByDescending<MutableParticipant> { it.name.lowercase() in currentNames }
                    .thenByDescending { it.count }
                    .thenBy { it.name.lowercase() },
            )
            .map { AlbumParticipant(it.name, it.count, it.example) },
        trackCount = albumSongs.size,
        collaborationTrackCount = collaborationTracks,
    )
}

@Composable
fun SongContributorsDialogHost(
    librarySongs: List<AudioFile>,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onComposerClick: (String) -> Unit = {},
) {
    val song = SongContributorsDialogState.requestedSong ?: return
    val summary = remember(song, librarySongs) {
        buildSongContributorsSummary(song, librarySongs)
    }
    val albumRoute = remember(song, librarySongs) {
        songCreditsAlbumRouteKey(song, librarySongs)
    }
    fun followArtist(name: String) {
        SongContributorsDialogState.dismiss()
        onArtistClick(name)
    }
    fun followAlbum(key: String) {
        SongContributorsDialogState.dismiss()
        onAlbumClick(key)
    }
    fun followComposer(name: String) {
        SongContributorsDialogState.dismiss()
        onComposerClick(name)
    }
    val scheme = MiuixTheme.colorScheme
    val primary = scheme.onSurface
    val secondary = scheme.onSurfaceVariantSummary

    Dialog(
        onDismissRequest = SongContributorsDialogState::dismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.92f).heightIn(max = 610.dp),
            shape = RoundedCornerShape(28.dp),
            color = scheme.surfaceContainer,
            shadowElevation = 20.dp,
        ) {
            // Load round album thumbnails only for visible participants. Large compilation albums
            // may contain dozens of credited artists; eagerly composing every artwork would
            // generate unnecessary image requests on the very frame the popup opens.
            androidx.compose.runtime.key(song.path, song.cueTrackIndex, song.cueOffsetMs) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    item(key = "heading") {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.song_credits_title),
                                color = primary,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f),
                            )
                            Text(
                                text = stringResource(R.string.song_credits_close),
                                color = scheme.primary,
                                fontSize = 14.sp,
                                modifier = Modifier.clip(RoundedCornerShape(12.dp))
                                    .clickable { SongContributorsDialogState.dismiss() }
                                    .padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                    item(key = "album") {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                        ) {
                            CreditsCircleCover(song = song, size = 78)
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    song.displayName,
                                    color = primary,
                                    fontSize = 17.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                val albumLabel = song.album.ifBlank {
                                    stringResource(R.string.song_credits_unknown_album)
                                }
                                if (albumRoute != null) {
                                    CreditsLinkText(
                                        text = albumLabel,
                                        onClick = { followAlbum(albumRoute) },
                                        fontSize = 13,
                                    )
                                } else {
                                    Text(
                                        albumLabel,
                                        color = secondary,
                                        fontSize = 13.sp,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                if (song.year > 0) {
                                    Text(song.year.toString(), color = secondary, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    item(key = "song_artists") {
                        CreditsSection(stringResource(R.string.song_credits_song_artists), primary) {
                            if (summary.songArtists.isEmpty()) {
                                Text(
                                    stringResource(R.string.player_unknown_artist),
                                    fontSize = 14.sp,
                                    color = secondary,
                                )
                            } else {
                                summary.songArtists.forEach { name ->
                                    CreditsLinkText(
                                        text = name,
                                        onClick = { followArtist(name) },
                                    )
                                }
                            }
                        }
                    }
                    if (summary.albumArtists.isNotEmpty()) {
                        item(key = "album_artist") {
                            CreditsSection(
                                stringResource(R.string.song_credits_album_artist),
                                primary,
                            ) {
                                summary.albumArtists.forEach { name ->
                                    CreditsLinkText(
                                        text = name,
                                        onClick = { followArtist(name) },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "participants_heading") {
                        CreditsSection(
                            stringResource(R.string.song_credits_album_participants),
                            primary,
                        ) {
                            Text(
                                stringResource(
                                    R.string.song_credits_totals,
                                    summary.participants.size,
                                    summary.trackCount,
                                    summary.collaborationTrackCount,
                                ),
                                color = secondary,
                                fontSize = 12.sp,
                            )
                            if (summary.participants.isEmpty()) {
                                Text(
                                    stringResource(R.string.song_credits_no_performers),
                                    color = secondary,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                    items(
                        items = summary.participants,
                        key = { "participant:${it.name.lowercase()}" },
                    ) { participant ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            CreditsCircleCover(participant.representative, 42)
                            Column(modifier = Modifier.weight(1f)) {
                                CreditsLinkText(
                                    text = participant.name,
                                    onClick = { followArtist(participant.name) },
                                )
                                Text(
                                    stringResource(
                                        R.string.song_credits_song_count,
                                        participant.songCount,
                                    ),
                                    fontSize = 12.sp,
                                    color = secondary,
                                )
                            }
                        }
                    }
                    if (summary.composers.isNotEmpty()) {
                        item(key = "composers") {
                            CreditsSection(
                                stringResource(R.string.song_credits_composers),
                                primary,
                            ) {
                                summary.composers.forEach { name ->
                                    CreditsLinkText(
                                        text = name,
                                        fontSize = 13,
                                        onClick = { followComposer(name) },
                                    )
                                }
                            }
                        }
                    }
                    item(key = "disclaimer") {
                        Text(
                            stringResource(R.string.song_credits_local_source),
                            color = secondary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CreditsLinkText(
    text: String,
    onClick: () -> Unit,
    fontSize: Int = 14,
) {
    Text(
        text = text,
        color = MiuixTheme.colorScheme.primary,
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Medium,
        textDecoration = TextDecoration.Underline,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 3.dp, horizontal = 2.dp),
    )
}

@Composable
private fun CreditsSection(
    title: String,
    color: Color,
    content: @Composable () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Text(title, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        content()
    }
}

@Composable
private fun CreditsCircleCover(song: AudioFile, size: Int) {
    val modifier = Modifier
        .size(size.dp)
        .clip(CircleShape)
    if (song.coverKey.isNotBlank()) {
        BitmapImage(
            key = song.coverKey,
            contentDescription = song.album,
            modifier = modifier,
            contentScale = ContentScale.Crop,
            targetWidth = size * 3,
            targetHeight = size * 3,
            priority = BitmapRequest.Priority.LOADING_LIST,
            surface = ArtworkSurface.MiniPlayer,
        )
    } else {
        Box(modifier.background(MiuixTheme.colorScheme.surfaceContainerHigh))
    }
}
