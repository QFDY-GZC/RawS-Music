package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.powerlist.ArtistPowerListItem
import com.rawsmusic.core.ui.widget.powerlist.ComposeGenericPowerList
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.formatPowerListDuration
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.stablePowerListHash64
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

// ============================================================================
// 数据源：保留旧接口，避免外部调用处破坏。
// ============================================================================

class ArtistComposeDataSource {
    var artists: List<Artist> by mutableStateOf(emptyList())
        private set

    fun setData(list: List<Artist>) {
        artists = list
    }
}

class ArtistDetailComposeDataSource {
    var artistName: String by mutableStateOf("")
        private set
    var songs: List<AudioFile> by mutableStateOf(emptyList())
        private set
    var albums: List<Album> by mutableStateOf(emptyList())
        private set

    fun setData(name: String, songs: List<AudioFile>, albums: List<Album>) {
        this.artistName = name
        this.songs = songs
        this.albums = albums
    }

    fun clear() {
        artistName = ""
        songs = emptyList()
        albums = emptyList()
    }
}

internal data class ArtistThemeColors(
    val background: Color,
    val surface: Color,
    val onSurface: Color,
    val primary: Color,
    val secondaryText: Color,
    val outline: Color
)

@Composable
internal fun artistThemeColors(): ArtistThemeColors {
    val context = LocalContext.current
    val isDark = ThemeManager.isDarkMode(context)
    return if (isDark) {
        ArtistThemeColors(
            background = Color(0xFF2A2624),
            surface = Color(0xFF353130),
            onSurface = Color(0xFFEFEFEF),
            primary = Color(0xFFD4B896),
            secondaryText = Color(0xFF928A86),
            outline = Color(0xFF9F8D80)
        )
    } else {
        ArtistThemeColors(
            background = Color(0xFFF7F6F4),
            surface = Color(0xFFF5DED0),
            onSurface = Color(0xFF1F1F1F),
            primary = Color(0xFFC4956A),
            secondaryText = Color(0xFF857367),
            outline = Color(0xFFBAB3AF)
        )
    }
}

@Composable
fun ArtistsPage(
    songs: List<AudioFile> = emptyList(),
    dataSource: ArtistComposeDataSource? = null,
    selectedArtistKey: String? = null,
    onArtistClick: (String) -> Unit = {},
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    powerListState: ComposePowerListState = rememberComposePowerListState("artists"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("artist_detail_songs")
    val artists by remember(songs, dataSource?.artists) {
        derivedStateOf {
            val grouped = songs.toArtistGroups()
            if (grouped.isNotEmpty()) {
                grouped
            } else {
                dataSource?.artists.orEmpty().map { artist ->
                    ArtistGroupUi(
                        key = artist.name.ifBlank { "未知艺术家" },
                        name = artist.name.ifBlank { "未知艺术家" },
                        songs = emptyList(),
                        albumCount = 0,
                        coverKey = "",
                        totalDurationMs = 0L
                    )
                }
            }
        }
    }
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.TITLE_ASC) }
    val sortedArtists = remember(artists, sortOrder) { artists.sortedFor(sortOrder) }

    if (selectedArtistKey.isNullOrBlank()) {
        ArtistListPage(
            artists = sortedArtists,
            state = powerListState,
            onBack = onBack,
            onArtistClick = onArtistClick,
            onShuffle = { onShuffle(sortedArtists.flatMap { it.songs }) },
            sortOrder = sortOrder,
            onSortOrderChange = { sortOrder = it },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedArtistKey) { Uri.decode(selectedArtistKey) }
        val artist = remember(decodedKey, artists) {
            artists.firstOrNull { it.key == decodedKey } ?: ArtistGroupUi.empty(decodedKey)
        }

        CollectionHeroDetailPage(
            hero = artist.toHeroData(),
            listScene = NavScene.ARTISTS,
            detailScene = NavScene.ARTIST_DETAIL,
            songListState = detailState,
            onBack = onBack,
            onPlayQueue = onPlayQueue,
            onOpenFolder = onOpenFolder,
            onShuffle = onShuffle,
            onSearch = onSearch,
            modifier = modifier
        )
    }
}

@Composable
private fun ArtistListPage(
    artists: List<ArtistGroupUi>,
    state: ComposePowerListState,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    onShuffle: () -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val items = remember(artists) {
        artists.map { artist ->
            ArtistPowerListItem(
                key = artist.key,
                name = artist.name,
                cover = artist.coverKey,
                songCount = artist.songCount,
                albumCount = artist.albumCount,
                totalDurationMs = artist.totalDurationMs
            )
        }
    }
    val alphabetIndexData = rememberAdaptiveAlphabetIndexData(items) { it.title }

    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_artists),
        sceneId = NavScene.ARTISTS.name,
        onBack = onBack,
        powerListState = state,
        onShuffle = onShuffle,
        currentSortOrder = sortOrder,
        onSortSelected = onSortOrderChange,
        sortOptions = listOf(
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_name) to SortOrder.TITLE_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_album_count) to SortOrder.ALBUM_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_modified) to SortOrder.DATE_ADDED_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_duration) to SortOrder.DURATION_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_song_count) to SortOrder.PLAYBACK_INFO
        ),
        modifier = modifier
    ) { topPadding, backdropSource ->
        ComposeGenericPowerList(
            items = items,
            state = state,
            contentTopPadding = topPadding,
            sharedCoverSceneId = NavScene.ARTISTS.name,
            modifier = Modifier.fillMaxSize().then(backdropSource),
            onItemClick = { item, _, _ ->
                val artist = item as? ArtistPowerListItem ?: return@ComposeGenericPowerList

                coverRegistry.freeze(
                    sceneId = NavScene.ARTISTS.name,
                    elementId = artist.sharedCoverElementId
                )

                onArtistClick(artist.key)
            }
        )

        RawAlphabetIndex(
            data = alphabetIndexData,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 92.dp, bottom = 118.dp, end = 0.dp)
                .then(backdropSource)
                .zIndex(30f),
            onTopSelect = {
                state.requestScrollToIndex(0)
            },
            onSelect = { _, index ->
                state.requestScrollToIndex(index)
            }
        )
    }
}

@Stable
private data class ArtistGroupUi(
    val key: String,
    val name: String,
    val songs: List<AudioFile>,
    val albumCount: Int,
    val coverKey: String,
    val totalDurationMs: Long
) {
    val songCount: Int get() = songs.size
    val sharedElementId: String get() = "cover:artist:${stablePowerListHash64(key)}"

    fun toHeroData(): CollectionHeroData {
        return CollectionHeroData(
            stableKey = key,
            sharedElementId = sharedElementId,
            coverKey = coverKey,
            title = name,
            subtitle = "$albumCount 张专辑",
            meta = "$songCount | ${formatPowerListDuration(totalDurationMs)}",
            songs = songs
        )
    }

    companion object {
        fun empty(key: String): ArtistGroupUi {
            return ArtistGroupUi(
                key = key,
                name = key.ifBlank { "未知艺术家" },
                songs = emptyList(),
                albumCount = 0,
                coverKey = "",
                totalDurationMs = 0L
            )
        }
    }
}

private fun List<AudioFile>.toArtistGroups(): List<ArtistGroupUi> {
    return asSequence()
        .filter { it.artist.isNotBlank() || it.albumArtist.isNotBlank() || it.displayName.isNotBlank() }
        .groupBy { song ->
            song.artist.ifBlank { song.albumArtist }.ifBlank { "未知艺术家" }
        }
        .map { (artistName, artistSongs) ->
            val coverSong = artistSongs.firstOrNull { it.albumArtPath.isNotBlank() } ?: artistSongs.firstOrNull()
            val albumCount = artistSongs
                .map { it.album.ifBlank { "未知专辑" } }
                .distinct()
                .size
            ArtistGroupUi(
                key = artistName,
                name = artistName,
                songs = artistSongs.sortedWith(compareBy<AudioFile> { it.album }.thenBy { it.discNumber }.thenBy { it.trackNumber }.thenBy { it.displayName }),
                albumCount = albumCount,
                coverKey = coverSong?.coverKey.orEmpty(),
                totalDurationMs = artistSongs.sumOf { it.duration.coerceAtLeast(0L) }
            )
        }
        .sortedBy { it.name.lowercase() }
}

private fun List<ArtistGroupUi>.sortedFor(order: SortOrder): List<ArtistGroupUi> {
    val descending = order in setOf(
        SortOrder.TITLE_DESC, SortOrder.ARTIST_DESC, SortOrder.ALBUM_DESC,
        SortOrder.DATE_ADDED_DESC, SortOrder.DURATION_DESC, SortOrder.YEAR_DESC,
        SortOrder.FILE_NAME_DESC, SortOrder.PATH_DESC, SortOrder.PLAYBACK_INFO_DESC
    )
    val comparator = when (order) {
        SortOrder.ALBUM_ASC, SortOrder.ALBUM_DESC -> compareBy<ArtistGroupUi> { it.albumCount }.thenBy { it.name.lowercase() }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy { it.totalDurationMs }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> compareBy { group -> group.songs.maxOfOrNull { it.dateModified } ?: 0L }
        SortOrder.PLAYBACK_INFO, SortOrder.PLAYBACK_INFO_DESC -> compareBy { it.songCount }
        else -> compareBy<ArtistGroupUi> { it.name.lowercase() }
    }
    return sortedWith(if (descending) comparator.reversed() else comparator)
}

// ============================================================================
// 旧的嵌入详情保留，给外部旧入口兜底；新的 ARTIST_DETAIL 已走 CollectionHeroDetailPage。
// ============================================================================

@Composable
fun ArtistDetailScreenEmbedded(
    dataSource: ArtistDetailComposeDataSource,
    onBack: () -> Unit,
    onAlbumClick: (Album) -> Unit,
    onPlayAll: (List<AudioFile>) -> Unit,
    onPlaySong: (AudioFile, List<AudioFile>) -> Unit
) {
    val colors = artistThemeColors()
    val backgroundColor = colors.background
    val name = dataSource.artistName
    val songs = dataSource.songs

    Column(
        Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.size(40.dp)) {
                Icon(
                    painter = painterResource(com.rawsmusic.core.ui.R.drawable.ic_back),
                    contentDescription = stringResource(com.rawsmusic.core.ui.R.string.library_action_back),
                    tint = colors.secondaryText
                )
            }
            Text(
                name,
                color = colors.onSurface,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(colors.primary)
                .clickable { onPlayAll(songs) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(com.rawsmusic.core.ui.R.drawable.ic_playlist_play),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                stringResource(com.rawsmusic.core.ui.R.string.library_play_all_count, songs.size),
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
        Spacer(Modifier.height(12.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            items(songs) { song ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { onPlaySong(song, songs) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(song.displayName, color = colors.onSurface, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(song.album, color = colors.secondaryText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(AudioUtils.formatDuration(song.duration), color = colors.secondaryText, fontSize = 13.sp)
                }
            }
        }
    }
}
