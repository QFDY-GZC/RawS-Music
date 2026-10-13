package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.common.utils.AudioUtils
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import com.rawsmusic.core.ui.theme.ThemeManager
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.virtuallist.AlbumVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.ArtistAllSongsVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.ArtistVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.ComposeGenericVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.VIRTUAL_LIST_ARTIST_ALL_SONGS_ENCODING
import com.rawsmusic.core.ui.widget.virtuallist.VIRTUAL_LIST_UNKNOWN_ALBUM_SENTINEL
import com.rawsmusic.core.ui.widget.virtuallist.VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListSectionHeader
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListVisualItem
import com.rawsmusic.core.ui.widget.virtuallist.formatVirtualListDuration
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.stableVirtualListHash64
import com.rawsmusic.core.ui.widget.virtuallist.retainedMappedList
import com.rawsmusic.module.data.prefs.CollectionSortPreferences
import com.rawsmusic.module.data.prefs.AppPreferences
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
    selectedArtistScene: NavScene = NavScene.ARTIST_DETAIL,
    onArtistClick: (String) -> Unit = {},
    onAlbumClick: (String) -> Unit = {},
    onOpenAllSongs: (String) -> Unit = {},
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    onOpenBiography: (String) -> Unit = {},
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("artists"),
    detailListState: ComposeVirtualListState = rememberComposeVirtualListState("artist_detail_songs"),
    modifier: Modifier = Modifier
) {
    val unknownArtistLabel = stringResource(com.rawsmusic.core.ui.R.string.common_unknown_artist)
    val groupedArtists by produceState(
        initialValue = LibrarySceneGroupingWarmup.artists(songs),
        key1 = songs,
    ) {
        value = LibrarySceneGroupingWarmup.loadArtists(songs)
    }
    val artists = remember(groupedArtists, dataSource?.artists) {
        if (groupedArtists.isNotEmpty()) {
            groupedArtists
        } else {
            dataSource?.artists.orEmpty().map { artist ->
                ArtistGroupUi(
                    key = artist.name.ifBlank { VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL },
                    name = artist.name.ifBlank { VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL },
                    songs = emptyList(),
                    albumCount = 0,
                    coverKey = "",
                    totalDurationMs = 0L
                )
            }
        }
    }
    var sortOrder by remember {
        mutableStateOf(
            CollectionSortPreferences.read("library_root", "artists", SortOrder.TITLE_ASC)
        )
    }
    val sortedArtists = remember(artists, sortOrder) {
        if (sortOrder == SortOrder.TITLE_ASC) artists else artists.sortedFor(sortOrder)
    }

    if (selectedArtistKey.isNullOrBlank()) {
        ArtistListPage(
            artists = sortedArtists,
            librarySongCount = songs.size,
            state = virtualListState,
            onBack = onBack,
            onArtistClick = onArtistClick,
            selectionActions = selectionActions,
            onShuffle = { onShuffle(sortedArtists.flatMap { it.songs }) },
            sortOrder = sortOrder,
            onSortOrderChange = {
                sortOrder = it
                CollectionSortPreferences.write("library_root", "artists", it)
            },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedArtistKey) { Uri.decode(selectedArtistKey) }
        val artist = remember(decodedKey, artists, songs) {
            artists.firstOrNull { it.key.equals(decodedKey, ignoreCase = true) }
                ?: com.rawsmusic.core.ui.widget.player.creditedSongsForArtist(decodedKey, songs)
                    .takeIf { it.isNotEmpty() }
                    ?.let { credited ->
                        val coverSong = credited.firstOrNull { it.albumArtPath.isNotBlank() }
                            ?: credited.first()
                        ArtistGroupUi(
                            key = decodedKey,
                            name = decodedKey,
                            songs = credited.sortedWith(
                                compareBy<AudioFile> { it.album }
                                    .thenBy { it.discNumber }
                                    .thenBy { it.trackNumber },
                            ),
                            albumCount = credited.map { it.album }.distinct().size,
                            coverKey = coverSong.coverKey,
                            totalDurationMs = credited.sumOf { it.duration.coerceAtLeast(0L) },
                        )
                    }
                ?: ArtistGroupUi.empty(decodedKey)
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        var biographyEnabled by remember(decodedKey) {
            mutableStateOf(AppPreferences.AlbumArt.artistBiographyEnabled)
        }
        var artworkViewerEnabled by remember(decodedKey) {
            mutableStateOf(AppPreferences.AlbumArt.artistArtworkViewerEnabled)
        }
        DisposableEffect(lifecycleOwner, decodedKey) {
            val observer = LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    biographyEnabled = AppPreferences.AlbumArt.artistBiographyEnabled
                    artworkViewerEnabled = AppPreferences.AlbumArt.artistArtworkViewerEnabled
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
        }

        var temporaryArtworkKey by remember(decodedKey) { mutableStateOf<String?>(null) }
        var artworkViewerVisible by remember(decodedKey) { mutableStateOf(false) }
        var artworkViewerOwnsHero by remember(decodedKey) { mutableStateOf(false) }
        var heroArtworkBounds by remember(decodedKey) { mutableStateOf<Rect?>(null) }
        val originalHero = remember(artist, artist.albumCount, unknownArtistLabel) {
            artist.toHeroData("", unknownArtistLabel)
        }
        val albumCountText = stringResource(com.rawsmusic.core.ui.R.string.library_album_count, artist.albumCount)
        val originalArtworkKey = originalHero.coverKey
        val currentArtworkKey = temporaryArtworkKey ?: originalArtworkKey
        val hero = remember(artist, albumCountText, currentArtworkKey, unknownArtistLabel) {
            artist.toHeroData(albumCountText, unknownArtistLabel).copy(coverKey = currentArtworkKey)
        }
        val artistAlbums = remember(artist.songs) { artist.songs.toAlbumGroups() }
        val allSongsTitle = stringResource(com.rawsmusic.core.ui.R.string.library_artist_all_songs)
        val allSongsCountText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_songs, artist.songCount)
        val allSongsHero = remember(hero, allSongsTitle, allSongsCountText) {
            hero.copy(
                sharedElementId = "cover:artist-all-songs:${stableVirtualListHash64(artist.key)}",
                title = allSongsTitle,
                subtitle = "",
                meta = allSongsCountText,
            )
        }

        Box(modifier = modifier) {
            val biographyAction = if (biographyEnabled) {
                { onOpenBiography(artist.key) }
            } else {
                null
            }
            val artworkLongPressAction = if (artworkViewerEnabled) {
                {
                    artworkViewerOwnsHero = false
                    artworkViewerVisible = true
                }
            } else {
                null
            }
            if (selectedArtistScene == NavScene.ARTIST_ALL_SONGS) {
                CollectionHeroDetailPage(
                    hero = allSongsHero,
                    listScene = NavScene.ARTIST_DETAIL,
                    detailScene = NavScene.ARTIST_ALL_SONGS,
                    songListState = detailListState,
                    onBack = onBack,
                    onPlayQueue = onPlayQueue,
                    onSongLongClick = onSongLongClick,
                    selectionActions = selectionActions,
                    onOpenFolder = onOpenFolder,
                    onShuffle = onShuffle,
                    onSearch = onSearch,
                    onBiography = biographyAction,
                    onHeroArtworkLongPress = artworkLongPressAction,
                    onHeroArtworkBoundsChanged = { heroArtworkBounds = it },
                    hideHeroArtwork = artworkViewerOwnsHero,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                ArtistAlbumsDetailPage(
                    hero = hero,
                    albums = artistAlbums,
                    listState = detailListState,
                    onBack = onBack,
                    onOpenAllSongs = { onOpenAllSongs(artist.key) },
                    onAlbumClick = onAlbumClick,
                    onPlayQueue = onPlayQueue,
                    onShuffle = onShuffle,
                    onOpenFolder = onOpenFolder,
                    onSearch = onSearch,
                    onBiography = biographyAction,
                    onHeroArtworkLongPress = artworkLongPressAction,
                    onHeroArtworkBoundsChanged = { heroArtworkBounds = it },
                    hideHeroArtwork = artworkViewerOwnsHero,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            if (artworkViewerVisible) {
                ArtistArtworkViewer(
                    artistName = artist.name,
                    originalArtworkKey = originalArtworkKey,
                    currentArtworkKey = currentArtworkKey,
                    sharedElementId = hero.sharedElementId,
                    heroBoundsInWindow = heroArtworkBounds,
                    onSelectArtwork = { selected ->
                        temporaryArtworkKey = selected.takeUnless { it == originalArtworkKey }
                    },
                    onRestoreArtwork = { temporaryArtworkKey = null },
                    onSharedOwnershipChanged = { artworkViewerOwnsHero = it },
                    onDismissed = {
                        artworkViewerOwnsHero = false
                        artworkViewerVisible = false
                    },
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(100f),
                )
            }
        }
    }
}

@Composable
private fun ArtistAlbumsDetailPage(
    hero: CollectionHeroData,
    albums: List<AlbumGroupUi>,
    listState: ComposeVirtualListState,
    onBack: () -> Unit,
    onOpenAllSongs: () -> Unit,
    onAlbumClick: (String) -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    onShuffle: (List<AudioFile>) -> Unit,
    onOpenFolder: () -> Unit,
    onSearch: () -> Unit,
    onBiography: (() -> Unit)?,
    onHeroArtworkLongPress: (() -> Unit)?,
    onHeroArtworkBoundsChanged: ((Rect) -> Unit)?,
    hideHeroArtwork: Boolean,
    modifier: Modifier = Modifier,
) {
    val chromeInfo = LocalLibraryChromeInfo.current
    val coverRegistry = LocalSharedCoverRegistry.current
    val unknownAlbumLabel = stringResource(com.rawsmusic.core.ui.R.string.player_unknown_album)
    val unknownArtistLabel = stringResource(com.rawsmusic.core.ui.R.string.common_unknown_artist)
    var showMoreDialog by remember(hero.stableKey) { mutableStateOf(false) }
    var showSortLayout by remember(hero.stableKey) { mutableStateOf(false) }
    var sortOrder by remember(hero.stableKey) {
        mutableStateOf(
            CollectionSortPreferences.read(
                ownerTag = "artist_detail_albums",
                stableKey = hero.stableKey,
                default = SortOrder.TITLE_ASC,
            )
        )
    }
    val sortedAlbums = remember(albums, sortOrder) { albums.sortedForArtistDetail(sortOrder) }
    val allSongsTitle = stringResource(com.rawsmusic.core.ui.R.string.library_artist_all_songs)
    val allSongsCountText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_songs, hero.songs.size)
    val allSongsItem = remember(hero.stableKey, allSongsTitle, allSongsCountText, hero.coverKey) {
        ArtistAllSongsVirtualListItem(
            artistKey = hero.stableKey,
            title = allSongsTitle,
            cover = hero.coverKey,
            songCountText = allSongsCountText,
        )
    }
    val albumItems = remember(sortedAlbums, unknownAlbumLabel, unknownArtistLabel) {
        retainedMappedList(sortedAlbums) { album ->
            AlbumVirtualListItem(
                key = album.key,
                name = album.name,
                artist = album.artist,
                cover = album.coverKey,
                songCount = album.songCount,
                totalDurationMs = album.totalDurationMs,
                unknownAlbumLabel = unknownAlbumLabel,
                unknownArtistLabel = unknownArtistLabel,
            )
        }
    }
    val items = remember(allSongsItem, albumItems) {
        object : AbstractList<VirtualListVisualItem>() {
            override val size: Int get() = albumItems.size + 1
            override fun get(index: Int): VirtualListVisualItem = when {
                index == 0 -> allSongsItem
                index in 1 until size -> albumItems[index - 1]
                else -> throw IndexOutOfBoundsException("index=$index size=$size")
            }
        }
    }
    val bottomPadding = LocalBottomChromeInsets.current.contentBottom
    val detailAlphabetIndex = rememberAdaptiveAlphabetIndexData(items) { item ->
        if (item is AlbumVirtualListItem) item.name else allSongsTitle
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val heroGeometry = resolveCollectionHeroWindowGeometry(
            scene = NavScene.ARTIST_DETAIL,
            window = LocalRawWindowLayoutProfile.current,
            statusBarTop = rawStableStatusBarTopPadding(),
        )
        ComposeGenericVirtualList(
                items = items,
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
                sharedCoverSceneId = NavScene.ARTIST_DETAIL.name,
                persistentHeaderHeight = heroGeometry.headerHeight,
                persistentHeaderVisibilityHeight = heroGeometry.headerHeight,
                persistentHeaderSceneItemVisibilityHeight = heroGeometry.artworkHeight,
                persistentHeaderSceneItemLeft = heroGeometry.artworkLeft,
                persistentHeaderSceneItemTop = heroGeometry.artworkTop,
                persistentHeaderSceneItemWidth = heroGeometry.artworkWidth,
                persistentHeaderSceneItemId = hero.sharedElementId,
                virtualListEdgeEnabled = true,
                persistentHeaderContent = { _, coverVisible ->
                    CollectionHeroHeader(
                        hero = hero,
                        geometry = heroGeometry,
                        listScene = NavScene.ARTISTS,
                        detailScene = NavScene.ARTIST_DETAIL,
                        coverVisible = coverVisible,
                        freezeArtworkUpdates = listState.isTransitioning,
                        onBack = onBack,
                        onMore = { showMoreDialog = true },
                        onShuffle = { onShuffle(hero.songs) },
                        onPlay = {
                            if (hero.songs.isNotEmpty()) onPlayQueue(hero.songs, 0)
                        },
                        onSearch = onSearch,
                        onSelect = null,
                        onBiography = onBiography,
                        onHeroArtworkLongPress = onHeroArtworkLongPress,
                        onHeroArtworkBoundsChanged = onHeroArtworkBoundsChanged,
                        hideHeroArtwork = hideHeroArtwork,
                        headerEdgeOvershootProvider = { listState.virtualListHeaderEdgeOvershootPx },
                        listEdgeOvershootProvider = { listState.virtualListEdgeOvershootPx },
                    )
                },
                contentBottomPadding = bottomPadding,
                onItemClick = { item, _, _ ->
                    // Same click contract as the root Albums/Artists lists: freeze the exact
                    // clicked holder under this scene before navigating, so ItemToHeader can
                    // promote it into the destination header instead of starting without a source.
                    when (item) {
                        is ArtistAllSongsVirtualListItem -> {
                            coverRegistry.freeze(
                                sceneId = NavScene.ARTIST_DETAIL.name,
                                elementId = item.sharedCoverElementId,
                            )
                            onOpenAllSongs()
                        }
                        is AlbumVirtualListItem -> {
                            coverRegistry.freeze(
                                sceneId = NavScene.ARTIST_DETAIL.name,
                                elementId = item.sharedCoverElementId,
                            )
                            onAlbumClick(item.key)
                        }
                        else -> Unit
                    }
                },
        )

        if (!com.rawsmusic.core.ui.scene.LocalReferenceLibraryFullScenePreflight.current &&
            !com.rawsmusic.core.ui.scene.LocalReferenceLibraryTopChromeOverlayOnly.current) {
            RawAlphabetIndex(
                data = detailAlphabetIndex,
                sceneId = NavScene.ARTIST_DETAIL.name,
                scrollActiveProvider = { listState.isListScrollInProgress },
                modifier = Modifier.align(Alignment.CenterEnd).padding(top = 92.dp, bottom = bottomPadding),
                onTopSelect = { listState.requestScrollToIndex(0) },
                onSelect = { _, index -> listState.requestScrollToIndex(index) },
            )
        }

        LibraryMoreDialog(
            visible = showMoreDialog,
            sourceOrder = chromeInfo.metadataMatchSources,
            onDismiss = { showMoreDialog = false },
            onChooseFolder = onOpenFolder,
            onOpenSort = { showSortLayout = true },
            onMoveSource = chromeInfo.onMoveMetadataSource,
            onAutoMatchCurrent = chromeInfo.onAutoMatchCurrent,
            onRematchAll = chromeInfo.onAutoRematchAll,
        )

        SongsSortLayoutSheet(
            visible = showSortLayout,
            currentSortOrder = sortOrder,
            virtualListState = listState,
            onSortSelected = { selected ->
                sortOrder = selected
                CollectionSortPreferences.write(
                    ownerTag = "artist_detail_albums",
                    stableKey = hero.stableKey,
                    value = selected,
                )
            },
            sortOptions = listOf(
                stringResource(com.rawsmusic.core.ui.R.string.sort_by_name) to SortOrder.TITLE_ASC,
                stringResource(com.rawsmusic.core.ui.R.string.sort_by_artist) to SortOrder.ARTIST_ASC,
                stringResource(com.rawsmusic.core.ui.R.string.sort_by_year) to SortOrder.YEAR_ASC,
                stringResource(com.rawsmusic.core.ui.R.string.sort_by_added) to SortOrder.DATE_ADDED_ASC,
                stringResource(com.rawsmusic.core.ui.R.string.sort_by_modified) to SortOrder.DATE_MODIFIED_ASC,
                stringResource(com.rawsmusic.core.ui.R.string.sort_by_duration) to SortOrder.DURATION_ASC,
                stringResource(com.rawsmusic.core.ui.R.string.sort_by_song_count) to SortOrder.PLAYBACK_INFO,
            ),
            onDismiss = { showSortLayout = false },
        )
    }
}

private fun List<AlbumGroupUi>.sortedForArtistDetail(order: SortOrder): List<AlbumGroupUi> {
    val descending = order in setOf(
        SortOrder.TITLE_DESC, SortOrder.ARTIST_DESC, SortOrder.ALBUM_DESC,
        SortOrder.DATE_ADDED_DESC, SortOrder.DATE_MODIFIED_DESC, SortOrder.DURATION_DESC,
        SortOrder.YEAR_DESC, SortOrder.FILE_NAME_DESC, SortOrder.PATH_DESC,
        SortOrder.PLAYBACK_INFO_DESC,
    )
    val comparator = when (order) {
        SortOrder.ARTIST_ASC, SortOrder.ARTIST_DESC ->
            compareBy<AlbumGroupUi> { it.artist.lowercase() }.thenBy { it.name.lowercase() }
        SortOrder.YEAR_ASC, SortOrder.YEAR_DESC ->
            compareBy { group -> group.songs.map { it.year }.filter { it > 0 }.minOrNull() ?: 0 }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy { it.totalDurationMs }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC ->
            compareBy { group -> group.songs.maxOfOrNull { it.dateAdded } ?: 0L }
        SortOrder.DATE_MODIFIED_ASC, SortOrder.DATE_MODIFIED_DESC ->
            compareBy { group -> group.songs.maxOfOrNull { it.dateModified } ?: 0L }
        SortOrder.PLAYBACK_INFO, SortOrder.PLAYBACK_INFO_DESC -> compareBy { it.songCount }
        else -> compareBy<AlbumGroupUi> { it.name.lowercase() }.thenBy { it.artist.lowercase() }
    }
    return sortedWith(if (descending) comparator.reversed() else comparator)
}

@Composable
private fun ArtistListPage(
    artists: List<ArtistGroupUi>,
    librarySongCount: Int,
    state: ComposeVirtualListState,
    onBack: () -> Unit,
    onArtistClick: (String) -> Unit,
    selectionActions: LibrarySongSelectionActions,
    onShuffle: () -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val context = LocalContext.current
    val unknownArtistLabel = stringResource(com.rawsmusic.core.ui.R.string.common_unknown_artist)
    val items = remember(artists, unknownArtistLabel, context.resources.configuration) {
        retainedMappedList(artists) { artist ->
            ArtistVirtualListItem(
                key = artist.key,
                name = artist.name,
                cover = artist.coverKey,
                songCount = artist.songCount,
                albumCount = artist.albumCount,
                totalDurationMs = artist.totalDurationMs,
                unknownArtistLabel = unknownArtistLabel,
                albumCountLabel = context.getString(
                    com.rawsmusic.core.ui.R.string.library_album_count,
                    artist.albumCount,
                ),
            )
        }
    }
    val warmedAlphabetIndex = remember(artists, sortOrder) {
        if (sortOrder == SortOrder.TITLE_ASC) LibrarySceneGroupingWarmup.artistsIndex(artists) else null
    }
    val alphabetIndexData = warmedAlphabetIndex
        ?: rememberAdaptiveAlphabetIndexData(artists) { it.name }

    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_artists),
        sceneId = NavScene.ARTISTS.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_artists, artists.size, librarySongCount),
        onBack = onBack,
        virtualListState = state,
        onShuffle = onShuffle,
        currentSortOrder = sortOrder,
        onSortSelected = onSortOrderChange,
        sortOptions = listOf(
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_name) to SortOrder.TITLE_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_album_count) to SortOrder.ALBUM_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_added) to SortOrder.DATE_ADDED_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_modified) to SortOrder.DATE_MODIFIED_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_duration) to SortOrder.DURATION_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_song_count) to SortOrder.PLAYBACK_INFO
        ),
        modifier = modifier
    ) { topPadding, backdropSource ->
        SelectableCollectionList(
            items = items,
            state = state,
            contentTopPadding = topPadding,
            sharedCoverSceneId = NavScene.ARTISTS.name,
            selectionActions = selectionActions,
            songsForIndex = { index -> artists.getOrNull(index)?.songs.orEmpty() },
            artworkSongForIndex = { index ->
                artists.getOrNull(index)?.songs?.let { artistSongs ->
                    artistSongs.firstOrNull { it.albumArtPath.isNotBlank() } ?: artistSongs.firstOrNull()
                }
            },
            modifier = Modifier.fillMaxSize().then(backdropSource),
            onItemClick = { item, _, _ ->
                val artist = item as? ArtistVirtualListItem ?: return@SelectableCollectionList

                coverRegistry.freeze(
                    sceneId = NavScene.ARTISTS.name,
                    elementId = artist.sharedCoverElementId
                )
                onArtistClick(artist.key)
            }
        )
        if (LocalReferenceLibraryProviderPublicationOnly.current) return@LibraryListScaffold

        RawAlphabetIndex(
            data = alphabetIndexData,
            sceneId = NavScene.ARTISTS.name,
            scrollActiveProvider = { state.isListScrollInProgress },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 92.dp, bottom = LocalBottomChromeInsets.current.contentBottom, end = 0.dp)
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
internal data class ArtistGroupUi(
    val key: String,
    val name: String,
    val songs: List<AudioFile>,
    val albumCount: Int,
    val coverKey: String,
    val totalDurationMs: Long
) {
    val songCount: Int get() = songs.size
    val sharedElementId: String get() = "cover:artist:${stableVirtualListHash64(key)}"

    fun toHeroData(albumCountText: String, unknownArtistLabel: String): CollectionHeroData {
        return CollectionHeroData(
            stableKey = key,
            sharedElementId = sharedElementId,
            coverKey = coverKey,
            title = name.takeUnless { it == VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL }
                .orEmpty().ifBlank { unknownArtistLabel },
            subtitle = albumCountText,
            meta = "$songCount | ${formatVirtualListDuration(totalDurationMs)}",
            songs = songs
        )
    }

    companion object {
        fun empty(key: String): ArtistGroupUi {
            return ArtistGroupUi(
                key = key,
                name = key.ifBlank { VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL },
                songs = emptyList(),
                albumCount = 0,
                coverKey = "",
                totalDurationMs = 0L
            )
        }
    }
}

internal fun List<AudioFile>.toArtistGroups(): List<ArtistGroupUi> {
    return asSequence()
        .filter { it.artist.isNotBlank() || it.albumArtist.isNotBlank() || it.displayName.isNotBlank() }
        .groupBy { song ->
            song.artist.ifBlank { song.albumArtist }.ifBlank { VIRTUAL_LIST_UNKNOWN_ARTIST_SENTINEL }
        }
        .map { (artistName, artistSongs) ->
            val coverSong = artistSongs.firstOrNull { it.albumArtPath.isNotBlank() } ?: artistSongs.firstOrNull()
            val albumCount = artistSongs
                .map { it.album.ifBlank { VIRTUAL_LIST_UNKNOWN_ALBUM_SENTINEL } }
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
        SortOrder.DATE_ADDED_DESC, SortOrder.DATE_MODIFIED_DESC, SortOrder.DURATION_DESC, SortOrder.YEAR_DESC,
        SortOrder.FILE_NAME_DESC, SortOrder.PATH_DESC, SortOrder.PLAYBACK_INFO_DESC
    )
    val comparator = when (order) {
        SortOrder.ALBUM_ASC, SortOrder.ALBUM_DESC -> compareBy<ArtistGroupUi> { it.albumCount }.thenBy { it.name.lowercase() }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy { it.totalDurationMs }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> compareBy { group -> group.songs.maxOfOrNull { it.dateAdded } ?: 0L }
        SortOrder.DATE_MODIFIED_ASC, SortOrder.DATE_MODIFIED_DESC -> compareBy { group -> group.songs.maxOfOrNull { it.dateModified } ?: 0L }
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
