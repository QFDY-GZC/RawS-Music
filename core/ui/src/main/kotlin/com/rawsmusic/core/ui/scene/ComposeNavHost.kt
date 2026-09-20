package com.rawsmusic.core.ui.scene

import android.net.Uri
import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.FolderHierarchyNode
import com.rawsmusic.core.common.model.Playlist
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.pages.AboutPage
import com.rawsmusic.core.ui.scene.pages.AlbumsPage
import com.rawsmusic.core.ui.scene.pages.ArtistBiographyPage
import com.rawsmusic.core.ui.scene.pages.ArtistComposeDataSource
import com.rawsmusic.core.ui.scene.pages.ArtistsPage
import com.rawsmusic.core.ui.scene.pages.ComposersPage
import com.rawsmusic.core.ui.scene.pages.Daily20Page
import com.rawsmusic.core.ui.scene.pages.FoldersPage
import com.rawsmusic.core.ui.scene.pages.FolderHierarchyPage
import com.rawsmusic.core.ui.scene.pages.FolderHierarchyPageState
import com.rawsmusic.core.ui.scene.pages.GenresPage
import com.rawsmusic.core.ui.scene.pages.YearsPage
import com.rawsmusic.core.ui.scene.pages.HomePage
import com.rawsmusic.core.ui.scene.pages.HomeArtworkCarouselState
import com.rawsmusic.core.ui.scene.pages.HomeHeaderOptionsState
import com.rawsmusic.core.ui.scene.pages.HomeCardLayoutState
import com.rawsmusic.core.ui.scene.pages.LogViewerPage
import com.rawsmusic.core.ui.scene.pages.LibraryChromeInfo
import com.rawsmusic.core.ui.scene.pages.LibrarySceneGroupingWarmup
import com.rawsmusic.core.ui.scene.pages.LocalLibraryChromeInfo
import com.rawsmusic.core.ui.scene.pages.MetadataMatchSourceUi
import com.rawsmusic.core.ui.scene.pages.PlaylistsPage
import com.rawsmusic.core.ui.scene.pages.LibrarySongSelectionActions
import com.rawsmusic.core.ui.scene.pages.QueuePage
import com.rawsmusic.core.ui.scene.pages.RecentlyAddedPage
import com.rawsmusic.core.ui.scene.pages.SongStatsPage
import com.rawsmusic.core.ui.scene.pages.LibraryAnalysisPageState
import com.rawsmusic.core.ui.scene.pages.SongsPage
import com.rawsmusic.core.ui.scene.pages.SettingsRootPage
import com.rawsmusic.core.ui.scene.pages.SourceImportPage
import com.rawsmusic.core.ui.scene.pages.ScanSettingsPage
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.ReferenceLibraryProviderRegistry
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListPersistentRuntime
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderIdentity
import com.rawsmusic.core.ui.widget.virtuallist.stableVirtualListHash64
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.geometry.Rect
import io.github.proify.lyricon.lyric.model.Song

/**
 * 导航回调集合。
 * 由 MainActivity 设置，传递给各个页面。
 */
data class NavCallbacks(
    val onSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    val onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    val onAlbumClick: (Album) -> Unit = {},
    val onAlbumItemClick: (Album) -> Unit = {},
    val onArtistClick: (Artist) -> Unit = {},
    val onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    val onPlaylistClick: (Playlist) -> Unit = {},
    val onFolderClick: (Folder) -> Unit = {},
    val onFolderHierarchyClick: (Folder) -> Unit = {},
    val onHomeCarouselSongClick: (List<AudioFile>, AudioFile, Int) -> Unit = { _, _, _ -> },
    val onHomeCarouselNavigate: (Int) -> Unit = {},
    val onQueueSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    val onRecentlyAddedClick: (AudioFile, Int) -> Unit = { _, _ -> },
    val onPlayAll: (List<AudioFile>) -> Unit = {},
    val onShuffleAll: (List<AudioFile>) -> Unit = {},
    val onSearchClick: (GlobalSearchScope?) -> Unit = {},
    val onNavigateToPlayer: () -> Unit = {},
    val onMiniPlayerPlayPause: () -> Unit = {},
    val onMiniPlayerPrevious: () -> Unit = {},
    val onMiniPlayerNext: () -> Unit = {},
    val onMiniPlayerExpandDragStart: () -> Unit = {},
    val onMiniPlayerExpandDragProgress: (Float) -> Unit = {},
    val onMiniPlayerExpandDragEnd: (Boolean, Float) -> Unit = { _, _ -> },
    val onPlayerSeek: (Long) -> Unit = {},
    val onOpenFolderPicker: () -> Unit = {},
    val onSortClick: () -> Unit = {},
    val onSongSortSelected: (SortOrder) -> Unit = {},
    val onSelectionAddToPlaylist: (List<AudioFile>) -> Unit = {},
    val onSelectionAddToQueue: (List<AudioFile>) -> Unit = {},
    val onSelectionDelete: (List<AudioFile>) -> Unit = {},
    val onSelectionPlayNext: (List<AudioFile>) -> Unit = {},
    val onSelectionTranscode: (List<AudioFile>) -> Unit = {},
    val onSelectionBatchMatchLyrics: (List<AudioFile>) -> Unit = {},
    val onSelectionAutoMatch: (List<AudioFile>) -> Unit = {},
    val onSelectionClearQueue: () -> Unit = {},
    val onMoveMetadataSource: (String, Int) -> Unit = { _, _ -> },
    val onAutoMatchCurrent: () -> Unit = {},
    val onAutoRematchAll: () -> Unit = {},
    val onSongsSelectionModeChanged: (Boolean) -> Unit = {},
    val onSongsRefresh: () -> Unit = {},
    val onRequestLegacyAudioAccess: () -> Unit = {},
    val onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    val onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    val onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    val onMiniPlayerCoverBoundsChanged: (RectF?) -> Unit = {},
    val onMiniPlayerCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {}
)

/**
 * 导航数据集合。
 * 由 MainActivity 通过 submit 方法更新。
 */
data class NavData(
    val songs: List<AudioFile> = emptyList(),
    val folderHierarchy: List<FolderHierarchyNode> = emptyList(),
    val currentPlayingIndex: Int = -1,
    val currentSong: AudioFile? = null,
    /** Live MiniPlayer identity; library/current-row state may remain frozen during a scene move. */
    val miniPlayerSong: AudioFile? = null,
    val queueSongs: List<AudioFile> = emptyList(),
    val queueCurrentIndex: Int = -1,
    val homeCarouselSongs: List<AudioFile> = emptyList(),
    val homeCarouselCurrentIndex: Int = -1,
    val miniPlayerTitle: String = "",
    val miniPlayerArtist: String = "",
    val miniPlayerLyric: String = "",
    val miniPlayerLyricTranslation: String = "",
    val lyricSong: Song? = null,
    val miniPlayerIsPlaying: Boolean = false,
    val miniPlayerProgress: Float = 0f,
    val miniPlayerPreviousSong: AudioFile? = null,
    val miniPlayerNextSong: AudioFile? = null,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val nextSongTitle: String = "",
    val miniPlayerCoverPath: String? = null,
    val playerReturnRevealIndex: Int = -1,
    val hidePlayingCover: Boolean = false,
    val currentSortOrder: SortOrder = SortOrder.TITLE_ASC,
    val artistDataSource: ArtistComposeDataSource? = null,
    val playCounts: Map<Long, Int> = emptyMap(),
    val metadataMatchSources: List<MetadataMatchSourceUi> = emptyList(),
    val metadataMatchProgressText: String = "",
    val bottomChromeHidden: Boolean = false,
    val uiForeground: Boolean = true,
    /** Progress is rendered inside the current scene while the first Room snapshot is prepared. */
    val libraryStartupLoading: Boolean = false,
    val libraryStartupLoadingAlpha: Float = 0f,
)

/**
 * 纯 Compose 导航宿主。
 *
 * @param externalPageRenderer 外部页面渲染器，用于 app 模块中依赖 AppPreferences 等的页面。
 */
@Composable
fun ComposeNavHost(
    state: NavigationState,
    callbacks: NavCallbacks,
    data: NavData,
    modifier: Modifier = Modifier,
    externalPageRenderer: ExternalPageRenderer? = null,
    showHomeSettingsShortcut: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onHomeHeaderMenuAction: (() -> Unit)? = null,
    homeCarouselState: HomeArtworkCarouselState,
    homeHeaderOptions: HomeHeaderOptionsState,
    homeCardLayoutState: HomeCardLayoutState,
    renderHomeBackdrop: Boolean = true,
    homeFullCoverActive: Boolean = false,
    homeFullCoverCenterReflectionAlpha: Float = 1f,
    homeFullCoverCenterReflectionArtworkKey: String = "",
    onHomeCarouselCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit = {},
    onHomeCarouselCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit = { _, _ -> },
    sceneGestureExclusionBounds: Rect? = null,
    sceneGesturesEnabled: Boolean = true,
    onSceneTransitionActiveChanged: (Boolean) -> Unit = {},
    onSceneTransitionFrameChanged: (SceneTransitionFrame) -> Unit = {},
) {
    val homeListState = rememberScrollState()
    val libraryAnalysisState = remember { LibraryAnalysisPageState() }
    val libraryAnalysisRootListState = rememberComposeVirtualListState("library_analysis_root")
    val libraryAnalysisDetailListState = rememberComposeVirtualListState("library_analysis_detail")
    // HOME is a VirtualList provider too. Hoist its state beside every other root provider so leaving
    // the HOME branch cannot destroy its scroll owner and recreate it at 0 on return.
    val homeVirtualListState = rememberComposeVirtualListState("home")
    val songsVirtualListState = rememberComposeVirtualListState("songs")
    val foldersVirtualListState = rememberComposeVirtualListState("folders")
    val folderHierarchyVirtualListState = rememberComposeVirtualListState("folder_hierarchy")
    val folderHierarchyPageState = remember { FolderHierarchyPageState() }
    val albumsVirtualListState = rememberComposeVirtualListState("albums")
    val albumDetailVirtualListState = rememberComposeVirtualListState("album_detail_songs")
    val artistsVirtualListState = rememberComposeVirtualListState("artists")
    val artistDetailVirtualListState = rememberComposeVirtualListState("artist_detail_songs")
    val artistBiographyVirtualListState = rememberComposeVirtualListState("artist_biography")
    val genresVirtualListState = rememberComposeVirtualListState("genres")
    val genreDetailVirtualListState = rememberComposeVirtualListState("genre_detail_songs")
    val yearsVirtualListState = rememberComposeVirtualListState("years")
    val yearDetailVirtualListState = rememberComposeVirtualListState("year_detail_songs")
    val composersVirtualListState = rememberComposeVirtualListState("composers")
    val composerDetailVirtualListState = rememberComposeVirtualListState("composer_detail_songs")
    val folderDetailVirtualListState = rememberComposeVirtualListState("folder_detail_songs")
    val playlistsVirtualListState = rememberComposeVirtualListState("playlists")
    // An offscreen collection-header return should behave like a transient VirtualList detail
    // provider on the *next* entry. Do not mutate the retired detail viewport during the back
    // commit itself: that LayoutRes is still retained for the endpoint handoff and changing its
    // scroll there produces a full-screen flash. Keep a plain pending marker and consume it before
    // the next forward navigation instead.
    val freshDetailEntryPending = remember { mutableSetOf<NavScene>() }
    // Queue/recently-added are ordinary persistent library providers. Keep their viewport state
    // beside the other root providers so provider swaps never recreate a scroll owner.
    val queueVirtualListState = rememberComposeVirtualListState("queue")
    val recentlyAddedVirtualListState = rememberComposeVirtualListState("recently_added")
    val songSelectionActions = remember(callbacks) {
        LibrarySongSelectionActions(
            addToPlaylist = callbacks.onSelectionAddToPlaylist,
            addToQueue = callbacks.onSelectionAddToQueue,
            delete = callbacks.onSelectionDelete,
            playNext = callbacks.onSelectionPlayNext,
            transcode = callbacks.onSelectionTranscode,
            batchMatchLyrics = callbacks.onSelectionBatchMatchLyrics,
            autoMatch = callbacks.onSelectionAutoMatch,
            clearQueue = callbacks.onSelectionClearQueue,
            onSelectionModeChanged = callbacks.onSongsSelectionModeChanged,
        )
    }
    // The library VirtualList is a process/UI-owner object, not a page object. Settings and PLAYER
    // cover the library but must not dispose its physical holder/LayoutRes population; doing so
    // recreates the presentation at scroll=0 for one frame before the hoisted state restores the
    // remembered position. Keep both provider registry and physical runtime alive for the complete
    // ComposeNavHost lifetime and only clear them when the whole navigation host is disposed.
    val persistentLibraryRegistry = remember { ReferenceLibraryProviderRegistry() }
    val persistentLibraryRuntime = remember { VirtualListPersistentRuntime() }
    DisposableEffect(persistentLibraryRuntime) {
        onDispose { persistentLibraryRuntime.clear() }
    }
    // Scroll anchors are provider-owned state.  Do not reset every root library provider when HOME
    // becomes current; that historical workaround directly violated VirtualList scroll restore and
    // made a scene switch look like a renderer/layout failure.

    // Root provider metadata belongs to the library snapshot, not to the transition that happens to
    // reveal it.  Warm it while the persistent navigation owner is settled so HOME -> category
    // PivotTransition only binds already-built provider/LayoutRes data, matching retained-view implementation's VirtualList
    // transition boundary and keeping grouping/index work out of the 250 ms animation window.
    LaunchedEffect(data.songs, data.queueSongs, data.queueCurrentIndex) {
        LibrarySceneGroupingWarmup.prewarmRootProviders(data.songs)
    }


    CompositionLocalProvider(
        LocalLibraryChromeInfo provides LibraryChromeInfo(
            nowPlayingTitle = data.miniPlayerTitle,
            nextSongTitle = data.nextSongTitle,
            showNextQueueHint = data.miniPlayerIsPlaying &&
                data.playbackDurationMs > 0L &&
                (data.playbackDurationMs - data.playbackPositionMs) in 1L..10_000L &&
                data.nextSongTitle.isNotBlank(),
            onSearch = {
                callbacks.onSearchClick(GlobalSearchScope.fromScene(state.currentScene))
            },
            onOpenFolderPicker = callbacks.onOpenFolderPicker,
            currentSortOrder = data.currentSortOrder,
            onSortSelected = callbacks.onSongSortSelected,
            metadataMatchSources = data.metadataMatchSources,
            metadataMatchProgressText = data.metadataMatchProgressText,
            onMoveMetadataSource = callbacks.onMoveMetadataSource,
            onAutoMatchCurrent = callbacks.onAutoMatchCurrent,
            onAutoRematchAll = callbacks.onAutoRematchAll,
        )
    ) {
    Box(modifier = modifier) {
        SceneTransitionHost(
            state = state,
            modifier = Modifier.fillMaxSize(),
            horizontalGestureExclusionBounds = sceneGestureExclusionBounds,
            gesturesEnabled = sceneGesturesEnabled,
            providerBackGesture = folderHierarchyPageState.contentBackGesture.takeIf {
                state.currentScene == NavScene.FOLDER_HIERARCHY
            },
            onTransitionActiveChanged = onSceneTransitionActiveChanged,
            onTransitionFrameChanged = onSceneTransitionFrameChanged,
            onCollectionDetailOffscreenReturnCommitted = { detailScene ->
                when (detailScene) {
                    NavScene.ALBUM_DETAIL,
                    NavScene.ARTIST_DETAIL,
                    NavScene.GENRE_DETAIL,
                    NavScene.YEAR_DETAIL,
                    NavScene.COMPOSER_DETAIL,
                    NavScene.FOLDER_DETAIL -> freshDetailEntryPending += detailScene
                    else -> Unit
                }
            },
            persistentLibraryContent = { scene, presentationVisible, controllerVisible, renderScene ->
                // Keep the one HOME/category owner at SceneTransitionHost level. The page renderer
                // remains a provider/chrome source; it is no longer the parent that carries the
                // persistent presentation host through route changes.
                ReferenceLibraryVirtualListHost(
                    requestedScene = scene,
                    registry = persistentLibraryRegistry,
                    physicalRuntime = persistentLibraryRuntime,
                    presentationVisible = presentationVisible,
                    controllerVisible = controllerVisible,
                    providerIdentity = { providerScene ->
                        if (providerScene.isDetail()) state.currentArgument else ""
                    },
                    modifier = Modifier.fillMaxSize(),
                    content = renderScene,
                )
            },
        ) { scene ->
            @Composable
            fun RenderScene(scene: NavScene) {
                val onBack: () -> Unit = { state.navigateBackAnimated() }
                // SceneTransitionHost owns the one SharedCoverRegistry used by source freeze,
                // pair matching, physical promotion and return finalization. Capture that same
                // registry here, inside its CompositionLocalProvider. Reading the local at the
                // outer ComposeNavHost level produced a second/default registry: list clicks froze
                // the real source while navigation published the NEXT header into an invisible
                // registry, so every cold identity missed once and only worked after composition
                // later published the target into the real owner.
                val sharedCoverRegistry = LocalSharedCoverRegistry.current
                val density = LocalDensity.current
                val configuration = LocalConfiguration.current
                val collectionHeaderWidthPx = with(density) {
                    configuration.screenWidthDp.dp.roundToPx()
                }

                fun collectionDetailSharedElementId(
                    detailScene: NavScene,
                    decodedArgument: String,
                ): String {
                    val hash = stableVirtualListHash64(decodedArgument)
                    return when (detailScene) {
                        NavScene.ALBUM_DETAIL -> "cover:album:$hash"
                        NavScene.ARTIST_DETAIL -> "cover:artist:$hash"
                        NavScene.FOLDER_DETAIL -> "cover:folder:$hash"
                        NavScene.GENRE_DETAIL -> "cover:genre:$hash"
                        NavScene.YEAR_DETAIL -> "cover:year:$hash"
                        NavScene.COMPOSER_DETAIL -> "cover:composer:$hash"
                        else -> ""
                    }
                }

                fun navigateToCollectionDetail(
                    detailScene: NavScene,
                    encodedArgument: String,
                    detailListState: ComposeVirtualListState,
                ) {
                    val decodedArgument = Uri.decode(encodedArgument)
                    val sharedElementId =
                        collectionDetailSharedElementId(detailScene, decodedArgument)
                    if (sharedElementId.isNotBlank()) {
                        sharedCoverRegistry.registerSynchronousSceneHeaderEndpoint(
                            sceneId = detailScene.name,
                            elementId = sharedElementId,
                            widthPx = collectionHeaderWidthPx,
                            sceneItemHeightPx = collectionHeaderWidthPx,
                        )
                    }
                    if (state.currentScene != detailScene ||
                        state.currentArgument != encodedArgument
                    ) {
                        detailListState.resetViewportToTopForFreshEntry()
                    }
                    freshDetailEntryPending.remove(detailScene)
                    state.navigateTo(detailScene, encodedArgument)
                }
                val roleProviderIdentity = LocalReferenceLibraryProviderIdentity.current
                val roleArgument = if (scene.isDetail() && roleProviderIdentity.isNotBlank()) {
                    roleProviderIdentity
                } else {
                    state.currentArgument
                }

                when (scene) {
            NavScene.HOME -> HomePage(
                songs = data.songs,
                currentSong = data.currentSong,
                queueSongs = data.homeCarouselSongs,
                queueCurrentIndex = data.homeCarouselCurrentIndex,
                currentLyric = data.miniPlayerLyric,
                currentLyricTranslation = data.miniPlayerLyricTranslation,
                lyricSong = data.lyricSong,
                playbackPositionMs = data.playbackPositionMs,
                isPlaying = data.miniPlayerIsPlaying,
                playCounts = data.playCounts,
                listState = homeListState,
                virtualListState = homeVirtualListState,
                carouselState = homeCarouselState,
                renderBackdrop = renderHomeBackdrop,
                onNavigate = { targetScene -> state.navigateTo(targetScene) },
                onSearchClick = { callbacks.onSearchClick(null) },
                showSettingsShortcut = showHomeSettingsShortcut,
                onSettingsClick = onSettingsClick,
                onHeaderMenuActionOverride = onHomeHeaderMenuAction,
                headerOptions = homeHeaderOptions,
                homeCardLayoutState = homeCardLayoutState,
                onCurrentPlayPause = callbacks.onMiniPlayerPlayPause,
                onSongClick = callbacks.onSongClick,
                onQueueNavigate = callbacks.onHomeCarouselNavigate,
                onCarouselSongSelect = callbacks.onHomeCarouselSongClick,
                onCurrentArtworkLongPress = onHomeCarouselCurrentArtworkLongPress,
                onCurrentArtworkBoundsChanged = onHomeCarouselCurrentArtworkBoundsChanged,
                hideCenterForFullscreenTransition = homeFullCoverActive,
                centerReflectionAlpha = homeFullCoverCenterReflectionAlpha,
                centerReflectionArtworkKey = homeFullCoverCenterReflectionArtworkKey,
            )

            NavScene.DAILY_20 -> Daily20Page(
                songs = data.songs,
                onBack = onBack,
                onSongClick = callbacks.onSongClick,
                onPlayQueue = callbacks.onPlayQueue
            )

            NavScene.SOURCE_IMPORT -> SourceImportPage(
                onBack = onBack,
                modifier = Modifier.fillMaxSize(),
            )

            NavScene.SONGS -> SongsPage(
                songs = data.songs,
                currentPlayingIndex = data.currentPlayingIndex,
                currentSortOrder = data.currentSortOrder,
                playerReturnRevealIndex = data.playerReturnRevealIndex,
                miniPlayerTitle = data.miniPlayerTitle,
                miniPlayerArtist = data.miniPlayerArtist,
                miniPlayerIsPlaying = data.miniPlayerIsPlaying,
                miniPlayerProgress = data.miniPlayerProgress,
                playbackPositionMs = data.playbackPositionMs,
                playbackDurationMs = data.playbackDurationMs,
                nextSongTitle = data.nextSongTitle,
                miniPlayerCoverPath = data.miniPlayerCoverPath,
                hidePlayingCover = data.hidePlayingCover,
                onBack = onBack,
                onSongClick = callbacks.onSongClick,
                onSongLongClick = callbacks.onSongLongClick,
                onMiniPlayerClick = callbacks.onNavigateToPlayer,
                onMiniPlayerPlayPause = callbacks.onMiniPlayerPlayPause,
                onMiniPlayerPrevious = callbacks.onMiniPlayerPrevious,
                onMiniPlayerNext = callbacks.onMiniPlayerNext,
                onOpenFolderPicker = callbacks.onOpenFolderPicker,
                onOpenGlobalSearch = { callbacks.onSearchClick(GlobalSearchScope.SONG) },
                onSortClick = callbacks.onSortClick,
                onShuffleAll = callbacks.onShuffleAll,
                onSortSelected = callbacks.onSongSortSelected,
                onSelectionAddToPlaylist = callbacks.onSelectionAddToPlaylist,
                onSelectionAddToQueue = callbacks.onSelectionAddToQueue,
                onSelectionDelete = callbacks.onSelectionDelete,
                onSelectionPlayNext = callbacks.onSelectionPlayNext,
                onSelectionTranscode = callbacks.onSelectionTranscode,
                onSelectionBatchMatchLyrics = callbacks.onSelectionBatchMatchLyrics,
                onSelectionAutoMatch = callbacks.onSelectionAutoMatch,
                metadataMatchSources = data.metadataMatchSources,
                metadataMatchProgressText = data.metadataMatchProgressText,
                onMoveMetadataSource = callbacks.onMoveMetadataSource,
                onAutoMatchCurrent = callbacks.onAutoMatchCurrent,
                onAutoRematchAll = callbacks.onAutoRematchAll,
                onSelectionModeChanged = callbacks.onSongsSelectionModeChanged,
                virtualListState = songsVirtualListState,
                onPlayingCoverBoundsChanged = callbacks.onPlayingCoverBoundsChanged,
                onPlayingCoverTargetChanged = callbacks.onPlayingCoverTargetChanged,
                onRevealCoverTargetResolved = callbacks.onRevealCoverTargetResolved,
                onMiniPlayerCoverBoundsChanged = callbacks.onMiniPlayerCoverBoundsChanged
            )

            NavScene.FOLDERS -> FoldersPage(
                songs = data.songs,
                selectedFolderPath = null,
                onBack = onBack,
                onFolderClick = { folderPath ->
                    navigateToCollectionDetail(
                        NavScene.FOLDER_DETAIL,
                        Uri.encode(folderPath),
                        folderDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.FOLDER) },
                virtualListState = foldersVirtualListState
            )
            NavScene.ALBUMS -> AlbumsPage(
                songs = data.songs,
                selectedAlbumKey = null,
                onBack = onBack,
                onAlbumClick = { albumKey ->
                    navigateToCollectionDetail(
                        NavScene.ALBUM_DETAIL,
                        Uri.encode(albumKey),
                        albumDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ALBUM) },
                virtualListState = albumsVirtualListState
            )

            NavScene.ARTISTS -> ArtistsPage(
                songs = data.songs,
                dataSource = data.artistDataSource,
                selectedArtistKey = null,
                onArtistClick = { artistKey ->
                    navigateToCollectionDetail(
                        NavScene.ARTIST_DETAIL,
                        Uri.encode(artistKey),
                        artistDetailVirtualListState,
                    )
                },
                onBack = onBack,
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ARTIST) },
                virtualListState = artistsVirtualListState
            )

            NavScene.PLAYLISTS -> {
                val handled = externalPageRenderer?.RenderPage(
                    scene = scene,
                    onBack = onBack,
                    argument = state.currentArgument,
                    virtualListState = playlistsVirtualListState,
                ) ?: false
                if (!handled) {
                    PlaylistsPage(onBack = onBack)
                }
            }
            NavScene.QUEUE -> QueuePage(
                songs = data.queueSongs,
                currentIndex = data.queueCurrentIndex,
                onBack = onBack,
                onSongClick = callbacks.onQueueSongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                virtualListState = queueVirtualListState,
            )
            NavScene.RECENTLY_ADDED -> RecentlyAddedPage(
                songs = data.songs,
                onBack = onBack,
                onSongClick = callbacks.onRecentlyAddedClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                virtualListState = recentlyAddedVirtualListState,
            )
            // WEBDAV 由外部渲染器处理（依赖 AppPreferences）
            NavScene.ABOUT -> AboutPage(onBack = onBack)
            NavScene.SONG_STATS, NavScene.LIBRARY_ANALYSIS_DETAIL -> SongStatsPage(
                pageState = libraryAnalysisState,
                bucketArgument = if (scene == NavScene.LIBRARY_ANALYSIS_DETAIL) state.currentArgument else null,
                rootListState = libraryAnalysisRootListState,
                detailListState = libraryAnalysisDetailListState,
                onOpenBucket = { argument -> state.navigateTo(NavScene.LIBRARY_ANALYSIS_DETAIL, argument) },
                songs = data.songs,
                currentPlayingId = data.currentSong?.id ?: -1L,
                onBack = onBack,
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                onShuffle = callbacks.onShuffleAll,
            )
            NavScene.LOG_VIEWER -> LogViewerPage(onBack = onBack)

            NavScene.GENRE -> GenresPage(
                songs = data.songs,
                selectedGenreKey = null,
                onBack = onBack,
                onGenreClick = { genreKey ->
                    navigateToCollectionDetail(
                        NavScene.GENRE_DETAIL,
                        Uri.encode(genreKey),
                        genreDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.GENRE) },
                virtualListState = genresVirtualListState
            )

            NavScene.YEAR -> YearsPage(
                songs = data.songs,
                selectedYearKey = null,
                onBack = onBack,
                onYearClick = { yearKey ->
                    navigateToCollectionDetail(
                        NavScene.YEAR_DETAIL,
                        Uri.encode(yearKey),
                        yearDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.YEAR) },
                virtualListState = yearsVirtualListState
            )

            NavScene.COMPOSER -> ComposersPage(
                songs = data.songs,
                selectedComposerKey = null,
                onBack = onBack,
                onComposerClick = { composerKey ->
                    navigateToCollectionDetail(
                        NavScene.COMPOSER_DETAIL,
                        Uri.encode(composerKey),
                        composerDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.COMPOSER) },
                virtualListState = composersVirtualListState
            )

            NavScene.FOLDER_HIERARCHY -> FolderHierarchyPage(
                pageState = folderHierarchyPageState,
                songs = data.songs,
                hierarchy = data.folderHierarchy,
                onBack = onBack,
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                virtualListState = folderHierarchyVirtualListState,
                predictiveBackEnabled = sceneGesturesEnabled,
            )

            NavScene.FOLDER_DETAIL -> FoldersPage(
                songs = data.songs,
                selectedFolderPath = roleArgument,
                detailListState = folderDetailVirtualListState,
                onBack = onBack,
                onFolderClick = { folderPath ->
                    navigateToCollectionDetail(
                        NavScene.FOLDER_DETAIL,
                        Uri.encode(folderPath),
                        folderDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.FOLDER) },
                virtualListState = foldersVirtualListState
            )

            NavScene.ALBUM_DETAIL -> AlbumsPage(
                songs = data.songs,
                selectedAlbumKey = roleArgument,
                detailListState = albumDetailVirtualListState,
                onBack = onBack,
                onAlbumClick = { albumKey ->
                    navigateToCollectionDetail(
                        NavScene.ALBUM_DETAIL,
                        Uri.encode(albumKey),
                        albumDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ALBUM) },
                virtualListState = albumsVirtualListState
            )

            NavScene.ARTIST_DETAIL -> ArtistsPage(
                songs = data.songs,
                dataSource = data.artistDataSource,
                selectedArtistKey = roleArgument,
                detailListState = artistDetailVirtualListState,
                onArtistClick = { artistKey ->
                    navigateToCollectionDetail(
                        NavScene.ARTIST_DETAIL,
                        Uri.encode(artistKey),
                        artistDetailVirtualListState,
                    )
                },
                onBack = onBack,
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ARTIST) },
                onOpenBiography = { artistKey ->
                    state.navigateTo(NavScene.ARTIST_BIOGRAPHY, Uri.encode(artistKey))
                },
                virtualListState = artistsVirtualListState
            )

            NavScene.ARTIST_BIOGRAPHY -> ArtistBiographyPage(
                artistName = Uri.decode(roleArgument),
                listState = artistBiographyVirtualListState,
                onBack = onBack,
            )

            NavScene.GENRE_DETAIL -> GenresPage(
                songs = data.songs,
                selectedGenreKey = roleArgument,
                detailListState = genreDetailVirtualListState,
                onBack = onBack,
                onGenreClick = { genreKey ->
                    navigateToCollectionDetail(
                        NavScene.GENRE_DETAIL,
                        Uri.encode(genreKey),
                        genreDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.GENRE) },
                virtualListState = genresVirtualListState
            )

            NavScene.YEAR_DETAIL -> YearsPage(
                songs = data.songs,
                selectedYearKey = roleArgument,
                detailListState = yearDetailVirtualListState,
                onBack = onBack,
                onYearClick = { yearKey ->
                    navigateToCollectionDetail(
                        NavScene.YEAR_DETAIL,
                        Uri.encode(yearKey),
                        yearDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.YEAR) },
                virtualListState = yearsVirtualListState
            )

            NavScene.COMPOSER_DETAIL -> ComposersPage(
                songs = data.songs,
                selectedComposerKey = roleArgument,
                detailListState = composerDetailVirtualListState,
                onBack = onBack,
                onComposerClick = { composerKey ->
                    navigateToCollectionDetail(
                        NavScene.COMPOSER_DETAIL,
                        Uri.encode(composerKey),
                        composerDetailVirtualListState,
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onSongLongClick = callbacks.onSongLongClick,
                selectionActions = songSelectionActions,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.COMPOSER) },
                virtualListState = composersVirtualListState
            )

            NavScene.PLAYLIST_DETAIL -> FoldersPage(onBack = onBack)

            NavScene.SETTINGS -> {
                val handled = externalPageRenderer?.RenderPage(scene, onBack, state.currentArgument) ?: false
                if (!handled) {
                    SettingsRootPage(
                        onBack = onBack,
                        onNavigate = { target -> state.navigateToSettings(target) }
                    )
                }
            }

            NavScene.SCAN_SETTINGS -> ScanSettingsPage(
                onBack = onBack,
                onRescan = callbacks.onSongsRefresh,
                onRequestLegacyAudioAccess = callbacks.onRequestLegacyAudioAccess
            )

            // 优先尝试外部渲染器，未处理则显示占位
            else -> {
                val handled = externalPageRenderer?.RenderPage(scene, onBack, state.currentArgument) ?: false
                if (!handled) {
                    FoldersPage(onBack = onBack)
                }
                }
            }
            }

            // During a HOME/category handoff the transition host consumes this renderer through
            // persistentLibraryContent. For transitions involving a non-library scene, render the
            // page normally; keeping this lambda complete avoids a blank HOME page on e.g.
            // SETTINGS -> HOME where the persistent owner is not active yet.
            RenderScene(scene)
        }

        // Reference keeps this indicator in the existing scene. The header, background and
        // navigation chrome remain mounted; only the list body reports that the first coherent
        // Room/index snapshot is still being prepared.
        if (data.libraryStartupLoading && data.libraryStartupLoadingAlpha > 0.001f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = 96.dp, bottom = LocalBottomChromeInsets.current.contentBottom + 24.dp)
                    .alpha(data.libraryStartupLoadingAlpha)
                    .zIndex(10f),
                contentAlignment = Alignment.Center,
            ) {
                // Mirrors the retained-view implementation's ItemEmptyListScanProgressCenter resource:
                // Android's native Material indeterminate ProgressBar at 24dp,
                // without a panel, Material3 tint, or startup text.
                AndroidView(
                    modifier = Modifier.size(24.dp),
                    factory = { context ->
                        android.widget.ProgressBar(
                            context,
                            null,
                            0,
                            android.R.style.Widget_Material_ProgressBar,
                        ).apply {
                            isIndeterminate = true
                        }
                    },
                    update = { it.isIndeterminate = true },
                )
            }
        }
    }
}
}
