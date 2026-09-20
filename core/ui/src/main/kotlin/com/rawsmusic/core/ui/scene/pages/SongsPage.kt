package com.rawsmusic.core.ui.scene.pages

import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.State
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.ui.zIndex
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalAppHazeState
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.LocalRetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.LocalReferenceRetainedLibraryPopulation
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryFullScenePreflight
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryTopChromeOverlayOnly
import com.rawsmusic.core.ui.scene.LocalExternalRetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.LocalSceneChromeAlpha
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsSurface
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsMode
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.TopChromeStyle
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndexData
import com.rawsmusic.core.ui.widget.index.RawIndexMode
import com.rawsmusic.core.ui.widget.ActivityOverlayBackOwner
import com.rawsmusic.core.ui.widget.MiuixOverlayBackRuntime
import com.rawsmusic.core.ui.widget.predictiveBottomSheetMotion
import com.rawsmusic.core.ui.widget.predictiveBottomSheetScrim
import com.rawsmusic.core.ui.widget.predictiveDialogMotion
import com.rawsmusic.core.ui.widget.rememberPredictiveDialogProgress
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListScrollProgressObserver
import com.rawsmusic.core.ui.widget.virtuallist.ListZoomIndex
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.core.ui.scene.pages.floatingActionVisibilityProgress
import com.rawsmusic.core.ui.scene.pages.TOP_CHROME_COLLAPSE_DISTANCE_DP
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import com.rawsmusic.core.ui.widget.text.SharedMarqueeText
import com.rawsmusic.core.ui.widget.player.OriginalArtworkViewerDialog
import com.rawsmusic.core.ui.widget.player.shareSelectedAudio
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.HazeProgressive
import dev.chrisbanes.haze.blur.blurEffect
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Close
import top.yukonga.miuix.kmp.icon.extended.Delete
import top.yukonga.miuix.kmp.icon.extended.ExpandLess
import top.yukonga.miuix.kmp.icon.extended.ExpandMore
import top.yukonga.miuix.kmp.icon.extended.ListView
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import com.rawsmusic.core.ui.systemui.rawStableNavigationBarsPadding

/**
 * 歌曲列表页面。
 */
@Composable
fun SongsPage(
    songs: List<AudioFile>,
    currentPlayingIndex: Int,
    currentSortOrder: SortOrder = SortOrder.TITLE_ASC,
    playerReturnRevealIndex: Int = -1,
    miniPlayerTitle: String,
    miniPlayerArtist: String,
    miniPlayerIsPlaying: Boolean,
    miniPlayerProgress: Float,
    playbackPositionMs: Long,
    playbackDurationMs: Long,
    nextSongTitle: String,
    miniPlayerCoverPath: String?,
    hidePlayingCover: Boolean = false,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    onMiniPlayerClick: () -> Unit,
    onMiniPlayerPlayPause: () -> Unit,
    onMiniPlayerPrevious: () -> Unit,
    onMiniPlayerNext: () -> Unit,
    onOpenFolderPicker: () -> Unit,
    onOpenGlobalSearch: () -> Unit,
    onSortClick: () -> Unit,
    onShuffleAll: (List<AudioFile>) -> Unit,
    onSortSelected: (SortOrder) -> Unit = {},
    onSelectionAddToPlaylist: (List<AudioFile>) -> Unit = {},
    onSelectionAddToQueue: (List<AudioFile>) -> Unit = {},
    onSelectionDelete: (List<AudioFile>) -> Unit = {},
    onSelectionPlayNext: (List<AudioFile>) -> Unit = {},
    onSelectionTranscode: (List<AudioFile>) -> Unit = {},
    onSelectionBatchMatchLyrics: (List<AudioFile>) -> Unit = {},
    onSelectionAutoMatch: (List<AudioFile>) -> Unit = {},
    metadataMatchSources: List<MetadataMatchSourceUi> = emptyList(),
    metadataMatchProgressText: String = "",
    onMoveMetadataSource: (String, Int) -> Unit = { _, _ -> },
    onAutoMatchCurrent: () -> Unit = {},
    onAutoRematchAll: () -> Unit = {},
    onSelectionModeChanged: (Boolean) -> Unit = {},
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState(),
    onPlayingCoverBoundsChanged: (android.graphics.RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onMiniPlayerCoverBoundsChanged: (android.graphics.RectF?) -> Unit = {}
) {
    var showSortSheet by remember { mutableStateOf(false) }
    var showMoreDialog by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showSelectionSheet by remember { mutableStateOf(false) }
    val floatingModalAlpha by animateFloatAsState(
        targetValue = if (showSelectionSheet || MiuixOverlayBackRuntime.activeCount > 0) 0f else 1f,
        animationSpec = tween(200),
        label = "songs-floating-actions-modal-alpha",
    )

    val density = LocalDensity.current
    val backgroundColor = MiuixTheme.colorScheme.background
    val isLightGlass = backgroundColor.luminance() > 0.5f
    // Lyric uses Haze source/effect nodes so the top material stays synchronized with
    // scrolling RenderNodes instead of replaying a separately recorded Compose layer.
    val localSongsListHazeState = rememberHazeState()
    val songsListHazeState = LocalAppHazeState.current ?: localSongsListHazeState

    val visibleSongs = songs

    val selectedSongs = remember(visibleSongs, selectedSongIds) {
        if (selectedSongIds.isEmpty()) emptyList() else visibleSongs.filter { it.id in selectedSongIds }
    }

    val selectedPositions = remember(visibleSongs, selectedSongIds, selectionMode) {
        val actual = if (selectedSongIds.isEmpty()) {
            emptySet()
        } else {
            visibleSongs.mapIndexedNotNull { index, song ->
                if (song.id in selectedSongIds) index else null
            }.toSet()
        }
        if (selectionMode && actual.isEmpty()) setOf(-1) else actual
    }

    val emptyAlphabetIndexData = remember { RawAlphabetIndexData(emptyList(), emptyMap(), RawIndexMode.AUTO) }
    val retainedSceneItemTransform = LocalRetainedSceneItemTransform.current
    val warmedAlphabetIndexData = remember(visibleSongs, currentSortOrder) {
        LibrarySceneGroupingWarmup.songsIndex(visibleSongs)
    }
    val alphabetIndexData by produceState(
        initialValue = warmedAlphabetIndexData ?: emptyAlphabetIndexData,
        key1 = visibleSongs,
        key2 = currentSortOrder,
        key3 = retainedSceneItemTransform,
    ) {
        if (visibleSongs.isEmpty()) {
            value = emptyAlphabetIndexData
            return@produceState
        }
        LibrarySceneGroupingWarmup.songsIndex(visibleSongs)?.let { warmed ->
            value = warmed
            return@produceState
        }
        // Fingerprinting/index construction are O(N) and compete with GRID_3/GRID_4 holder bind,
        // rendering and image admission even when dispatched away from Main. the retained-view implementation's list
        // transition consumes already-owned provider metadata; do not start Raw's synthetic side
        // index while PivotTransition owns the scene. A cold process can show an empty rail until the
        // transition settles, then the dedicated low-priority metadata lane builds it once.
        if (retainedSceneItemTransform != null) {
            value = emptyAlphabetIndexData
            return@produceState
        }
        val exact = LibrarySceneGroupingWarmup.loadSongsIndex(visibleSongs)
        if (exact.targets.isNotEmpty()) {
            value = exact
        }
    }
    val statusBarTop = unconsumedStatusBarTopPadding()

    val toolbarContentHeight = LIBRARY_TOOLBAR_CONTENT_HEIGHT
    val toolbarTotalHeight = statusBarTop + toolbarContentHeight
    val topChromeStyle by PersonalizationPreferences.topChromeStyle.collectAsState()
    val bottomButtonsMode by PersonalizationPreferences.libraryBottomButtonsMode.collectAsState()
    val floatingStyleSelected = topChromeStyle == TopChromeStyle.FLOATING ||
        topChromeStyle == TopChromeStyle.FLOATING_VERTICAL
    val floatingChromeSelected = floatingStyleSelected &&
        bottomButtonsMode != LibraryBottomButtonsMode.DISABLED
    val headerButtonsEnabled by PersonalizationPreferences.libraryHeaderButtonsEnabled.collectAsState()
    // If the floating action owner is not active, the fixed top bar is the only remaining owner
    // for search/more/shuffle. Do not let a stale header-buttons preference hide both owners.
    val effectiveHeaderButtonsEnabled = headerButtonsEnabled || !floatingChromeSelected
    val bottomButtonsSurface by PersonalizationPreferences.libraryBottomButtonsSurface.collectAsState()
    val listContentTopPadding = if (floatingChromeSelected) {
        statusBarTop + LIBRARY_CONTENT_MIN_INSET
    } else {
        (toolbarTotalHeight + LIBRARY_CONTENT_TOP_GUARD - LIBRARY_CONTENT_OVERLAP)
            .coerceAtLeast(statusBarTop + LIBRARY_CONTENT_MIN_INSET)
    }
    val normalBottomPadding = LocalBottomChromeInsets.current.contentBottom
    val stableSystemBottom = rawStableNavigationBottomPadding()
    val selectionListBottomPadding by animateDpAsState(
        // retained-view implementation raises its normal 90dp list reserve by 60dp while selection controls own the
        // bottom edge: 150dp plus the stable navigation-bar inset. Raw's ordinary bottom chrome is
        // hidden in selection mode, so derive this endpoint directly instead of adding to a value
        // that becomes zero as soon as AppMainLayout removes the MiniPlayer/navigation owner.
        targetValue = if (selectionMode) 198.dp + stableSystemBottom else normalBottomPadding,
        animationSpec = tween(
            durationMillis = if (selectionMode) 500 else 200,
            easing = SelectionTransitionEasing,
        ),
        label = "songs-selection-bottom-reserve",
    )

    fun clearSelection() {
        selectionMode = false
        selectedSongIds = emptySet()
        showSelectionSheet = false
    }

    fun openSelectionActions(ids: Set<Long>) {
        selectedSongIds = ids
        selectionMode = true
        showSelectionSheet = true
    }

    fun toggleSelectAll() {
        val allIds = visibleSongs.map { it.id }.toSet()
        selectedSongIds = if (allIds.isNotEmpty() && selectedSongIds.containsAll(allIds)) {
            emptySet()
        } else {
            allIds
        }
    }

    fun toggleSelectionRange() {
        val positions = visibleSongs.mapIndexedNotNull { index, song ->
            if (song.id in selectedSongIds) index else null
        }
        if (positions.size < 2) return
        val first = positions.minOrNull() ?: return
        val last = positions.maxOrNull() ?: return
        if (last - first <= 1) return
        val insideIds = (first + 1 until last).map { visibleSongs[it].id }.toSet()
        selectedSongIds = if (insideIds.all { it in selectedSongIds }) {
            selectedSongIds - insideIds
        } else {
            selectedSongIds + insideIds
        }
    }

    LaunchedEffect(selectionMode) {
        onSelectionModeChanged(selectionMode)
    }

    val topChromeCollapseDistancePx = with(density) { TOP_CHROME_COLLAPSE_DISTANCE_DP.dp.toPx() }
    val sceneChrome = LocalSceneChromeAlpha.current
    val topChromeSceneRole = sceneChrome.topChromeRole(NavScene.SONGS.name)
    val sceneTopMenuAlpha = sceneChrome.topMenu
    val sceneTopMenuAlphaProvider = sceneChrome.topMenuProvider
    var listMotionActive by remember { mutableStateOf(false) }
    val performanceMode by PersonalizationPreferences.performanceMode.collectAsState()
    val transparentTopChrome = topChromeStyle == TopChromeStyle.TRANSPARENT
    val presentationTopClipInset = if (transparentTopChrome) {
        toolbarTotalHeight + LIBRARY_CONTENT_TOP_GUARD
    } else {
        0.dp
    }
    val retainedLibraryPopulation = LocalReferenceRetainedLibraryPopulation.current
    // Do not switch Haze/clip visual branches merely because HOME/category motion starts. The
    // VirtualList population is already retained; changing the chrome renderer on the first gesture
    // frame is more expensive and visibly flashes even when the list itself has no empty frame.
    val needsFloatingHaze = floatingChromeSelected &&
        bottomButtonsSurface == LibraryBottomButtonsSurface.FROSTED &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val topBackdropBlurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !transparentTopChrome
    val topBackdropBlurActive = topBackdropBlurSupported
    val transparentGlassDuringMotion = performanceMode && listMotionActive
    val showNextQueueHint = miniPlayerIsPlaying &&
        playbackDurationMs > 0L &&
        (playbackDurationMs - playbackPositionMs) in 1L..10_000L &&
        nextSongTitle.isNotBlank()
    val libraryTitle = stringResource(R.string.songs_music_library_title)
    if (LocalReferenceLibraryTopChromeOverlayOnly.current) {
        LibraryTopChromeTransitionOverlay(
            title = libraryTitle,
            sceneId = NavScene.SONGS.name,
            statisticsText = stringResource(R.string.library_statistics_songs, visibleSongs.size),
            virtualListState = virtualListState,
            onBack = onBack,
            onSelect = { openSelectionActions(visibleSongs.map { it.id }.toSet()) },
            onPlay = {
                visibleSongs.firstOrNull()?.let { first -> onSongClick(first, 0) }
            },
            onSearch = onOpenGlobalSearch,
            onShuffle = { onShuffleAll(visibleSongs) },
            onMore = {},
            onCreatePlaylist = null,
            onImportPlaylist = null,
            nowPlayingTitle = miniPlayerTitle,
            nextSongTitle = nextSongTitle,
            showNextQueueHint = showNextQueueHint,
            metadataMatchProgressText = metadataMatchProgressText,
            showNowPlayingLocator = true,
            onLocateNowPlaying = { virtualListState.requestScrollToIndex(currentPlayingIndex) },
        )
        return
    }
    if (LocalReferenceLibraryProviderPublicationOnly.current &&
        LocalReferenceLibraryFullScenePreflight.current
    ) {
        // The hidden destination preflight only binds provider metadata. The visible persistent
        // library host must still keep SongsTopMenuBar/search/chrome in its fixed page slot.
        ComposeVirtualListFull(
            songs = visibleSongs,
            currentPlayingIndex = currentPlayingIndex,
            revealIndexRequest = playerReturnRevealIndex,
            hidePlayingCover = hidePlayingCover,
            state = virtualListState,
            selectedPositions = selectedPositions,
            contentTopPadding = listContentTopPadding,
            contentBottomPadding = selectionListBottomPadding,
            onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
            onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
            onRevealCoverTargetResolved = onRevealCoverTargetResolved,
            presentationTopClipInset = presentationTopClipInset,
            onScrollActiveChanged = {},
            onSongClick = { _, _ -> },
            onSongLongClick = { _, _ -> },
            modifier = Modifier.fillMaxSize(),
        )
        return
    }

    PublishLibraryFloatingToolbar(
        sceneId = NavScene.SONGS.name,
        visibilityAlpha = floatingModalAlpha,
        onSelect = { openSelectionActions(visibleSongs.map { it.id }.toSet()) },
        onPlay = {
            visibleSongs.firstOrNull()?.let { first -> onSongClick(first, 0) }
        },
        onSearch = onOpenGlobalSearch,
        onShuffle = { onShuffleAll(visibleSongs) },
        onMore = { showMoreDialog = true },
        onCreatePlaylist = null,
        onImportPlaylist = null,
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            // The floating action group follows the bottom chrome beyond its resting slot.
            // Keep the page host from clipping the group while it travels downward.
            .graphicsLayer { clip = false }
    ) {
        ComposeVirtualListFull(
            songs = visibleSongs,
            currentPlayingIndex = currentPlayingIndex,
            revealIndexRequest = playerReturnRevealIndex,
            hidePlayingCover = hidePlayingCover,
            state = virtualListState,
            selectedPositions = selectedPositions,
            contentTopPadding = listContentTopPadding,
            contentBottomPadding = selectionListBottomPadding,
            onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
            onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
            onRevealCoverTargetResolved = onRevealCoverTargetResolved,
            presentationTopClipInset = presentationTopClipInset,
            onScrollActiveChanged = { listMotionActive = it },
            onSongClick = { song, index ->
                if (selectionMode) {
                    val next = if (song.id in selectedSongIds) {
                        selectedSongIds - song.id
                    } else {
                        selectedSongIds + song.id
                    }
                    selectedSongIds = next
                } else {
                    onSongClick(song, index)
                }
            },
            onSongLongClick = { song, _ ->
                if (selectionMode) {
                    selectedSongIds = selectedSongIds + song.id
                } else {
                    openSelectionActions(setOf(song.id))
                }
            },
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (transparentTopChrome) {
                        Modifier.progressiveTransparentTopChromeMask(
                            topInset = toolbarTotalHeight + LIBRARY_CONTENT_TOP_GUARD,
                            sceneExitProgressProvider = sceneChrome.topMenuExitProgressProvider,
                        )
                    } else {
                        Modifier
                    }
                )
                .then(
                    if (topBackdropBlurSupported || needsFloatingHaze) {
                        Modifier.hazeSource(songsListHazeState)
                    } else {
                        Modifier
                    }
                )
        )

        if (!retainedLibraryPopulation && !floatingChromeSelected) {
            // Keep per-pixel exact scroll inside the top-chrome subtree. Floating mode has no
            // scene-local chrome/action renderer; its toolbar is owned by AppMainLayout.
            VirtualListScrollProgressObserver(
                owner = virtualListState.viewportScrollOwner,
                distancePx = topChromeCollapseDistancePx,
            ) { topOverlayProgress ->
                val topOverlayProgressState = rememberUpdatedState(topOverlayProgress)
                val topMenuVisibility = 1f
                val headerActionVisibility = if (effectiveHeaderButtonsEnabled) 1f else 0f
                val topMenuExitProgressProvider = sceneChrome.topMenuExitProgressProvider
                val externalPlayerTransform = LocalExternalRetainedSceneItemTransform.current
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // Haze remains in a stationary scene child. Only the menu information
                        // layer below receives scale/translation during forward navigation.
                        .zIndex(40f)
                ) {
                    TopGradientGlassTail(
                        hazeState = songsListHazeState,
                        blurSupported = topBackdropBlurSupported,
                        blurActive = topBackdropBlurActive,
                        isLight = isLightGlass,
                        transparentStyle = transparentTopChrome,
                        transparentDuringMotion = transparentGlassDuringMotion,
                        overlayProgress = topOverlayProgressState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(toolbarTotalHeight + 8.dp)
                            .graphicsLayer {
                                val sceneAlpha = sceneTopMenuAlphaProvider?.invoke() ?: sceneTopMenuAlpha
                                val progress = topMenuExitProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
                                val externalAlpha = externalPlayerTransform
                                    ?.alphaProvider
                                    ?.invoke()
                                    ?.coerceIn(0f, 1f)
                                    ?: 1f
                                alpha = sceneAlpha * topMenuVisibility *
                                    topChromeSceneRole.visibilityAt(progress) * externalAlpha
                            }
                    )

                    SongsTopMenuBar(
                        title = libraryTitle,
                        sceneId = NavScene.SONGS.name,
                        statisticsText = stringResource(R.string.library_statistics_songs, visibleSongs.size),
                        nowPlayingTitle = miniPlayerTitle,
                        nextSongTitle = nextSongTitle,
                        showNextQueueHint = showNextQueueHint,
                        metadataMatchProgressText = metadataMatchProgressText,
                        isSearchActive = false,
                        searchQuery = "",
                        onSearchQueryChange = {},
                        onToggleSearch = onOpenGlobalSearch,
                        onCancelSearch = {},
                        selectionMode = selectionMode,
                        selectedCount = selectedSongIds.size,
                        onCancelSelection = { clearSelection() },
                        onSelectAll = { toggleSelectAll() },
                        onBack = onBack,
                        onMoreClick = { showMoreDialog = true },
                        onShuffleAll = { onShuffleAll(visibleSongs) },
                        headerButtonsEnabled = effectiveHeaderButtonsEnabled,
                        headerActionsAlpha = headerActionVisibility,
                        showNowPlayingLocator = true,
                        onLocateNowPlaying = { virtualListState.requestScrollToIndex(currentPlayingIndex) },
                        isLight = isLightGlass,
                        overlayProgress = topOverlayProgress,
                        backdropBlurEnabled = topBackdropBlurSupported,
                        transparentStyle = transparentTopChrome,
                        sceneExitProgressProvider = topMenuExitProgressProvider,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val sceneAlpha = sceneTopMenuAlphaProvider?.invoke() ?: sceneTopMenuAlpha
                                val externalAlpha = externalPlayerTransform
                                    ?.alphaProvider
                                    ?.invoke()
                                    ?.coerceIn(0f, 1f)
                                    ?: 1f
                                val externalScale = externalPlayerTransform
                                    ?.scaleProvider
                                    ?.invoke()
                                    ?.coerceIn(0.5f, 1.5f)
                                    ?: 1f
                                alpha = sceneAlpha * topMenuVisibility * externalAlpha
                                scaleX = externalScale
                                scaleY = externalScale
                            }
                    )
                }

            }
        }

        RawAlphabetIndex(
            data = alphabetIndexData,
            scrollActiveProvider = { virtualListState.isListScrollInProgress },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(
                    top = 92.dp,
                    bottom = LocalBottomChromeInsets.current.contentBottom,
                    end = 0.dp
                )
                .then(
                    if (topBackdropBlurSupported) {
                        Modifier.hazeSource(songsListHazeState)
                    } else {
                        Modifier
                    }
                )
                .zIndex(30f),
            onTopSelect = {
                virtualListState.requestScrollToIndex(0)
            },
            onSelect = { _, index ->
                virtualListState.requestScrollToIndex(index)
            }
        )

        SongsSortLayoutSheet(
            visible = showSortSheet,
            currentSortOrder = currentSortOrder,
            virtualListState = virtualListState,
            onSortSelected = onSortSelected,
            onDismiss = { showSortSheet = false }
        )

        LibraryMoreDialog(
            visible = showMoreDialog,
            sourceOrder = metadataMatchSources,
            onDismiss = { showMoreDialog = false },
            onChooseFolder = onOpenFolderPicker,
            onOpenSort = {
                onSortClick()
                showSortSheet = true
            },
            onMoveSource = onMoveMetadataSource,
            onAutoMatchCurrent = onAutoMatchCurrent,
            onRematchAll = onAutoRematchAll,
        )

        SongSelectionMenu(
            visible = showSelectionSheet && selectionMode,
            selectedSongs = selectedSongs,
            selectedCount = selectedSongs.size,
            totalCount = visibleSongs.size,
            allSelected = visibleSongs.isNotEmpty() && selectedSongIds.size == visibleSongs.size,
            rangeEnabled = selectedSongIds.size >= 2,
            onToggleSelectAll = { toggleSelectAll() },
            onToggleRange = { toggleSelectionRange() },
            onAddToPlaylist = {
                if (selectedSongs.isNotEmpty()) {
                    onSelectionAddToPlaylist(selectedSongs)
                    clearSelection()
                }
            },
            onAddToQueue = {
                if (selectedSongs.isNotEmpty()) {
                    onSelectionAddToQueue(selectedSongs)
                    clearSelection()
                }
            },
            onDelete = {
                if (selectedSongs.isNotEmpty()) {
                    onSelectionDelete(selectedSongs)
                    clearSelection()
                }
            },
            onPlayNext = {
                if (selectedSongs.isNotEmpty()) {
                    onSelectionPlayNext(selectedSongs)
                    clearSelection()
                }
            },
            onTranscode = {
                if (selectedSongs.isNotEmpty()) {
                    onSelectionTranscode(selectedSongs)
                    clearSelection()
                }
            },
            onBatchMatchLyrics = {
                if (selectedSongs.isNotEmpty()) {
                    onSelectionBatchMatchLyrics(selectedSongs)
                    clearSelection()
                }
            },
            onAutoMatch = {
                if (selectedSongs.isNotEmpty()) {
                    onSelectionAutoMatch(selectedSongs)
                    clearSelection()
                }
            },
            onDismiss = { clearSelection() }
        )
    }
}

// ─────────────── 顶部菜单 ───────────────

@Composable
internal fun SongsTopMenuBar(
    title: String,
    sceneId: String,
    statisticsText: String = "",
    nowPlayingTitle: String,
    nextSongTitle: String,
    showNextQueueHint: Boolean,
    metadataMatchProgressText: String = "",
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    onToggleSearch: () -> Unit,
    onCancelSearch: () -> Unit,
    selectionMode: Boolean,
    selectedCount: Int,
    onCancelSelection: () -> Unit,
    onSelectAll: () -> Unit,
    onBack: () -> Unit,
    onMoreClick: () -> Unit,
    onShuffleAll: () -> Unit,
    headerButtonsEnabled: Boolean = true,
    headerActionsAlpha: Float = 1f,
    showNowPlayingLocator: Boolean = false,
    onLocateNowPlaying: () -> Unit = {},
    onCreatePlaylist: (() -> Unit)? = null,
    onImportPlaylist: (() -> Unit)? = null,
    showHeaderMore: Boolean = true,
    showHeaderSearch: Boolean = true,
    showHeaderShuffle: Boolean = true,
    headerTrailingContent: (@Composable () -> Unit)? = null,
    isLight: Boolean,
    overlayProgress: Float,
    backdropBlurEnabled: Boolean,
    transparentStyle: Boolean = false,
    sceneExitProgressProvider: (() -> Float)? = null,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    val sceneChrome = LocalSceneChromeAlpha.current
    val topChromeSceneRole = sceneChrome.topChromeRole(sceneId)
    val statusBarTop = unconsumedStatusBarTopPadding()
    val staticForeground = usesReferenceStaticForeground()
    val foreground = if (staticForeground) Color.White else scheme.onSurface
    val titleForeground = if (staticForeground) Color.White else scheme.onBackground
    val secondaryForeground = if (staticForeground) Color.White.copy(alpha = 0.72f) else scheme.onSurfaceVariantSummary
    val accentForeground = if (staticForeground) Color.White else scheme.primary
    // The top menu can remain mounted while the player sheet is open. Do not let
    // its hidden title register a marquee client or keep the shared clock alive.
    val listMarqueeVisible = LongTextMotionState.LocalListMarqueeVisibility.current
    val surfaceColor = if (staticForeground) {
        Color.Black.copy(alpha = 0.26f)
    } else {
        blendColor(
            start = scheme.background,
            end = accentForeground,
            fraction = if (isLight) 0.035f + 0.035f * overlayProgress else 0.12f + 0.06f * overlayProgress
        )
    }
    val searchSurfaceColor = if (staticForeground) {
        Color.Black.copy(alpha = 0.30f)
    } else {
        blendColor(
            start = scheme.surfaceContainer,
            end = accentForeground,
            fraction = if (isLight) 0.025f else 0.08f
        )
    }
    // Selection ownership is rendered by the retained-view implementation-style bottom panel.  Keep the library
    // title stable instead of duplicating the selected-count/header controls in the top chrome.
    val displayedTitle = title
    val nowPlayingOffsetY by animateDpAsState(
        targetValue = if (showNextQueueHint) (-8).dp else 0.dp,
        animationSpec = tween(durationMillis = 280),
        label = "songs-now-playing-offset"
    )
    val nextQueueHintOffsetY by animateDpAsState(
        targetValue = if (showNextQueueHint) 9.dp else 14.dp,
        animationSpec = tween(durationMillis = 280),
        label = "songs-next-playing-offset"
    )
    val nextQueueHintAlpha by animateFloatAsState(
        targetValue = if (showNextQueueHint) 1f else 0f,
        animationSpec = tween(durationMillis = if (showNextQueueHint) 220 else 180),
        label = "songs-next-playing-alpha"
    )
    fun Modifier.topMenuSceneItemTransform(pivotX: Float): Modifier = graphicsLayer {
        val progress = sceneExitProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
        // retained-view implementation retained scene layout animates the attached top-nav children themselves instead of
        // scaling the whole toolbar container. Keep each logical child anchored to its own side so
        // left/right controls shrink in place while all text/icons follow the exact gesture clock.
        // EXIT and ENTER are complementary views of the same 0 -> 1 scene clock, so a predictive
        // gesture exposes incoming labels/icons at exactly the rate the outgoing set retires.
        alpha = topChromeSceneRole.visibilityAt(progress)
        translationY = -24.dp.toPx() * topChromeSceneRole.translationFractionAt(progress)
        val scale = topChromeSceneRole.scaleAt(progress)
        scaleX = scale
        scaleY = scale
        transformOrigin = androidx.compose.ui.graphics.TransformOrigin(pivotX, 0.5f)
    }
    Column(
        modifier = modifier
            .then(
                if (backdropBlurEnabled || transparentStyle) {
                    // The separately sampled glass tail owns the background. Keeping
                    // controls transparent avoids a hard card edge over the fade.
                    Modifier
                } else {
                    Modifier.background(
                        Brush.verticalGradient(
                            colors = listOf(
                                surfaceColor.copy(alpha = 0.98f),
                                surfaceColor.copy(alpha = 0.90f),
                                surfaceColor.copy(alpha = 0.58f),
                                Color.Transparent
                            )
                        )
                    )
                }
            )
            // The top menu is a fixed scene owner. Parent inset consumption must not move its
            // controls into the physical status bar when the persistent VirtualList host is used.
            .padding(top = statusBarTop)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(72.dp)
                .padding(horizontal = 4.dp)
        ) {
            IconButton(
                onClick = {
                    if (selectionMode) onCancelSelection() else onBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .offset(y = (-5).dp)
                    .size(44.dp)
                    .topMenuSceneItemTransform(0f)
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = if (selectionMode) "取消选择" else "返回",
                    tint = foreground
                )
            }

            val showStatistics = statisticsText.isNotBlank()
            Text(
                text = displayedTitle,
                color = titleForeground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 116.dp, bottom = if (showStatistics) 15.dp else 1.dp)
                    .topMenuSceneItemTransform(0f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Start
            )
            if (showStatistics) {
                Text(
                    text = statisticsText,
                    color = secondaryForeground,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 116.dp, bottom = 1.dp)
                        .topMenuSceneItemTransform(0f),
                    textAlign = TextAlign.Start,
                )
            }

            if (!selectionMode) {
                if (metadataMatchProgressText.isNotBlank()) {
                    Text(
                        text = metadataMatchProgressText,
                        color = accentForeground,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .fillMaxWidth()
                            .padding(start = 48.dp, end = 128.dp, top = 11.dp)
                            .topMenuSceneItemTransform(0.5f),
                        textAlign = TextAlign.Center,
                    )
                } else if (nowPlayingTitle.isNotBlank()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                            .padding(start = 48.dp, end = 128.dp)
                            .clipToBounds()
                            .topMenuSceneItemTransform(0.5f),
                        contentAlignment = Alignment.Center
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .offset(y = nowPlayingOffsetY)
                                .clipToBounds(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = stringResource(R.string.songs_now_playing_prefix),
                                color = secondaryForeground,
                                fontSize = 12.sp,
                                maxLines = 1
                            )
                                SharedMarqueeText(
                                    text = nowPlayingTitle,
                                    color = secondaryForeground,
                                    fontSizeSp = 12f,
                                modifier = Modifier
                                        .weight(1f)
                                        .height(20.dp),
                                    visible = listMarqueeVisible
                                )
                        }
                        Text(
                            text = stringResource(R.string.songs_next_playing, nextSongTitle),
                            color = accentForeground,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier
                                .offset(y = nextQueueHintOffsetY)
                                .fillMaxWidth()
                                .clipToBounds()
                                .graphicsLayer { alpha = nextQueueHintAlpha }
                        )
                    }
                }

                if (headerButtonsEnabled && headerActionsAlpha > 0.001f) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .graphicsLayer { alpha = headerActionsAlpha.coerceIn(0f, 1f) }
                            .topMenuSceneItemTransform(1f),
                        verticalAlignment = Alignment.Top
                    ) {
                        if (showHeaderMore && (onCreatePlaylist != null || onImportPlaylist != null)) {
                            onCreatePlaylist?.let { create ->
                                IconButton(onClick = create, modifier = Modifier.size(42.dp)) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_add_outline),
                                        contentDescription = stringResource(R.string.playlist_action_create),
                                        tint = foreground,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            onImportPlaylist?.let { importPlaylist ->
                                IconButton(onClick = importPlaylist, modifier = Modifier.size(42.dp)) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_playlist_import),
                                        contentDescription = stringResource(R.string.playlist_action_import),
                                        tint = foreground,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                            }
                            IconButton(
                                onClick = onMoreClick,
                                modifier = Modifier.size(42.dp)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_library_more),
                                    contentDescription = stringResource(R.string.common_more_operations),
                                    tint = foreground,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        } else if (showHeaderMore) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                IconButton(
                                    onClick = onMoreClick,
                                    modifier = Modifier.size(42.dp)
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_library_more),
                                        contentDescription = stringResource(R.string.common_more_operations),
                                        tint = foreground,
                                        modifier = Modifier.size(24.dp)
                                    )
                                }
                                if (showNowPlayingLocator) {
                                    IconButton(
                                        onClick = onLocateNowPlaying,
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_now_playing_locator),
                                            contentDescription = stringResource(R.string.songs_locate_now_playing),
                                            tint = accentForeground,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }

                        }
                        if (showHeaderSearch || showHeaderShuffle) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                if (showHeaderSearch) {
                                    IconButton(
                                        onClick = onToggleSearch,
                                        modifier = Modifier.size(40.dp)
                                    ) {
                                        Icon(
                                            imageVector = MiuixIcons.Basic.Search,
                                            contentDescription = stringResource(R.string.library_action_search),
                                            tint = foreground
                                        )
                                    }
                                }
                                if (showHeaderShuffle) {
                                    IconButton(
                                        onClick = onShuffleAll,
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.ic_shuffle_custom),
                                            contentDescription = stringResource(R.string.songs_shuffle_all),
                                            tint = foreground,
                                            modifier = Modifier.size(19.dp)
                                        )
                                    }
                                }
                            }
                        }
                        headerTrailingContent?.invoke()
                    }
                }
            }
        }

        if (isSearchActive) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(searchSurfaceColor)
                    .padding(horizontal = 14.dp)
                    .topMenuSceneItemTransform(0.5f),
                contentAlignment = Alignment.CenterStart
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    BasicTextField(
                        value = searchQuery,
                        onValueChange = onSearchQueryChange,
                        modifier = Modifier.weight(1f),
                        textStyle = TextStyle(
                            color = titleForeground,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(accentForeground)
                    )

                    Text(
                        text = stringResource(R.string.library_action_cancel),
                        color = accentForeground,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .padding(start = 12.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { onCancelSearch() }
                            .padding(horizontal = 6.dp, vertical = 4.dp)
                    )
                }

                if (searchQuery.isEmpty()) {
                    Text(
                        text = stringResource(R.string.library_search_songs),
                        color = secondaryForeground,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
internal fun TopGradientGlassTail(
    hazeState: HazeState,
    blurSupported: Boolean,
    blurActive: Boolean,
    isLight: Boolean,
    overlayProgress: State<Float>,
    transparentStyle: Boolean = false,
    transparentDuringMotion: Boolean = false,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme

    if (transparentStyle) {
        Box(modifier = modifier)
        return
    }

    val progress = overlayProgress.value
    val fallbackBase = blendColor(
        start = scheme.background,
        end = scheme.primary,
        fraction = if (isLight) {
            0.025f + 0.02f * progress
        } else {
            0.10f + 0.04f * progress
        }
    )
    val fallbackBrush = Brush.verticalGradient(
        colors = listOf(
            fallbackBase.copy(alpha = 0.98f),
            fallbackBase.copy(alpha = 0.90f),
            fallbackBase.copy(alpha = 0.62f),
            fallbackBase.copy(alpha = 0.22f),
            Color.Transparent
        )
    )

    if (!blurSupported) {
        Box(modifier = modifier.background(fallbackBrush))
        return
    }

    // Keep both visual children mounted. PivotTransition changes only two lightweight RenderNode alpha
    // properties instead of detaching hazeSource/hazeEffect and inserting a different background
    // subtree at the first/last motion frame. An alpha-zero Haze child is skipped by the renderer,
    // while the fallback keeps the top chrome opaque and deterministic during motion.
    Box(modifier = modifier) {
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (blurActive) 1f else 0f }
                .hazeEffect(state = hazeState) {
                    inputScale = HazeInputScale.Fixed(0.67f)
                    clipToAreasBounds = false
                    expandLayerBounds = true
                    drawContentBehind = true
                    forceInvalidateOnPreDraw = false
                    blurEffect {
                        backgroundColor = Color.Transparent
                        val tintAlpha = when {
                            transparentDuringMotion || usesReferenceStaticForeground() -> 0f
                            isLight -> 0.24f
                            else -> 0.20f
                        }
                        colorEffects = if (tintAlpha > 0f) {
                            listOf(HazeColorEffect.tint(scheme.background.copy(alpha = tintAlpha)))
                        } else {
                            emptyList()
                        }
                        blurRadius = 72.dp
                        noiseFactor = 0f
                        progressive = HazeProgressive.verticalGradient(
                            startIntensity = 1f,
                            endIntensity = 0f
                        )
                    }
                }
        )
        Box(
            modifier = Modifier
                .matchParentSize()
                .graphicsLayer { alpha = if (blurActive) 0f else 1f }
                .background(fallbackBrush)
        )
    }
}

@Composable
internal fun SongsSortLayoutSheet(
    visible: Boolean,
    currentSortOrder: SortOrder,
    virtualListState: ComposeVirtualListState,
    onSortSelected: (SortOrder) -> Unit,
    sortOptions: List<Pair<String, SortOrder>>? = null,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val scrollState = rememberScrollState()
    val scheme = MiuixTheme.colorScheme
    val sheetColor = blendColor(scheme.background, scheme.primary, 0.035f)
    val secondaryColor = scheme.onSurfaceVariantSummary
    val selectedColor = scheme.primary
    RawMiuixOverlayDialog(
        show = visible,
        title = stringResource(R.string.sort_and_layout),
        backgroundColor = sheetColor,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true
    ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp)
                    .verticalScroll(scrollState)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    SheetTitle(stringResource(R.string.sort_section))
                    Spacer(Modifier.height(8.dp))

                    val alphabeticalSelected = currentSortOrder.baseSortOrder() == SortOrder.TITLE_ASC
                    SortSheetRow(
                        title = stringResource(R.string.sort_alphabetical),
                        selected = alphabeticalSelected,
                        selectedColor = selectedColor,
                        leadingIconRes = R.drawable.ic_sort_alphabetical,
                        reverseSelected = alphabeticalSelected && currentSortOrder.isDescendingSortOrder(),
                        onReverseClick = {
                            onSortSelected(
                                if (alphabeticalSelected) currentSortOrder.reversedSortOrder()
                                else SortOrder.TITLE_DESC
                            )
                        },
                        onClick = {
                            onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.TITLE_ASC))
                        }
                    )

                    val effectiveSortOptions = (sortOptions ?: listOf(
                        stringResource(R.string.sort_by_title) to SortOrder.TITLE_ASC,
                        stringResource(R.string.sort_by_filename) to SortOrder.FILE_NAME_ASC,
                        stringResource(R.string.sort_by_path) to SortOrder.PATH_ASC,
                        stringResource(R.string.sort_by_artist) to SortOrder.ARTIST_ASC,
                        stringResource(R.string.sort_by_album) to SortOrder.ALBUM_ASC,
                        stringResource(R.string.sort_by_year) to SortOrder.YEAR_ASC,
                        stringResource(R.string.sort_by_added) to SortOrder.DATE_ADDED_ASC,
                        stringResource(R.string.sort_by_modified) to SortOrder.DATE_MODIFIED_ASC,
                        stringResource(R.string.sort_by_play_count) to SortOrder.PLAYBACK_INFO
                    )).filterNot { (_, order) -> order.baseSortOrder() == SortOrder.TITLE_ASC }
                    effectiveSortOptions.forEach { (label, baseOrder) ->
                        val selected = currentSortOrder.baseSortOrder() == baseOrder
                        SortSheetRow(
                            title = label,
                            selected = selected,
                            selectedColor = selectedColor,
                            reverseSelected = selected && currentSortOrder.isDescendingSortOrder(),
                            onReverseClick = {
                                onSortSelected(
                                    if (selected) currentSortOrder.reversedSortOrder()
                                    else baseOrder.withSortDirection(descending = true)
                                )
                            },
                            onClick = {
                                onSortSelected(currentSortOrder.withBaseSortOrder(baseOrder))
                            }
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(secondaryColor.copy(alpha = 0.16f))
                    )

                    Spacer(Modifier.height(10.dp))
                    SheetTitle(stringResource(R.string.layout_section))
                    Spacer(Modifier.height(8.dp))

                    SortSheetRow(stringResource(R.string.layout_compact_list), virtualListState.currentLevel == ListZoomIndex.SMALL && !virtualListState.isGrid, selectedColor) {
                        scope.launch { virtualListState.snapToLevel(ListZoomIndex.SMALL) }
                    }
                    SortSheetRow(stringResource(R.string.layout_standard_list), virtualListState.currentLevel == ListZoomIndex.NORMAL && !virtualListState.isGrid, selectedColor) {
                        scope.launch { virtualListState.snapToLevel(ListZoomIndex.NORMAL) }
                    }
                    SortSheetRow(stringResource(R.string.layout_large_cover_list), virtualListState.currentLevel == ListZoomIndex.ZOOMED && !virtualListState.isGrid, selectedColor) {
                        scope.launch { virtualListState.snapToLevel(ListZoomIndex.ZOOMED) }
                    }
                    SortSheetRow(stringResource(R.string.layout_grid_4), virtualListState.columns == 4, selectedColor) {
                        scope.launch { virtualListState.snapToColumns(4) }
                    }
                    SortSheetRow(stringResource(R.string.layout_grid_3), virtualListState.columns == 3, selectedColor) {
                        scope.launch { virtualListState.snapToColumns(3) }
                    }
                    SortSheetRow(stringResource(R.string.layout_grid_2), virtualListState.columns == 2, selectedColor) {
                        scope.launch { virtualListState.snapToColumns(2) }
                    }
                }
            }
    }
}

@Composable
internal fun SongSelectionMenu(
    visible: Boolean,
    selectedSongs: List<AudioFile>,
    singleContextSong: AudioFile? = selectedSongs.singleOrNull(),
    selectedCount: Int,
    totalCount: Int,
    allSelected: Boolean,
    rangeEnabled: Boolean,
    onToggleSelectAll: () -> Unit,
    onToggleRange: () -> Unit,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onDelete: () -> Unit,
    onPlayNext: () -> Unit,
    onTranscode: () -> Unit,
    onBatchMatchLyrics: () -> Unit,
    onAutoMatch: () -> Unit,
    kind: SelectionMenuKind = SelectionMenuKind.TRACKS,
    onClearQueue: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scheme = MiuixTheme.colorScheme
    val enabled = selectedCount > 0
    val hairline = with(LocalDensity.current) { 1.toDp() }
    var suspendedFor by remember { mutableStateOf<SelectionSecondaryAction?>(null) }
    var secondaryReady by remember { mutableStateOf(false) }
    val primaryVisible = visible && suspendedFor == null

    LaunchedEffect(suspendedFor) {
        secondaryReady = false
        if (suspendedFor != null) {
            // Let the selection popup finish its 200ms exit before the secondary surface appears.
            // This mirrors retained-view implementation's temporary selection-menu handoff instead of stacking two
            // fully visible modal surfaces on top of each other.
            delay(200)
            secondaryReady = true
        }
    }
    LaunchedEffect(visible) {
        if (!visible) {
            suspendedFor = null
            secondaryReady = false
        }
    }

    val standardActions = buildList {
        add(
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_add_to_playlist),
            vector = MiuixIcons.Regular.ListView,
            onClick = onAddToPlaylist,
        ))
        add(
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_add_to_queue),
            vector = MiuixIcons.Regular.Music,
            onClick = onAddToQueue,
        ))
        add(
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_play_next),
            // Keep this identical to the transport glyph used by the actual player. The resource
            // is historically named `ic_speed_fill`, but its vector is the next-track glyph used
            // by ComposePlayerContainer/MainActivity.
            drawableRes = R.drawable.ic_speed_fill,
            onClick = onPlayNext,
        ))
        add(
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_delete_selected),
            vector = MiuixIcons.Regular.Delete,
            danger = true,
            onClick = onDelete,
        ))
        add(
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_share),
            drawableRes = R.drawable.ic_share,
            onClick = {
                if (selectedSongs.isNotEmpty()) shareSelectedAudio(context, selectedSongs)
                onDismiss()
            },
        ))

        when (kind) {
            SelectionMenuKind.TRACKS -> {
                add(
                    SelectionActionSpec(
                        title = stringResource(R.string.songs_action_info),
                        drawableRes = R.drawable.ic_info,
                        actionEnabled = singleContextSong != null,
                        onClick = { suspendedFor = SelectionSecondaryAction.INFO },
                    )
                )
                add(
                    SelectionActionSpec(
                        title = stringResource(R.string.songs_action_album_art),
                        drawableRes = R.drawable.ic_album,
                        actionEnabled = singleContextSong != null,
                        onClick = { suspendedFor = SelectionSecondaryAction.ARTWORK },
                    )
                )
            }
            SelectionMenuKind.COLLECTION -> {
                add(
                    SelectionActionSpec(
                        title = stringResource(R.string.songs_action_album_art),
                        drawableRes = R.drawable.ic_album,
                        actionEnabled = singleContextSong != null,
                        onClick = { suspendedFor = SelectionSecondaryAction.ARTWORK },
                    )
                )
            }
            SelectionMenuKind.QUEUE -> {
                add(
                    SelectionActionSpec(
                        title = stringResource(R.string.songs_action_info),
                        drawableRes = R.drawable.ic_info,
                        actionEnabled = singleContextSong != null,
                        onClick = { suspendedFor = SelectionSecondaryAction.INFO },
                    )
                )
                add(SelectionActionSpec(
                title = stringResource(R.string.songs_action_clear_queue),
                drawableRes = R.drawable.ic_delete,
                onClick = {
                    onClearQueue()
                    onDismiss()
                },
                ))
            }
        }
    }
    val rawActions = listOf(
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_transcode),
            drawableRes = R.drawable.ic_selection_transcode,
            onClick = onTranscode,
        ),
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_batch_match_lyrics),
            drawableRes = R.drawable.ic_selection_batch_match,
            onClick = onBatchMatchLyrics,
        ),
        SelectionActionSpec(
            title = stringResource(R.string.songs_action_auto_match_missing),
            drawableRes = R.drawable.ic_selection_auto_match,
            onClick = onAutoMatch,
        ),
    )

    ActivityOverlayBackOwner(active = visible)
    val predictiveProgress = rememberPredictiveDialogProgress(
        enabled = primaryVisible,
        onDismissRequest = onDismiss,
    )

    // AppMainLayout owns RawAlphabetIndex as a sibling rendered after ComposeNavHost. A scene-local
    // zIndex can never outrank that sibling, which lets the index rail intercept the menu's right
    // edge (including Close). retained-view implementation puts SelectionMenu in its top modal container above
    // index scroller. A non-focusable Popup gives Raw the same layer ownership while taps outside
    // the menu still reach list rows, so the user can continue extending the selection.
    Popup(
        alignment = Alignment.BottomCenter,
        onDismissRequest = {},
        properties = PopupProperties(
            focusable = false,
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            clippingEnabled = false,
        ),
    ) {
        AnimatedVisibility(
            visible = primaryVisible,
            enter = slideInVertically(
                initialOffsetY = { it },
                animationSpec = tween(durationMillis = 500, easing = SelectionTransitionEasing),
            ),
            exit = slideOutVertically(
                targetOffsetY = { it },
                animationSpec = tween(durationMillis = 200, easing = SelectionTransitionEasing),
            ),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .rawStableNavigationBarsPadding()
                    .padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
                    .predictiveBottomSheetMotion(
                        progress = predictiveProgress,
                        translationY = 150.dp,
                    )
                    // build 1026: navbar_ext_bg + bar_elevation, with corners_navbar resolving to
                    // 22dp on the default phone resource set. Avoid the old 8dp floating-card
                    // shadow/border; the menu should read as bottom chrome, not a Material dialog.
                    .graphicsLayer {
                        shadowElevation = 2.dp.toPx()
                        shape = RoundedCornerShape(22.dp)
                        clip = false
                    }
                    .clip(RoundedCornerShape(22.dp))
                    .background(scheme.surfaceContainerHigh)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(38.dp),
                ) {
                    Row(
                        modifier = Modifier.align(Alignment.CenterStart),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(onClick = onToggleSelectAll)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SelectionAllCheck(checked = allSelected)
                            Spacer(Modifier.size(8.dp))
                            Text(
                                text = stringResource(R.string.library_action_select_all),
                                color = scheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                        Row(
                            modifier = Modifier
                                .height(38.dp)
                                .graphicsLayer { alpha = if (rangeEnabled) 1f else 0.34f }
                                .clip(RoundedCornerShape(10.dp))
                                .clickable(enabled = rangeEnabled, onClick = onToggleRange)
                                .padding(horizontal = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Image(
                                painter = painterResource(R.drawable.ic_selection_range),
                                contentDescription = null,
                                colorFilter = ColorFilter.tint(scheme.onSurface),
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.size(5.dp))
                            Text(
                                text = stringResource(R.string.songs_selection_range),
                                color = scheme.onSurface,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }

                    Text(
                        text = "$selectedCount / $totalCount",
                        color = scheme.onSurface,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.align(Alignment.Center),
                    )

                    Box(
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .size(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .clickable(onClick = onDismiss),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Close,
                            contentDescription = stringResource(R.string.library_action_cancel),
                            tint = scheme.onSurface,
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(hairline)
                        .background(scheme.onSurface.copy(alpha = 0.12f)),
                )

                standardActions.chunked(4).forEach { rowActions ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.Start,
                    ) {
                        rowActions.forEach { action ->
                            SelectionActionTile(
                                spec = action,
                                enabled = enabled && action.actionEnabled,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start,
                ) {
                    rawActions.forEach { action ->
                        SelectionActionTile(
                            spec = action,
                            enabled = enabled,
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }

    SelectionSongInfoDialog(
        song = if (secondaryReady && suspendedFor == SelectionSecondaryAction.INFO) singleContextSong else null,
        onDismiss = { suspendedFor = null },
    )
    OriginalArtworkViewerDialog(
        show = secondaryReady && suspendedFor == SelectionSecondaryAction.ARTWORK,
        song = singleContextSong,
        coverKey = singleContextSong?.coverKey,
        onDismiss = { suspendedFor = null },
    )
}

internal val SelectionTransitionEasing = CubicBezierEasing(0f, 0f, 0.2f, 1f)

internal enum class SelectionMenuKind {
    TRACKS,
    COLLECTION,
    QUEUE,
}

private enum class SelectionSecondaryAction {
    INFO,
    ARTWORK,
}

private data class SelectionActionSpec(
    val title: String,
    val vector: ImageVector? = null,
    val drawableRes: Int? = null,
    val danger: Boolean = false,
    val actionEnabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
private fun SelectionAllCheck(checked: Boolean) {
    val scheme = MiuixTheme.colorScheme
    Box(
        modifier = Modifier
            .size(18.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(if (checked) scheme.primary else Color.Transparent)
            .border(
                width = 1.dp,
                color = if (checked) scheme.primary else scheme.onSurface.copy(alpha = 0.55f),
                shape = RoundedCornerShape(6.dp),
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text(
                text = "✓",
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun SelectionActionTile(
    spec: SelectionActionSpec,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val dangerColor = MaterialTheme.colorScheme.error
    val tint = if (spec.danger) dangerColor else scheme.onSurface
    Column(
        modifier = modifier
            .heightIn(min = 48.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.34f }
            .clip(RoundedCornerShape(12.dp))
            .clickable(enabled = enabled, onClick = spec.onClick)
            .padding(start = 8.dp, end = 8.dp, bottom = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when {
            spec.vector != null -> Icon(
                imageVector = spec.vector,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(24.dp),
            )
            spec.drawableRes != null -> Image(
                painter = painterResource(spec.drawableRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(tint),
                modifier = Modifier.size(24.dp),
            )
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = spec.title,
            color = tint,
            fontSize = 12.sp,
            lineHeight = 13.sp,
            fontWeight = FontWeight.Medium,
            // retained-view implementation SelectionListContextButtonBase sets singleLine=true; keep every action row
            // geometrically stable even when Raw adds longer feature labels.
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun SelectionSongInfoDialog(
    song: AudioFile?,
    onDismiss: () -> Unit,
) {
    RawMiuixOverlayDialog(
        show = song != null,
        title = stringResource(R.string.songs_info_title),
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        val current = song ?: return@RawMiuixOverlayDialog
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState()),
        ) {
            SelectionInfoRow(stringResource(R.string.songs_info_title), current.displayName)
            SelectionInfoRow(stringResource(R.string.songs_info_artist), current.artist.ifBlank { "—" })
            SelectionInfoRow(stringResource(R.string.songs_info_album), current.album.ifBlank { "—" })
            SelectionInfoRow(
                stringResource(R.string.songs_info_format),
                current.format.ifBlank { current.extension.ifBlank { "—" } },
            )
            SelectionInfoRow(
                stringResource(R.string.songs_info_sample_rate),
                if (current.sampleRate > 0) "${current.sampleRate / 1000f} kHz" else "—",
            )
            SelectionInfoRow(
                stringResource(R.string.songs_info_bit_depth),
                if (current.bitsPerSample > 0) "${current.bitsPerSample} bit" else "—",
            )
            SelectionInfoRow(
                stringResource(R.string.songs_info_duration),
                formatSelectionDuration(current.duration),
            )
            SelectionInfoRow(stringResource(R.string.songs_info_path), current.path.ifBlank { "—" })
        }
    }
}

@Composable
private fun SelectionInfoRow(label: String, value: String) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
    ) {
        Text(
            text = label,
            color = scheme.onSurfaceVariantSummary,
            fontSize = 12.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = value,
            color = scheme.onSurface,
            fontSize = 14.sp,
        )
    }
}

private fun formatSelectionDuration(durationMs: Long): String {
    if (durationMs <= 0L) return "—"
    val totalSeconds = durationMs / 1000L
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}

private fun <T> tweenMillis(durationMillis: Int) = androidx.compose.animation.core.tween<T>(durationMillis = durationMillis)

@Composable
private fun SheetHandle(color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .size(width = 38.dp, height = 4.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(color.copy(alpha = 0.22f))
    )
}

@Composable
private fun SheetTitle(title: String) {
    Text(
        text = title,
        color = MiuixTheme.colorScheme.onSurface,
        fontSize = 20.sp,
        fontWeight = FontWeight.SemiBold
    )
}

@Composable
private fun SortSheetRow(
    title: String,
    selected: Boolean,
    selectedColor: Color,
    leadingIconRes: Int? = null,
    reverseSelected: Boolean? = null,
    onReverseClick: (() -> Unit)? = null,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val textColor = if (selected) selectedColor else scheme.onSurface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) selectedColor.copy(alpha = 0.10f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingIconRes != null) {
            Icon(
                painter = painterResource(leadingIconRes),
                contentDescription = null,
                tint = textColor,
                modifier = Modifier.size(18.dp)
            )
            Spacer(Modifier.size(10.dp))
        }
        Text(
            text = title,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (reverseSelected != null && onReverseClick != null) {
            SortDirectionToggle(
                checked = reverseSelected,
                selectedColor = selectedColor,
                onClick = onReverseClick
            )
        }
    }
}

@Composable
private fun SortDirectionToggle(
    checked: Boolean,
    selectedColor: Color,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .clickable(onClick = onClick)
            .padding(start = 10.dp, end = 2.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = stringResource(R.string.sort_descending),
            color = if (checked) selectedColor else scheme.onSurfaceVariantSummary,
            fontSize = 13.sp,
            fontWeight = if (checked) FontWeight.SemiBold else FontWeight.Normal
        )
        Spacer(Modifier.size(7.dp))
        Box(
            modifier = Modifier
                .size(width = 30.dp, height = 18.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(
                    if (checked) selectedColor
                    else scheme.onSurfaceVariantSummary.copy(alpha = 0.20f)
                )
                .padding(2.dp),
            contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart
        ) {
            Box(
                Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (checked) scheme.background else scheme.onSurfaceVariantSummary)
            )
        }
    }
}

@Composable
private fun SelectionSheetRow(
    title: String,
    danger: Boolean = false,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val dangerColor = MaterialTheme.colorScheme.error
    val textColor = if (danger) dangerColor else scheme.onSurface
    Text(
        text = title,
        color = textColor,
        fontSize = 16.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (danger) dangerColor.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 13.dp)
    )
}

private fun blendColor(
    start: Color,
    end: Color,
    fraction: Float
): Color {
    val f = fraction.coerceIn(0f, 1f)
    val inv = 1f - f
    return Color(
        red = start.red * inv + end.red * f,
        green = start.green * inv + end.green * f,
        blue = start.blue * inv + end.blue * f,
        alpha = start.alpha * inv + end.alpha * f
    )
}

private fun SortOrder.baseSortOrder(): SortOrder = when (this) {
    SortOrder.TITLE_ASC, SortOrder.TITLE_DESC -> SortOrder.TITLE_ASC
    SortOrder.FILE_NAME_ASC, SortOrder.FILE_NAME_DESC -> SortOrder.FILE_NAME_ASC
    SortOrder.PATH_ASC, SortOrder.PATH_DESC -> SortOrder.PATH_ASC
    SortOrder.ARTIST_ASC, SortOrder.ARTIST_DESC -> SortOrder.ARTIST_ASC
    SortOrder.ALBUM_ASC, SortOrder.ALBUM_DESC -> SortOrder.ALBUM_ASC
    SortOrder.YEAR_ASC, SortOrder.YEAR_DESC -> SortOrder.YEAR_ASC
    SortOrder.PLAYBACK_INFO, SortOrder.PLAYBACK_INFO_DESC -> SortOrder.PLAYBACK_INFO
    SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> SortOrder.DATE_ADDED_ASC
    SortOrder.DATE_MODIFIED_ASC, SortOrder.DATE_MODIFIED_DESC -> SortOrder.DATE_MODIFIED_ASC
    SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> SortOrder.DURATION_ASC
}

private fun SortOrder.withBaseSortOrder(base: SortOrder): SortOrder =
    base.withSortDirection(descending = isDescendingSortOrder())

private fun SortOrder.withSortDirection(descending: Boolean): SortOrder = when (baseSortOrder()) {
    SortOrder.TITLE_ASC -> if (descending) SortOrder.TITLE_DESC else SortOrder.TITLE_ASC
    SortOrder.FILE_NAME_ASC -> if (descending) SortOrder.FILE_NAME_DESC else SortOrder.FILE_NAME_ASC
    SortOrder.PATH_ASC -> if (descending) SortOrder.PATH_DESC else SortOrder.PATH_ASC
    SortOrder.ARTIST_ASC -> if (descending) SortOrder.ARTIST_DESC else SortOrder.ARTIST_ASC
    SortOrder.ALBUM_ASC -> if (descending) SortOrder.ALBUM_DESC else SortOrder.ALBUM_ASC
    SortOrder.YEAR_ASC -> if (descending) SortOrder.YEAR_DESC else SortOrder.YEAR_ASC
    SortOrder.PLAYBACK_INFO -> if (descending) SortOrder.PLAYBACK_INFO_DESC else SortOrder.PLAYBACK_INFO
    SortOrder.DATE_ADDED_ASC -> if (descending) SortOrder.DATE_ADDED_DESC else SortOrder.DATE_ADDED_ASC
    SortOrder.DATE_MODIFIED_ASC -> if (descending) SortOrder.DATE_MODIFIED_DESC else SortOrder.DATE_MODIFIED_ASC
    SortOrder.DURATION_ASC -> if (descending) SortOrder.DURATION_DESC else SortOrder.DURATION_ASC
    else -> this
}

private fun SortOrder.isDescendingSortOrder(): Boolean = when (this) {
    SortOrder.TITLE_DESC,
    SortOrder.FILE_NAME_DESC,
    SortOrder.PATH_DESC,
    SortOrder.ARTIST_DESC,
    SortOrder.ALBUM_DESC,
    SortOrder.YEAR_DESC,
    SortOrder.PLAYBACK_INFO_DESC,
    SortOrder.DATE_ADDED_DESC,
    SortOrder.DATE_MODIFIED_DESC,
    SortOrder.DURATION_DESC -> true
    else -> false
}

private fun SortOrder.reversedSortOrder(): SortOrder = when (this) {
    SortOrder.TITLE_ASC -> SortOrder.TITLE_DESC
    SortOrder.TITLE_DESC -> SortOrder.TITLE_ASC
    SortOrder.FILE_NAME_ASC -> SortOrder.FILE_NAME_DESC
    SortOrder.FILE_NAME_DESC -> SortOrder.FILE_NAME_ASC
    SortOrder.PATH_ASC -> SortOrder.PATH_DESC
    SortOrder.PATH_DESC -> SortOrder.PATH_ASC
    SortOrder.ARTIST_ASC -> SortOrder.ARTIST_DESC
    SortOrder.ARTIST_DESC -> SortOrder.ARTIST_ASC
    SortOrder.ALBUM_ASC -> SortOrder.ALBUM_DESC
    SortOrder.ALBUM_DESC -> SortOrder.ALBUM_ASC
    SortOrder.YEAR_ASC -> SortOrder.YEAR_DESC
    SortOrder.YEAR_DESC -> SortOrder.YEAR_ASC
    SortOrder.PLAYBACK_INFO -> SortOrder.PLAYBACK_INFO_DESC
    SortOrder.PLAYBACK_INFO_DESC -> SortOrder.PLAYBACK_INFO
    SortOrder.DATE_ADDED_ASC -> SortOrder.DATE_ADDED_DESC
    SortOrder.DATE_ADDED_DESC -> SortOrder.DATE_ADDED_ASC
    SortOrder.DATE_MODIFIED_ASC -> SortOrder.DATE_MODIFIED_DESC
    SortOrder.DATE_MODIFIED_DESC -> SortOrder.DATE_MODIFIED_ASC
    SortOrder.DURATION_ASC -> SortOrder.DURATION_DESC
    SortOrder.DURATION_DESC -> SortOrder.DURATION_ASC
}
