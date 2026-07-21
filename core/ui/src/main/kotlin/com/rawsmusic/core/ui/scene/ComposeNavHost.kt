package com.rawsmusic.core.ui.scene

import android.net.Uri
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import android.graphics.RectF
import com.rawsmusic.core.common.model.Album
import com.rawsmusic.core.common.model.Artist
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.Folder
import com.rawsmusic.core.common.model.Playlist
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.pages.AboutPage
import com.rawsmusic.core.ui.scene.pages.AlbumsPage
import com.rawsmusic.core.ui.scene.pages.ArtistComposeDataSource
import com.rawsmusic.core.ui.scene.pages.ArtistsPage
import com.rawsmusic.core.ui.scene.pages.ComposersPage
import com.rawsmusic.core.ui.scene.pages.Daily20Page
import com.rawsmusic.core.ui.scene.pages.FoldersPage
import com.rawsmusic.core.ui.scene.pages.GenresPage
import com.rawsmusic.core.ui.scene.pages.YearsPage
import com.rawsmusic.core.ui.scene.pages.HomePage
import com.rawsmusic.core.ui.scene.pages.LogViewerPage
import com.rawsmusic.core.ui.scene.pages.LibraryChromeInfo
import com.rawsmusic.core.ui.scene.pages.LocalLibraryChromeInfo
import com.rawsmusic.core.ui.scene.pages.PlaylistsPage
import com.rawsmusic.core.ui.scene.pages.QueuePage
import com.rawsmusic.core.ui.scene.pages.RecentlyAddedPage
import com.rawsmusic.core.ui.scene.pages.SongStatsPage
import com.rawsmusic.core.ui.scene.pages.SongsPage
import com.rawsmusic.core.ui.scene.pages.SettingsRootPage
import com.rawsmusic.core.ui.scene.pages.ScanSettingsPage
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import androidx.compose.foundation.lazy.rememberLazyListState

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
    val onQueueSongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    val onRecentlyAddedClick: (AudioFile, Int) -> Unit = { _, _ -> },
    val onPlayAll: (List<AudioFile>) -> Unit = {},
    val onShuffleAll: (List<AudioFile>) -> Unit = {},
    val onSearchClick: (GlobalSearchScope?) -> Unit = {},
    val onNavigateToPlayer: () -> Unit = {},
    val onMiniPlayerPlayPause: () -> Unit = {},
    val onMiniPlayerPrevious: () -> Unit = {},
    val onMiniPlayerNext: () -> Unit = {},
    val onOpenFolderPicker: () -> Unit = {},
    val onSortClick: () -> Unit = {},
    val onSongSortSelected: (SortOrder) -> Unit = {},
    val onSelectionAddToPlaylist: (List<AudioFile>) -> Unit = {},
    val onSelectionAddToQueue: (List<AudioFile>) -> Unit = {},
    val onSelectionDelete: (List<AudioFile>) -> Unit = {},
    val onSelectionPlayNext: (List<AudioFile>) -> Unit = {},
    val onSongsSelectionModeChanged: (Boolean) -> Unit = {},
    val onSongsRefresh: () -> Unit = {},
    val onRequestLegacyAudioAccess: () -> Unit = {},
    val onPlayingCoverBoundsChanged: (RectF?) -> Unit = {},
    val onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    val onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    val onMiniPlayerCoverBoundsChanged: (RectF?) -> Unit = {}
)

/**
 * 导航数据集合。
 * 由 MainActivity 通过 submit 方法更新。
 */
data class NavData(
    val songs: List<AudioFile> = emptyList(),
    val currentPlayingIndex: Int = -1,
    val currentSong: AudioFile? = null,
    val queueSongs: List<AudioFile> = emptyList(),
    val queueCurrentIndex: Int = -1,
    val miniPlayerTitle: String = "",
    val miniPlayerArtist: String = "",
    val miniPlayerLyric: String = "",
    val miniPlayerLyricTranslation: String = "",
    val miniPlayerIsPlaying: Boolean = false,
    val miniPlayerProgress: Float = 0f,
    val playbackPositionMs: Long = 0L,
    val playbackDurationMs: Long = 0L,
    val nextSongTitle: String = "",
    val miniPlayerCoverPath: String? = null,
    val playerReturnRevealIndex: Int = -1,
    val hidePlayingCover: Boolean = false,
    val currentSortOrder: SortOrder = SortOrder.TITLE_ASC,
    val artistDataSource: ArtistComposeDataSource? = null,
    val playCounts: Map<Long, Int> = emptyMap(),
    val bottomChromeHidden: Boolean = false,
    val uiForeground: Boolean = true
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
) {
    val homeListState = rememberLazyListState()
    val songsPowerListState = rememberComposePowerListState("songs")
    val foldersPowerListState = rememberComposePowerListState("folders")
    val albumsPowerListState = rememberComposePowerListState("albums")
    val artistsPowerListState = rememberComposePowerListState("artists")
    val genresPowerListState = rememberComposePowerListState("genres")
    val yearsPowerListState = rememberComposePowerListState("years")
    val composersPowerListState = rememberComposePowerListState("composers")

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
            onSortSelected = callbacks.onSongSortSelected
        )
    ) {
    SceneTransitionHost(
        state = state,
        modifier = modifier,
        prewarmScenes = emptyList()
    ) { scene ->
        val onBack: () -> Unit = { state.navigateBackAnimated() }

        when (scene) {
            NavScene.HOME -> HomePage(
                songs = data.songs,
                currentSong = data.currentSong,
                playCounts = data.playCounts,
                listState = homeListState,
                onNavigate = { targetScene -> state.navigateTo(targetScene) },
                onSearchClick = { callbacks.onSearchClick(null) },
                showSettingsShortcut = showHomeSettingsShortcut,
                onSettingsClick = onSettingsClick,
                onSongClick = callbacks.onSongClick,
                onPlayQueue = callbacks.onPlayQueue
            )

            NavScene.DAILY_20 -> Daily20Page(
                songs = data.songs,
                onBack = onBack,
                onSongClick = callbacks.onSongClick,
                onPlayQueue = callbacks.onPlayQueue
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
                onSelectionModeChanged = callbacks.onSongsSelectionModeChanged,
                powerListState = songsPowerListState,
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
                    state.navigateTo(
                        NavScene.FOLDER_HIERARCHY,
                        Uri.encode(folderPath)
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.FOLDER) },
                powerListState = foldersPowerListState
            )
            NavScene.ALBUMS -> AlbumsPage(
                songs = data.songs,
                selectedAlbumKey = null,
                onBack = onBack,
                onAlbumClick = { albumKey ->
                    state.navigateTo(
                        NavScene.ALBUM_DETAIL,
                        Uri.encode(albumKey)
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ALBUM) },
                powerListState = albumsPowerListState
            )

            NavScene.ARTISTS -> ArtistsPage(
                songs = data.songs,
                dataSource = data.artistDataSource,
                selectedArtistKey = null,
                onArtistClick = { artistKey ->
                    state.navigateTo(
                        NavScene.ARTIST_DETAIL,
                        Uri.encode(artistKey)
                    )
                },
                onBack = onBack,
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ARTIST) },
                powerListState = artistsPowerListState
            )

            NavScene.PLAYLISTS -> {
                val handled = externalPageRenderer?.RenderPage(scene, onBack, state.currentArgument) ?: false
                if (!handled) {
                    PlaylistsPage(onBack = onBack)
                }
            }
            NavScene.QUEUE -> QueuePage(
                songs = data.queueSongs,
                currentIndex = data.queueCurrentIndex,
                onBack = onBack,
                onSongClick = callbacks.onQueueSongClick,
                onShuffle = callbacks.onShuffleAll
            )
            NavScene.RECENTLY_ADDED -> RecentlyAddedPage(
                songs = data.songs,
                onBack = onBack,
                onSongClick = callbacks.onRecentlyAddedClick,
                onShuffle = callbacks.onShuffleAll
            )
            // WEBDAV 由外部渲染器处理（依赖 AppPreferences）
            NavScene.ABOUT -> AboutPage(onBack = onBack)
            NavScene.SONG_STATS -> SongStatsPage(onBack = onBack)
            NavScene.LOG_VIEWER -> LogViewerPage(onBack = onBack)

            NavScene.GENRE -> GenresPage(
                songs = data.songs,
                selectedGenreKey = null,
                onBack = onBack,
                onGenreClick = { genreKey ->
                    state.navigateTo(NavScene.GENRE_DETAIL, Uri.encode(genreKey))
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.GENRE) },
                powerListState = genresPowerListState
            )

            NavScene.YEAR -> YearsPage(
                songs = data.songs,
                selectedYearKey = null,
                onBack = onBack,
                onYearClick = { yearKey ->
                    state.navigateTo(NavScene.YEAR_DETAIL, Uri.encode(yearKey))
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.YEAR) },
                powerListState = yearsPowerListState
            )

            NavScene.COMPOSER -> ComposersPage(
                songs = data.songs,
                selectedComposerKey = null,
                onBack = onBack,
                onComposerClick = { composerKey ->
                    state.navigateTo(NavScene.COMPOSER_DETAIL, Uri.encode(composerKey))
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.COMPOSER) },
                powerListState = composersPowerListState
            )

            NavScene.FOLDER_HIERARCHY -> FoldersPage(
                songs = data.songs,
                selectedFolderPath = state.currentArgument,
                onBack = onBack,
                onFolderClick = { folderPath ->
                    state.navigateTo(
                        NavScene.FOLDER_HIERARCHY,
                        Uri.encode(folderPath)
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.FOLDER) },
                powerListState = foldersPowerListState
            )

            NavScene.ALBUM_DETAIL -> AlbumsPage(
                songs = data.songs,
                selectedAlbumKey = state.currentArgument,
                onBack = onBack,
                onAlbumClick = { albumKey ->
                    state.navigateTo(
                        NavScene.ALBUM_DETAIL,
                        Uri.encode(albumKey)
                    )
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ALBUM) },
                powerListState = albumsPowerListState
            )

            NavScene.ARTIST_DETAIL -> ArtistsPage(
                songs = data.songs,
                dataSource = data.artistDataSource,
                selectedArtistKey = state.currentArgument,
                onArtistClick = { artistKey ->
                    state.navigateTo(
                        NavScene.ARTIST_DETAIL,
                        Uri.encode(artistKey)
                    )
                },
                onBack = onBack,
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.ARTIST) },
                powerListState = artistsPowerListState
            )

            NavScene.GENRE_DETAIL -> GenresPage(
                songs = data.songs,
                selectedGenreKey = state.currentArgument,
                onBack = onBack,
                onGenreClick = { genreKey ->
                    state.navigateTo(NavScene.GENRE_DETAIL, Uri.encode(genreKey))
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.GENRE) },
                powerListState = genresPowerListState
            )

            NavScene.YEAR_DETAIL -> YearsPage(
                songs = data.songs,
                selectedYearKey = state.currentArgument,
                onBack = onBack,
                onYearClick = { yearKey ->
                    state.navigateTo(NavScene.YEAR_DETAIL, Uri.encode(yearKey))
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.YEAR) },
                powerListState = yearsPowerListState
            )

            NavScene.COMPOSER_DETAIL -> ComposersPage(
                songs = data.songs,
                selectedComposerKey = state.currentArgument,
                onBack = onBack,
                onComposerClick = { composerKey ->
                    state.navigateTo(NavScene.COMPOSER_DETAIL, Uri.encode(composerKey))
                },
                onPlayQueue = callbacks.onPlayQueue,
                onShuffle = callbacks.onShuffleAll,
                onOpenFolder = callbacks.onOpenFolderPicker,
                onSearch = { callbacks.onSearchClick(GlobalSearchScope.COMPOSER) },
                powerListState = composersPowerListState
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
    }
}
