package com.rawsmusic.core.ui.scene.pages

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import com.rawsmusic.core.ui.systemui.rawStableStatusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalSceneBackgroundFrozen
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryFullScenePreflight
import com.rawsmusic.core.ui.scene.LocalRetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.LocalReferenceRetainedLibraryPopulation
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.HomeFullCoverSourceAnchor
import com.rawsmusic.core.ui.widget.flow.referenceStaticForeground
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowMode
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowModeSetter
import com.rawsmusic.core.ui.widget.flow.RawFlowModeDialog
import com.rawsmusic.core.ui.widget.flow.rememberRawFlowChromeBaseColor
import com.rawsmusic.core.ui.widget.bitmaps.resolvePlaybackArtworkKey
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderRegistry
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryBodyRender
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListScrollProgressObserver
import io.github.proify.lyricon.lyric.model.Song
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlinx.coroutines.delay
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.rawsmusic.core.ui.widget.utils.InteractiveHighlight
import androidx.compose.ui.util.fastCoerceAtMost
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tanh
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePage(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    currentLyric: String,
    currentLyricTranslation: String,
    lyricSong: Song?,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    playCounts: Map<Long, Int>,
    listState: ScrollState = rememberScrollState(),
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("home"),
    carouselState: HomeArtworkCarouselState,
    renderBackdrop: Boolean = true,
    onNavigate: (NavScene) -> Unit,
    onSearchClick: () -> Unit,
    showSettingsShortcut: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onHeaderMenuActionOverride: (() -> Unit)? = null,
    headerOptions: HomeHeaderOptionsState = rememberHomeHeaderOptionsState(),
    homeCardLayoutState: HomeCardLayoutState = rememberHomeCardLayoutState(),
    onCurrentPlayPause: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onQueueNavigate: (Int) -> Unit,
    onCarouselSongSelect: (List<AudioFile>, AudioFile, Int) -> Unit = { _, _, _ -> },
    onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit = {},
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit = { _, _ -> },
    hideCenterForFullscreenTransition: Boolean = false,
    centerReflectionAlpha: Float = 1f,
    centerReflectionArtworkKey: String = "",
) {
    var showFlowModeDialog by remember { mutableStateOf(false) }
    var showHeaderMenuDialog by remember { mutableStateOf(false) }
    var homeCardEditMode by remember { mutableStateOf(false) }
    var addCardGroup by remember { mutableStateOf<HomeCardGroup?>(null) }
    var showAddCardDialog by remember { mutableStateOf(false) }
    val libraryCardDragPreview = remember { HomeCardDragPreviewState() }
    val toolCardDragPreview = remember { HomeCardDragPreviewState() }
    val rawFlowMode = LocalRawFlowMode.current
    val setRawFlowMode = LocalRawFlowModeSetter.current
    val sceneMotionFrozen = LocalSceneBackgroundFrozen.current
    val retainedLibraryPopulation = LocalReferenceRetainedLibraryPopulation.current
    LaunchedEffect(sceneMotionFrozen) {
        if (sceneMotionFrozen) {
            homeCardEditMode = false
            showAddCardDialog = false
        }
    }
    BackHandler(enabled = homeCardEditMode && !showAddCardDialog) {
        libraryCardDragPreview.clear()
        toolCardDragPreview.clear()
        homeCardEditMode = false
    }
    // A retained VirtualList layout keeps the provider snapshot that existed at pivot start. Do not
    // let a simultaneous track bind/lyric tick mutate HOME holders while PivotTransition is moving.
    // Array-backed retention deliberately avoids creating another Compose state clock.
    val retainedQueue = remember { arrayOf(queueSongs) }
    val retainedCurrent = remember { arrayOf(currentSong) }
    val retainedLyric = remember { arrayOf(currentLyric) }
    val retainedTranslation = remember { arrayOf(currentLyricTranslation) }
    val retainedLyricSong = remember { arrayOf(lyricSong) }
    val retainedPosition = remember { longArrayOf(playbackPositionMs) }
    val retainedPlaying = remember { booleanArrayOf(isPlaying) }
    if (!sceneMotionFrozen) {
        retainedQueue[0] = queueSongs
        retainedCurrent[0] = currentSong
        retainedLyric[0] = currentLyric
        retainedTranslation[0] = currentLyricTranslation
        retainedLyricSong[0] = lyricSong
        retainedPosition[0] = playbackPositionMs
        retainedPlaying[0] = isPlaying
    }
    val effectiveQueueSongs = if (sceneMotionFrozen) retainedQueue[0] else queueSongs
    val effectiveCurrentSong = if (sceneMotionFrozen) retainedCurrent[0] else currentSong
    val effectiveLyric = if (sceneMotionFrozen) retainedLyric[0] else currentLyric
    val effectiveTranslation = if (sceneMotionFrozen) retainedTranslation[0] else currentLyricTranslation
    val effectiveLyricSong = if (sceneMotionFrozen) retainedLyricSong[0] else lyricSong
    val effectivePosition = if (sceneMotionFrozen) retainedPosition[0] else playbackPositionMs
    val effectivePlaying = if (sceneMotionFrozen) retainedPlaying[0] else isPlaying
    val carouselSongs = remember(effectiveQueueSongs, effectiveCurrentSong, queueCurrentIndex) {
        effectiveQueueSongs.ifEmpty { listOfNotNull(effectiveCurrentSong) }
    }
    // Carousel position and playback identity are separate owners. The queue cursor may advance
    // before the decoder/currentSong commit, so it must never impersonate the playing song here;
    // doing so is the source of target -> previous -> target artwork/background flashes.
    val carouselCurrentSong = effectiveCurrentSong
    // HOME is composed both as the visible persistent library controller and as a hidden provider
    // preflight target on return. Rebuilding all folder/album/artist/etc. HashSets in each
    // composition puts O(library size) allocation directly on the navigation hot path. Reuse the
    // metadata worker's snapshot when available; the fallback is only for the very first HOME frame
    // before background warmup has completed.
    val warmedHomeStatistics = LibrarySceneGroupingWarmup.homeStatistics(songs)
    val homeStatistics = warmedHomeStatistics ?: remember(songs) {
        buildHomeCardStatisticsSnapshot(songs)
    }
    val homeContentCounts = remember(homeStatistics, carouselSongs.size) {
        buildHomeContentCounts(homeStatistics, carouselSongs.size)
    }
    val externalLibraryOwner = LocalReferenceLibraryProviderRegistry.current != null &&
        !LocalReferenceLibraryBodyRender.current
    val virtualListProvider = if (externalLibraryOwner) {
        rememberHomeVirtualListProviderSpec(
            songs = songs,
            currentSong = carouselCurrentSong,
            queueSongs = carouselSongs,
            queueCurrentIndex = queueCurrentIndex,
            currentLyric = effectiveLyric,
            currentLyricTranslation = effectiveTranslation,
            lyricSong = effectiveLyricSong,
            playbackPositionMs = effectivePosition,
            isPlaying = effectivePlaying,
            playCounts = playCounts,
            carouselState = carouselState,
            headerOptions = headerOptions,
            homeCardLayoutState = homeCardLayoutState,
            homeContentCounts = homeContentCounts,
            homeCardEditMode = homeCardEditMode,
            libraryCardDragPreview = libraryCardDragPreview,
            toolCardDragPreview = toolCardDragPreview,
            onHomeCardEditModeChange = { homeCardEditMode = it },
            onAddHomeCard = { group ->
                addCardGroup = group
                showAddCardDialog = true
            },
            onNavigate = onNavigate,
            onSearchClick = onSearchClick,
            showSettingsShortcut = showSettingsShortcut,
            onSettingsClick = onSettingsClick,
            onHeaderMenuClick = onHeaderMenuActionOverride ?: { showHeaderMenuDialog = true },
            onFlowBackgroundClick = { showFlowModeDialog = true },
            onCurrentPlayPause = onCurrentPlayPause,
            onSongClick = onSongClick,
            onQueueNavigate = onQueueNavigate,
            onCarouselSongSelect = onCarouselSongSelect,
            onCurrentArtworkLongPress = onCurrentArtworkLongPress,
            onCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
            hideCenterForFullscreenTransition = hideCenterForFullscreenTransition,
            centerReflectionAlpha = centerReflectionAlpha,
            centerReflectionArtworkKey = centerReflectionArtworkKey,
        )
    } else null
    if (LocalReferenceLibraryProviderPublicationOnly.current &&
        externalLibraryOwner &&
        virtualListProvider != null
    ) {
        // Provider capture is the Compose equivalent of Reference binding the next adapter into the
        // same VirtualList. This must also be true for the hidden full-scene preflight. Previously
        // FullScenePreflight deliberately bypassed this return and rebuilt HOME carousel/cards/
        // dialogs in the navigation hot path, while retained-view implementation only binds the next provider/LayoutRes.
        // Do not compose HOME backdrop/carousel/chrome or a second physical list.
        CompositionLocalProvider(LocalVirtualListCustomProvider provides virtualListProvider.customProvider) {
            ComposeVirtualList(
                songs = virtualListProvider.items,
                state = virtualListState,
                pinchEnabled = false,
                contentTopPadding = 0.dp,
                onSongClick = { _, _ -> },
                onSongLongClick = { _, _ -> },
                modifier = Modifier.fillMaxSize(),
            )
        }
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (renderBackdrop && !retainedLibraryPopulation) {
            HomeArtworkCarouselBackdrop(
                songs = carouselSongs,
                currentSong = carouselCurrentSong,
                state = carouselState,
                modifier = Modifier.fillMaxSize()
            )
        }
        if (externalLibraryOwner && virtualListProvider != null) {
            CompositionLocalProvider(LocalVirtualListCustomProvider provides virtualListProvider.customProvider) {
                ComposeVirtualList(
                    songs = virtualListProvider.items,
                    state = virtualListState,
                    pinchEnabled = false,
                    contentTopPadding = 0.dp,
                    onSongClick = { _, _ -> },
                    onSongLongClick = { _, _ -> },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        } else {
            HomePageContent(
                modifier = Modifier.retainedSceneLayoutTransform(),
                songs = songs,
                currentSong = carouselCurrentSong,
                queueSongs = carouselSongs,
                carouselState = carouselState,
                carouselVisible = headerOptions.carouselVisible,
                carouselStyle = headerOptions.carouselStyle,
                carouselLyricVisible = headerOptions.carouselLyricVisible,
                carouselGestureLocked = headerOptions.carouselGestureLocked,
                currentLyric = effectiveLyric,
                currentLyricTranslation = effectiveTranslation,
                lyricSong = effectiveLyricSong,
                playbackPositionMs = effectivePosition,
                isPlaying = effectivePlaying,
                playCounts = playCounts,
                mostPlayedVisible = headerOptions.mostPlayedVisible,
                homeCardLayoutState = homeCardLayoutState,
                homeContentCounts = homeContentCounts,
                homeCardEditMode = homeCardEditMode,
                libraryCardDragPreview = libraryCardDragPreview,
                toolCardDragPreview = toolCardDragPreview,
                onHomeCardEditModeChange = { homeCardEditMode = it },
                onAddHomeCard = { group ->
                    addCardGroup = group
                    showAddCardDialog = true
                },
                listState = listState,
                onNavigate = onNavigate,
                onSearchClick = onSearchClick,
                weatherVisible = headerOptions.weatherVisible,
                onHeaderMenuClick = onHeaderMenuActionOverride ?: { showHeaderMenuDialog = true },
                onFlowBackgroundClick = { showFlowModeDialog = true },
                showSettingsShortcut = showSettingsShortcut,
                onSettingsClick = onSettingsClick,
                onCurrentPlayPause = onCurrentPlayPause,
                onSongClick = onSongClick,
                onQueueNavigate = onQueueNavigate,
                onCarouselSongSelect = onCarouselSongSelect,
                onCurrentArtworkLongPress = onCurrentArtworkLongPress,
                onCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
                hideCenterForFullscreenTransition = hideCenterForFullscreenTransition,
                centerReflectionAlpha = centerReflectionAlpha,
                centerReflectionArtworkKey = centerReflectionArtworkKey,
                sceneMotionFrozen = sceneMotionFrozen,
            )
        }

        if (!retainedLibraryPopulation) {
            PublishHomeFloatingHeader(
                sourceCoverKey = carouselCurrentSong.resolvePlaybackArtworkKey(null),
                virtualListState = virtualListState.takeIf {
                    externalLibraryOwner && virtualListProvider != null
                },
                scrollState = listState.takeUnless {
                    externalLibraryOwner && virtualListProvider != null
                },
                showSettingsShortcut = showSettingsShortcut,
                onMenuClick = onHeaderMenuActionOverride ?: { showHeaderMenuDialog = true },
                onFlowBackgroundClick = { showFlowModeDialog = true },
                onSettingsClick = onSettingsClick,
            )
        }

        if (!retainedLibraryPopulation) {
        HomeHeaderMenuDialog(
            show = showHeaderMenuDialog,
            weatherVisible = headerOptions.weatherVisible,
            onWeatherVisibleChange = headerOptions::updateWeatherVisible,
            carouselVisible = headerOptions.carouselVisible,
            onCarouselVisibleChange = headerOptions::updateCarouselVisible,
            carouselLyricVisible = headerOptions.carouselLyricVisible,
            onCarouselLyricVisibleChange = headerOptions::updateCarouselLyricVisible,
            carouselStyle = headerOptions.carouselStyle,
            onCarouselStyleChange = headerOptions::updateCarouselStyle,
            onDismissRequest = { showHeaderMenuDialog = false }
        )

        RawFlowModeDialog(
            show = showFlowModeDialog,
            selectedMode = rawFlowMode,
            onSelectMode = setRawFlowMode,
            onDismissRequest = { showFlowModeDialog = false }
        )

        HomeCardAddDialog(
            show = showAddCardDialog,
            group = addCardGroup,
            state = homeCardLayoutState,
            contentCounts = homeContentCounts,
            onDismissRequest = { showAddCardDialog = false },
        )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomePageContent(
    modifier: Modifier = Modifier,
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    carouselState: HomeArtworkCarouselState,
    carouselVisible: Boolean,
    carouselStyle: HomeArtworkCarouselStyle,
    carouselLyricVisible: Boolean,
    carouselGestureLocked: Boolean,
    currentLyric: String,
    currentLyricTranslation: String,
    lyricSong: Song?,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    playCounts: Map<Long, Int>,
    mostPlayedVisible: Boolean,
    homeCardLayoutState: HomeCardLayoutState,
    homeContentCounts: Map<NavScene, Int>,
    homeCardEditMode: Boolean,
    libraryCardDragPreview: HomeCardDragPreviewState,
    toolCardDragPreview: HomeCardDragPreviewState,
    onHomeCardEditModeChange: (Boolean) -> Unit,
    onAddHomeCard: (HomeCardGroup) -> Unit,
    listState: ScrollState = rememberScrollState(),
    onNavigate: (NavScene) -> Unit,
    onSearchClick: () -> Unit,
    weatherVisible: Boolean,
    onHeaderMenuClick: () -> Unit,
    onFlowBackgroundClick: () -> Unit,
    showSettingsShortcut: Boolean,
    onSettingsClick: () -> Unit,
    onCurrentPlayPause: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onQueueNavigate: (Int) -> Unit,
    onCarouselSongSelect: (List<AudioFile>, AudioFile, Int) -> Unit,
    onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit,
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit,
    hideCenterForFullscreenTransition: Boolean,
    centerReflectionAlpha: Float,
    centerReflectionArtworkKey: String,
    sceneMotionFrozen: Boolean,
) {
    val visibleLibraryScenes = homeCardLayoutState.scenes(HomeCardGroup.LIBRARY)
    val visibleToolScenes = homeCardLayoutState.scenes(HomeCardGroup.TOOLS)
    val libraryCards = remember(songs, visibleLibraryScenes) { homeLibraryCards(songs, visibleLibraryScenes) }
    val toolCards = remember(songs, visibleToolScenes) { homeToolCards(songs, visibleToolScenes) }
    val librarySlots = remember(libraryCards, homeCardEditMode, homeCardLayoutState.hiddenScenes) {
        buildHomeCardSlots(
            cards = libraryCards,
            includeAdd = homeCardEditMode && homeCardLayoutState.hidden(HomeCardGroup.LIBRARY).isNotEmpty(),
        )
    }
    val toolSlots = remember(toolCards, homeCardEditMode, homeCardLayoutState.hiddenScenes) {
        buildHomeCardSlots(
            cards = toolCards,
            includeAdd = homeCardEditMode && homeCardLayoutState.hidden(HomeCardGroup.TOOLS).isNotEmpty(),
        )
    }
    val mostPlayed = remember(songs, playCounts, mostPlayedVisible) {
        if (!mostPlayedVisible) {
            emptyList()
        } else {
            songs.sortedWith(
                compareByDescending<AudioFile> { playCounts[it.id] ?: 0 }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            ).take(10)
        }
    }

    // HOME is a small heterogeneous provider (header/carousel/cards/10 rows/tools). Reference keeps
    // those holders attached under one VirtualList owner; a LazyColumn would create a second lazy
    // recycling engine beside ComposeVirtualList and rebind it during PivotTransition. Keep the complete
    // HOME holder population persistent and move only this one scrolling layout.
    Column(
        modifier = modifier
            .fillMaxSize()
            .rawStableStatusBarsPadding()
            .padding(horizontal = 16.dp)
            .verticalScroll(listState),
        verticalArrangement = Arrangement.spacedBy(22.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        ) {
            HomeTopHeader(
                weatherVisible = weatherVisible,
            )
            Spacer(Modifier.height(14.dp))
            SearchBar(
                inputField = {
                    InputField(
                        query = "",
                        onQueryChange = {},
                        onSearch = {},
                        expanded = false,
                        onExpandedChange = { onSearchClick() },
                        label = stringResource(R.string.search_hint),
                        enabled = false
                    )
                },
                onExpandedChange = { onSearchClick() },
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSearchClick() },
                expanded = false,
                content = {}
            )
        }

        if (carouselVisible) {
            Box(modifier = Modifier.fillMaxWidth()) {
                HomeArtworkCarousel(
                    songs = queueSongs,
                    currentSong = currentSong,
                    state = carouselState,
                    style = carouselStyle,
                    showLyrics = carouselLyricVisible,
                    gestureLocked = carouselGestureLocked,
                    currentLyric = currentLyric,
                    currentLyricTranslation = currentLyricTranslation,
                    lyricSong = lyricSong,
                    playbackPositionMs = playbackPositionMs,
                    isPlaying = isPlaying,
                    onNavigate = onQueueNavigate,
                    onSelectSong = { song, index ->
                        onCarouselSongSelect(queueSongs, song, index)
                    },
                    onCurrentArtworkLongPress = onCurrentArtworkLongPress,
                    onCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
                    hideCenterForFullscreenTransition = hideCenterForFullscreenTransition,
                    centerReflectionAlpha = centerReflectionAlpha,
                    centerReflectionArtworkKey = centerReflectionArtworkKey,
                    hostFrozen = sceneMotionFrozen,
                )
            }
        }

        HomeSectionTitle(
            text = stringResource(R.string.home_library_section),
            statistics = stringResource(
                R.string.home_library_section_statistics,
                visibleLibraryScenes.size,
                songs.size,
            ),
            editing = homeCardEditMode,
            onDone = { onHomeCardEditModeChange(false) },
        )
        librarySlots.chunked(2).forEach { row ->
            HomeCardSlotRow(
                row = row,
                group = HomeCardGroup.LIBRARY,
                editMode = homeCardEditMode,
                contentCounts = homeContentCounts,
                layoutState = homeCardLayoutState,
                visibleOrder = visibleLibraryScenes,
                dragPreview = libraryCardDragPreview,
                onEnterEdit = { onHomeCardEditModeChange(true) },
                onAdd = { onAddHomeCard(HomeCardGroup.LIBRARY) },
                onNavigate = onNavigate,
            )
        }

        if (mostPlayedVisible) {
            SectionTitle(stringResource(R.string.home_most_played_section))
            mostPlayed.forEach { song ->
                val index = remember(songs, song.id) {
                    songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
                }
                val isCurrentSong = currentSong?.let { current ->
                    current.path == song.path &&
                        current.cueOffsetMs == song.cueOffsetMs &&
                        current.cueTrackIndex == song.cueTrackIndex
                } == true
                MostPlayedRow(
                    song = song,
                    playCount = playCounts[song.id] ?: 0,
                    isCurrentSong = isCurrentSong,
                    isPlaying = isCurrentSong && isPlaying,
                    onClick = { onSongClick(song, index) },
                    onPlayPauseClick = {
                        if (isCurrentSong) onCurrentPlayPause() else onSongClick(song, index)
                    }
                )
            }
        }

        HomeSectionTitle(
            text = stringResource(R.string.home_tools_section),
            statistics = stringResource(R.string.home_tools_section_statistics, visibleToolScenes.size),
            editing = homeCardEditMode,
            onDone = { onHomeCardEditModeChange(false) },
        )
        toolSlots.chunked(2).forEach { row ->
            HomeCardSlotRow(
                row = row,
                group = HomeCardGroup.TOOLS,
                editMode = homeCardEditMode,
                contentCounts = homeContentCounts,
                layoutState = homeCardLayoutState,
                visibleOrder = visibleToolScenes,
                dragPreview = toolCardDragPreview,
                onEnterEdit = { onHomeCardEditModeChange(true) },
                onAdd = { onAddHomeCard(HomeCardGroup.TOOLS) },
                onNavigate = onNavigate,
            )
        }
        Spacer(modifier = Modifier.height(170.dp))
    }
}
@Composable
fun Daily20Page(
    songs: List<AudioFile>,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit
) {
    var todayKey by remember { mutableStateOf(todayKey()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextDay())
            todayKey = todayKey()
        }
    }

    val dailySongs = remember(songs, todayKey) { daily20Songs(songs, todayKey) }
    val coverSong = dailySongs.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
            ) {
                if (coverSong?.coverKey?.isNotBlank() == true) {
                    BitmapImage(
                        key = coverSong.coverKey,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetWidth = 1600,
                        targetHeight = 1600
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(pastelColorFor("daily-bg-$todayKey"))
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.04f),
                                    Color.Black.copy(alpha = 0.08f),
                                    MiuixTheme.colorScheme.background.copy(alpha = 0.72f),
                                    MiuixTheme.colorScheme.background.copy(alpha = 0.38f)
                                ),
                                startY = 0f,
                                endY = 1200f
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .rawStableStatusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(onClick = onBack) {
                        VectorIcon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.library_action_back),
                            tint = Color.White
                        )
                    }
                    CircleIconButton(onClick = {}) {
                        Image(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.home_action_share),
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 24.dp)
                ) {
                    Text(stringResource(R.string.home_daily_songs_title), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = if (usesReferenceStaticForeground()) Color.White else Color.Black)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.home_daily_songs_summary),
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                        color = if (usesReferenceStaticForeground()) Color.White.copy(alpha = 0.78f) else Color(0xFF2F3440),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillAction(
                            text = stringResource(R.string.home_favorite_playlist),
                            icon = { VectorIcon(Icons.Default.Favorite, contentDescription = null, tint = if (usesReferenceStaticForeground()) Color.White else Color.Black) },
                            modifier = Modifier.weight(1f)
                        )
                        PillAction(
                            text = stringResource(R.string.home_play_all),
                            icon = { VectorIcon(Icons.Default.PlayArrow, contentDescription = null, tint = if (usesReferenceStaticForeground()) Color.White else Color.Black) },
                            modifier = Modifier.weight(1f),
                            onClick = { if (dailySongs.isNotEmpty()) onPlayQueue(dailySongs, 0) }
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(stringResource(R.string.home_song_count, dailySongs.size), fontSize = 23.sp, fontWeight = FontWeight.Medium, color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground))
                Text(todayKey, fontSize = 13.sp, color = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.72f))
            }
        }

        items(dailySongs) { song ->
            val position = dailySongs.indexOf(song)
            DailySongRow(
                position = position + 1,
                song = song,
                onClick = { onPlayQueue(dailySongs, position) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(170.dp))
        }
    }
}

@Composable
private fun HomeSectionTitle(
    text: String,
    statistics: String,
    editing: Boolean,
    onDone: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = text,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (statistics.isNotBlank()) {
                Text(
                    text = statistics,
                    fontSize = 10.5.sp,
                    fontWeight = FontWeight.Medium,
                    color = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.72f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (editing) {
            Text(
                text = stringResource(R.string.home_card_edit_done),
                color = referenceStaticForeground(MiuixTheme.colorScheme.primary),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .clickable(onClick = onDone)
                    .padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun HomeCardSlotRow(
    row: List<HomeCardSlot>,
    group: HomeCardGroup,
    editMode: Boolean,
    contentCounts: Map<NavScene, Int>,
    layoutState: HomeCardLayoutState,
    visibleOrder: List<NavScene>,
    dragPreview: HomeCardDragPreviewState,
    onEnterEdit: () -> Unit,
    onAdd: () -> Unit,
    onNavigate: (NavScene) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        row.forEach { slot ->
            when (slot) {
                is HomeCardSlot.Card -> {
                    val card = slot.value
                    LibraryTile(
                        scene = card.scene,
                        song = card.song,
                        contentCount = contentCounts[card.scene],
                        modifier = Modifier.weight(1f),
                        editMode = editMode,
                        onEnterEdit = onEnterEdit,
                        onRemove = { layoutState.hide(card.scene) },
                        visibleOrder = visibleOrder,
                        dragPreview = dragPreview,
                        onMoveTo = { target -> layoutState.moveVisibleTo(card.scene, target) },
                        onClick = { onNavigate(card.scene) },
                    )
                }
                HomeCardSlot.Add -> {
                    AddHomeCardTile(
                        modifier = Modifier.weight(1f),
                        onClick = onAdd,
                    )
                }
            }
        }
        if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AddHomeCardTile(
    modifier: Modifier,
    onClick: () -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .clickable(onClick = onClick),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(13.dp))
                .background(MiuixTheme.colorScheme.surfaceContainer.copy(alpha = 0.68f)),
            contentAlignment = Alignment.Center,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.onSurface.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center,
            ) {
                Image(
                    painter = painterResource(R.drawable.ic_add_outline),
                    contentDescription = stringResource(R.string.home_card_add_title),
                    colorFilter = ColorFilter.tint(referenceStaticForeground(MiuixTheme.colorScheme.onSurface)),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = stringResource(R.string.home_card_add_tile),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.78f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun LibraryTile(
    scene: NavScene,
    song: AudioFile?,
    contentCount: Int?,
    modifier: Modifier,
    editMode: Boolean,
    onEnterEdit: () -> Unit,
    onRemove: () -> Unit,
    visibleOrder: List<NavScene>,
    dragPreview: HomeCardDragPreviewState,
    onMoveTo: (Int) -> Unit,
    onClick: () -> Unit,
) {
    var removing by remember(scene) { mutableStateOf(false) }
    val dragging = dragPreview.activeScene == scene
    val previewDisplacement = dragPreview.displacementFor(scene)
    val committedReleaseEpoch = dragPreview.committedReleaseEpoch
    var renderedCommitEpoch by remember(scene) { mutableIntStateOf(committedReleaseEpoch) }
    val snapCommittedRelease = committedReleaseEpoch != renderedCommitEpoch
    val previewTranslationX by animateFloatAsState(
        targetValue = if (dragging) dragPreview.draggedOffset(scene).x else previewDisplacement.x,
        animationSpec = if (dragging || snapCommittedRelease) tween(0) else tween(145),
        label = "home-card-preview-x-${scene.tag}",
    )
    val previewTranslationY by animateFloatAsState(
        targetValue = if (dragging) dragPreview.draggedOffset(scene).y else previewDisplacement.y,
        animationSpec = if (dragging || snapCommittedRelease) tween(0) else tween(145),
        label = "home-card-preview-y-${scene.tag}",
    )
    LaunchedEffect(committedReleaseEpoch) {
        renderedCommitEpoch = committedReleaseEpoch
    }
    val removalProgress by animateFloatAsState(
        targetValue = if (removing) 1f else 0f,
        animationSpec = tween(durationMillis = 170),
        label = "home-card-remove-${scene.tag}",
        finishedListener = { value ->
            if (value >= 0.999f && removing) onRemove()
        },
    )
    val interactionModifier = if (editMode) {
        Modifier.pointerInput(scene, visibleOrder) {
            detectDragGesturesAfterLongPress(
                onDragStart = { dragPreview.start(scene, visibleOrder) },
                onDragCancel = { dragPreview.clear() },
                onDragEnd = {
                    val target = dragPreview.targetIndex(scene)
                    if (target == null) {
                        dragPreview.clear()
                    } else {
                        // Both mutations happen in the same snapshot/frame: the provider adopts the
                        // new canonical order while the temporary drag translations are released,
                        // avoiding an intermediate snap back to the source slot.
                        onMoveTo(target)
                        dragPreview.commitAndClear()
                    }
                },
                onDrag = { change, dragAmount ->
                    change.consume()
                    dragPreview.dragBy(dragAmount.x, dragAmount.y)
                },
            )
        }
    } else {
        Modifier.combinedClickable(
            onClick = onClick,
            onLongClick = onEnterEdit,
        )
    }

    Column(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                dragPreview.publishSlot(scene, coordinates.boundsInRoot())
            }
            .graphicsLayer {
                val removeScale = 1f - 0.35f * removalProgress
                val dragScale = if (dragging) 1.035f else 1f
                scaleX = removeScale * dragScale
                scaleY = removeScale * dragScale
                translationX = previewTranslationX
                translationY = previewTranslationY
                alpha = 1f - removalProgress
            }
            .then(interactionModifier),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(13.dp))
                .background(pastelColorFor(scene.tag)),
        ) {
            if (scene == NavScene.SOURCE_IMPORT) {
                Image(
                    painter = painterResource(R.drawable.ic_cloud),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.94f)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(58.dp),
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.05f),
                                    Color.Black.copy(alpha = 0.18f),
                                )
                            )
                        ),
                )
            } else if (song?.coverKey?.isNotBlank() == true) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 360,
                    targetHeight = 360,
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.36f)))),
                )
            }

            if (editMode) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = (-7).dp, y = 7.dp)
                        .size(33.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.66f))
                        .clickable(enabled = !removing) { removing = true },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_close),
                        contentDescription = stringResource(R.string.home_card_remove_action),
                        colorFilter = ColorFilter.tint(Color.White),
                        modifier = Modifier.size(17.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            text = scene.label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        val statistics = homeCardStatisticsText(scene, contentCount)
        if (statistics.isNotBlank()) {
            Text(
                text = statistics,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                color = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.70f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 1.dp),
            )
        }
    }
}

@Composable
private fun homeCardStatisticsText(scene: NavScene, count: Int?): String {
    if (count == null) return ""
    return when (scene) {
        NavScene.SONGS, NavScene.QUEUE, NavScene.RECENTLY_ADDED,
        NavScene.ANALYTICS, NavScene.SONG_STATS -> stringResource(R.string.library_statistics_songs, count)
        NavScene.FOLDERS, NavScene.FOLDER_HIERARCHY -> stringResource(R.string.home_card_statistics_folders, count)
        NavScene.ALBUMS -> stringResource(R.string.home_card_statistics_albums, count)
        NavScene.ARTISTS -> stringResource(R.string.home_card_statistics_artists, count)
        NavScene.GENRE -> stringResource(R.string.home_card_statistics_genres, count)
        NavScene.YEAR -> stringResource(R.string.home_card_statistics_years, count)
        NavScene.COMPOSER -> stringResource(R.string.home_card_statistics_composers, count)
        else -> stringResource(R.string.home_card_content_count, count)
    }
}

@Composable
private fun MostPlayedRow(
    song: AudioFile,
    playCount: Int,
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayPauseClick: () -> Unit,
    contentHeight: androidx.compose.ui.unit.Dp? = null,
) {
    val resolvedContentHeight = contentHeight
        ?: homeMostPlayedContentHeightDp(LocalDensity.current.fontScale).dp
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(resolvedContentHeight)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(pastelColorFor(song.title))
        ) {
            if (song.coverKey.isNotBlank()) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 160,
                    targetHeight = 160
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.displayName, fontSize = 18.sp, color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { stringResource(R.string.common_unknown_artist) }, fontSize = 13.sp, color = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.home_play_count, playCount), fontSize = 13.sp, color = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.72f))
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .clickable(onClick = onPlayPauseClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.common_pause else R.string.common_play),
                colorFilter = ColorFilter.tint(
                    if (isCurrentSong) referenceStaticForeground(MiuixTheme.colorScheme.primary)
                    else referenceStaticForeground(MiuixTheme.colorScheme.onBackground)
                ),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun DailySongRow(
    position: Int,
    song: AudioFile,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clickable { onClick() }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(position.toString(), fontSize = 22.sp, color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground), modifier = Modifier.width(34.dp))
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(pastelColorFor(song.title))
        ) {
            if (song.coverKey.isNotBlank()) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 160,
                    targetHeight = 160
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.displayName, fontSize = 19.sp, color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { stringResource(R.string.common_unknown_artist) }, fontSize = 14.sp, color = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.72f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        VectorIcon(Icons.Default.PlayArrow, contentDescription = null, tint = referenceStaticForeground(MiuixTheme.colorScheme.onSurfaceVariantSummary, 0.72f), modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun Modifier.retainedSceneLayoutTransform(): Modifier {
    // PivotTransition is a layout-state transform, not 30+ independent Compose scene layers. Keep one
    // persistent RenderNode around HOME's scrolling layout for the complete settled -> drag -> settle
    // lifetime and update only its properties. The backdrop/dialog chrome stays outside this owner.
    val sceneTransform by rememberUpdatedState(LocalRetainedSceneItemTransform.current)
    val layoutBounds = remember { arrayOfNulls<Rect>(1) }
    return onGloballyPositioned { coordinates ->
        layoutBounds[0] = coordinates.boundsInRoot()
    }.graphicsLayer {
        val transform = sceneTransform
        val scale = transform?.scaleProvider()?.coerceIn(0.5f, 1.5f) ?: 1f
        val bounds = layoutBounds[0]
        if (transform != null && bounds != null) {
            val centerX = (bounds.left + bounds.right) * 0.5f
            val centerY = (bounds.top + bounds.bottom) * 0.5f
            translationX = (centerX - transform.pivotX) * (scale - 1f)
            translationY = (centerY - transform.pivotY) * (scale - 1f)
        } else {
            translationX = 0f
            translationY = 0f
        }
        scaleX = scale
        scaleY = scale
        alpha = transform?.alphaProvider()?.coerceIn(0f, 1f) ?: 1f
        transformOrigin = TransformOrigin(0.5f, 0.5f)
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 25.sp,
        fontWeight = FontWeight.Bold,
        color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground),
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun CircleIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PillAction(
    text: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(if (usesReferenceStaticForeground()) Color.Black.copy(alpha = 0.28f) else Color.White)
            .clickable { onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 18.sp, color = if (usesReferenceStaticForeground()) Color.White else Color.Black, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun HomeTopHeader(
    weatherVisible: Boolean,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = stringResource(R.string.bottom_nav_home),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = referenceStaticForeground(MiuixTheme.colorScheme.onBackground),
                modifier = Modifier.align(Alignment.Center)
            )

        }

        if (weatherVisible) {
            Spacer(Modifier.height(4.dp))
            HomeWeatherHeader(modifier = Modifier.fillMaxWidth())
        }
    }
}

private data class LibraryHomeCard(
    val scene: NavScene,
    val song: AudioFile?
)

private sealed interface HomeCardSlot {
    data class Card(val value: LibraryHomeCard) : HomeCardSlot
    data object Add : HomeCardSlot
}

private fun buildHomeCardSlots(
    cards: List<LibraryHomeCard>,
    includeAdd: Boolean,
): List<HomeCardSlot> = buildList {
    cards.forEach { add(HomeCardSlot.Card(it)) }
    if (includeAdd) add(HomeCardSlot.Add)
}

private fun homeLibraryCards(songs: List<AudioFile>, scenes: List<NavScene>): List<LibraryHomeCard> =
    stableHomeCards(songs, scenes, salt = "library")

private fun homeToolCards(songs: List<AudioFile>, scenes: List<NavScene>): List<LibraryHomeCard> =
    stableHomeCards(songs, scenes, salt = "tools")

private fun stableHomeCards(
    songs: List<AudioFile>,
    scenes: List<NavScene>,
    salt: String,
): List<LibraryHomeCard> {
    val covers = songs
        .filter { it.coverKey.isNotBlank() }
        .ifEmpty { songs }
        .sortedWith(compareBy<AudioFile> { it.coverKey }.thenBy { it.path }.thenBy { it.cueTrackIndex })
    return scenes.map { scene ->
        val index = stableHomeCardArtworkIndex(scene.tag, salt, covers.size)
        LibraryHomeCard(scene, covers.getOrNull(index))
    }
}

private fun daily20Songs(songs: List<AudioFile>, dateKey: String): List<AudioFile> {
    return songs.stableShuffled("daily-20-$dateKey").take(20)
}

private fun List<AudioFile>.stableShuffled(seedKey: String): List<AudioFile> {
    if (isEmpty()) return emptyList()
    val seed = seedKey.hashCode().toLong() * 31L + size
    return shuffled(Random(seed))
}

private fun pastelColorFor(key: String): Color {
    val palette = listOf(
        Color(0xFFFF7F7C),
        Color(0xFF8FA2F0),
        Color(0xFF6EC8B7),
        Color(0xFFE5B46E),
        Color(0xFFA5C778),
        Color(0xFFD88DB5),
        Color(0xFF80B7D8),
        Color(0xFFC3A4E8)
    )
    return palette[key.hashCode().absoluteValue % palette.size]
}

private fun todayKey(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}

private fun millisUntilNextDay(): Long {
    val now = Calendar.getInstance()
    val next = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return (next.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
}

@Composable
private fun VectorIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier.size(24.dp)
) {
    Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}


internal const val HOME_VIRTUAL_LIST_ENCODING_PREFIX = "raws_home_holder:"
private const val HOME_HOLDER_HERO = "hero"
private const val HOME_HOLDER_LIBRARY_HEADER = "library_header"
private const val HOME_HOLDER_LIBRARY_ROW = "library_row"
private const val HOME_HOLDER_MOST_HEADER = "most_header"
private const val HOME_HOLDER_MOST_ROW = "most_row"
private const val HOME_HOLDER_TOOLS_HEADER = "tools_header"
private const val HOME_HOLDER_TOOLS_ROW = "tools_row"
private const val HOME_HOLDER_BOTTOM_SPACER = "bottom_spacer"

internal data class HomeVirtualListProviderSpec(
    val items: List<AudioFile>,
    val customProvider: VirtualListCustomProvider,
)

/**
 * HOME as heterogeneous holders inside the same physical VirtualList engine used by library rows.
 *
 * The old HOME LazyColumn/verticalScroll was a second recycling/layout owner.  Reference instead
 * binds different holder layout resources through one VirtualList adapter.  Keep the exact HOME
 * visual content, but expose it as Hero / Library / MostPlayed / Tools holder types.
 */
@Composable
internal fun rememberHomeVirtualListProviderSpec(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    currentLyric: String,
    currentLyricTranslation: String,
    lyricSong: Song?,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    playCounts: Map<Long, Int>,
    carouselState: HomeArtworkCarouselState,
    headerOptions: HomeHeaderOptionsState,
    homeCardLayoutState: HomeCardLayoutState,
    homeContentCounts: Map<NavScene, Int>,
    homeCardEditMode: Boolean,
    libraryCardDragPreview: HomeCardDragPreviewState,
    toolCardDragPreview: HomeCardDragPreviewState,
    onHomeCardEditModeChange: (Boolean) -> Unit,
    onAddHomeCard: (HomeCardGroup) -> Unit,
    onNavigate: (NavScene) -> Unit,
    onSearchClick: () -> Unit,
    showSettingsShortcut: Boolean,
    onSettingsClick: () -> Unit,
    onHeaderMenuClick: () -> Unit,
    onFlowBackgroundClick: () -> Unit,
    onCurrentPlayPause: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onQueueNavigate: (Int) -> Unit,
    onCarouselSongSelect: (List<AudioFile>, AudioFile, Int) -> Unit,
    onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit,
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit,
    hideCenterForFullscreenTransition: Boolean,
    centerReflectionAlpha: Float,
    centerReflectionArtworkKey: String,
): HomeVirtualListProviderSpec {
    val sceneMotionFrozen = LocalSceneBackgroundFrozen.current
    val retainedQueue = remember { arrayOf(queueSongs) }
    val retainedCurrent = remember { arrayOf(currentSong) }
    val retainedLyric = remember { arrayOf(currentLyric) }
    val retainedTranslation = remember { arrayOf(currentLyricTranslation) }
    val retainedLyricSong = remember { arrayOf(lyricSong) }
    val retainedPosition = remember { longArrayOf(playbackPositionMs) }
    val retainedPlaying = remember { booleanArrayOf(isPlaying) }
    if (!sceneMotionFrozen) {
        retainedQueue[0] = queueSongs
        retainedCurrent[0] = currentSong
        retainedLyric[0] = currentLyric
        retainedTranslation[0] = currentLyricTranslation
        retainedLyricSong[0] = lyricSong
        retainedPosition[0] = playbackPositionMs
        retainedPlaying[0] = isPlaying
    }
    val effectiveQueueSongs = if (sceneMotionFrozen) retainedQueue[0] else queueSongs
    val effectiveCurrentSong = if (sceneMotionFrozen) retainedCurrent[0] else currentSong
    val effectiveLyric = if (sceneMotionFrozen) retainedLyric[0] else currentLyric
    val effectiveTranslation = if (sceneMotionFrozen) retainedTranslation[0] else currentLyricTranslation
    val effectiveLyricSong = if (sceneMotionFrozen) retainedLyricSong[0] else lyricSong
    val effectivePosition = if (sceneMotionFrozen) retainedPosition[0] else playbackPositionMs
    val effectivePlaying = if (sceneMotionFrozen) retainedPlaying[0] else isPlaying
    val carouselSongs = remember(effectiveQueueSongs, effectiveCurrentSong, queueCurrentIndex) {
        effectiveQueueSongs.ifEmpty { listOfNotNull(effectiveCurrentSong) }
    }
    // Keep the persistent HOME provider on the same committed playback identity as the visible
    // page. queueCurrentIndex is only a carousel coordinate and can lead currentSong by a frame.
    val carouselCurrentSong = effectiveCurrentSong
    val latestCarouselSongs by rememberUpdatedState(carouselSongs)
    val latestCarouselCurrentSong by rememberUpdatedState(carouselCurrentSong)
    val latestLyric by rememberUpdatedState(effectiveLyric)
    val latestTranslation by rememberUpdatedState(effectiveTranslation)
    val latestLyricSong by rememberUpdatedState(effectiveLyricSong)
    val latestPosition by rememberUpdatedState(effectivePosition)
    val latestPlaying by rememberUpdatedState(effectivePlaying)
    val latestSceneMotionFrozen by rememberUpdatedState(sceneMotionFrozen)
    val visibleLibraryScenes = homeCardLayoutState.scenes(HomeCardGroup.LIBRARY)
    val visibleToolScenes = homeCardLayoutState.scenes(HomeCardGroup.TOOLS)
    val libraryCards = remember(songs, visibleLibraryScenes) { homeLibraryCards(songs, visibleLibraryScenes) }
    val toolCards = remember(songs, visibleToolScenes) { homeToolCards(songs, visibleToolScenes) }
    val librarySlots = remember(libraryCards, homeCardEditMode, homeCardLayoutState.hiddenScenes) {
        buildHomeCardSlots(
            cards = libraryCards,
            includeAdd = homeCardEditMode && homeCardLayoutState.hidden(HomeCardGroup.LIBRARY).isNotEmpty(),
        )
    }
    val toolSlots = remember(toolCards, homeCardEditMode, homeCardLayoutState.hiddenScenes) {
        buildHomeCardSlots(
            cards = toolCards,
            includeAdd = homeCardEditMode && homeCardLayoutState.hidden(HomeCardGroup.TOOLS).isNotEmpty(),
        )
    }
    val mostPlayed = remember(songs, playCounts, headerOptions.mostPlayedVisible) {
        if (!headerOptions.mostPlayedVisible) {
            emptyList()
        } else {
            songs.sortedWith(
                compareByDescending<AudioFile> { playCounts[it.id] ?: 0 }
                    .thenBy { it.title.lowercase(Locale.getDefault()) }
            ).take(10)
        }
    }
    val mostPlayedIndex = remember(songs, mostPlayed) {
        mostPlayed.associate { song -> song.id to songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0) }
    }
    val libraryRows = remember(librarySlots) { librarySlots.chunked(2) }
    val toolRows = remember(toolSlots) { toolSlots.chunked(2) }
    val screenWidthDp = LocalConfiguration.current.screenWidthDp
    val fontScale = LocalDensity.current.fontScale
    // HOME cards are square. v15 packed six library rows and two tool rows into two fixed 330dp
    // holders, so their children were forced to overlap/squeeze. Give every physical row its own
    // holder and derive the row height from the same two-column geometry used by LibraryTile, plus
    // a font-scale-aware text envelope so larger global/accessibility fonts are never clipped.
    val cardRowHeight = remember(screenWidthDp, fontScale) {
        val tileSide = ((screenWidthDp - 32 - 14).coerceAtLeast(240) / 2f)
        (tileSide + homeCardRowExtraHeightDp(fontScale)).dp
    }
    val sectionHeaderHeight = remember(fontScale) {
        homeSectionHeaderHeightDp(fontScale).dp
    }
    val mostPlayedContentHeight = remember(fontScale) {
        homeMostPlayedContentHeightDp(fontScale).dp
    }
    val mostPlayedHolderHeight = remember(fontScale) {
        homeMostPlayedHolderHeightDp(fontScale).dp
    }
    val heroHeight = remember(
        headerOptions.weatherVisible,
        headerOptions.carouselVisible,
        headerOptions.carouselLyricVisible,
        headerOptions.carouselStyle,
        fontScale,
    ) {
        // Keep the heterogeneous HOME hero holder equal to the content it actually composes. The
        // header/search area plus the original ~22dp section gap is 182dp; the carousel contributes
        // its real runtime height only while enabled, so hiding it removes the blank 360dp reserve.
        val headerHeight = 182.dp + if (headerOptions.weatherVisible) 64.dp else 0.dp
        if (!headerOptions.carouselVisible) {
            headerHeight
        } else {
            headerHeight + 20.dp + homeArtworkCarouselHeight(
                style = headerOptions.carouselStyle,
                showLyrics = headerOptions.carouselLyricVisible,
                fontScale = fontScale,
            )
        }
    }

    val items = remember(mostPlayed, libraryRows.size, toolRows.size, headerOptions.mostPlayedVisible) {
        buildList {
            add(homeSyntheticAudioFile(-9_100_001L, HOME_HOLDER_HERO, "HOME Hero"))
            add(homeSyntheticAudioFile(-9_100_002L, HOME_HOLDER_LIBRARY_HEADER, "HOME Library"))
            repeat(libraryRows.size) { row ->
                add(homeSyntheticAudioFile(-9_110_000L - row, "$HOME_HOLDER_LIBRARY_ROW:$row", "HOME Library $row"))
            }
            if (headerOptions.mostPlayedVisible) {
                add(homeSyntheticAudioFile(-9_100_003L, HOME_HOLDER_MOST_HEADER, "HOME Most Played"))
                mostPlayed.forEach { song ->
                    add(homeSyntheticAudioFile(-9_200_000L - song.id.absoluteValue, "$HOME_HOLDER_MOST_ROW:${song.id}", song.displayName))
                }
            }
            add(homeSyntheticAudioFile(-9_100_004L, HOME_HOLDER_TOOLS_HEADER, "HOME Tools"))
            repeat(toolRows.size) { row ->
                add(homeSyntheticAudioFile(-9_120_000L - row, "$HOME_HOLDER_TOOLS_ROW:$row", "HOME Tools $row"))
            }
            add(homeSyntheticAudioFile(-9_100_005L, HOME_HOLDER_BOTTOM_SPACER, "HOME Bottom"))
        }
    }
    val mostPlayedById = remember(mostPlayed) { mostPlayed.associateBy { it.id } }

    val provider = remember(
        songs,
        playCounts,
        libraryRows,
        toolRows,
        homeContentCounts,
        homeCardEditMode,
        homeCardLayoutState.libraryOrder,
        homeCardLayoutState.toolOrder,
        homeCardLayoutState.hiddenScenes,
        cardRowHeight,
        sectionHeaderHeight,
        mostPlayedContentHeight,
        mostPlayedHolderHeight,
        heroHeight,
        mostPlayedById,
        mostPlayedIndex,
        headerOptions.carouselVisible,
        headerOptions.carouselStyle,
        headerOptions.carouselLyricVisible,
        headerOptions.weatherVisible,
        showSettingsShortcut,
        hideCenterForFullscreenTransition,
        centerReflectionAlpha,
        centerReflectionArtworkKey,
    ) {
        VirtualListCustomProvider(
            itemHeight = { song, _ ->
                val kind = song.encodingFormat.removePrefix(HOME_VIRTUAL_LIST_ENCODING_PREFIX)
                when {
                    kind == HOME_HOLDER_HERO -> heroHeight
                    kind == HOME_HOLDER_LIBRARY_HEADER -> sectionHeaderHeight
                    kind.startsWith("$HOME_HOLDER_LIBRARY_ROW:") -> cardRowHeight
                    kind == HOME_HOLDER_MOST_HEADER -> sectionHeaderHeight
                    kind.startsWith("$HOME_HOLDER_MOST_ROW:") -> mostPlayedHolderHeight
                    kind == HOME_HOLDER_TOOLS_HEADER -> sectionHeaderHeight
                    kind.startsWith("$HOME_HOLDER_TOOLS_ROW:") -> cardRowHeight
                    kind == HOME_HOLDER_BOTTOM_SPACER -> 170.dp
                    else -> null
                }
            },
            handles = { song, _ -> song.encodingFormat.startsWith(HOME_VIRTUAL_LIST_ENCODING_PREFIX) },
            content = { song, _, modifier ->
                val kind = song.encodingFormat.removePrefix(HOME_VIRTUAL_LIST_ENCODING_PREFIX)
                when {
                    kind == HOME_HOLDER_HERO -> {
                        Column(
                            modifier = modifier
                                .fillMaxSize()
                                .rawStableStatusBarsPadding()
                                .padding(start = 16.dp, top = 16.dp, end = 16.dp),
                        ) {
                            HomeTopHeader(
                                weatherVisible = headerOptions.weatherVisible,
                            )
                            Spacer(Modifier.height(14.dp))
                            SearchBar(
                                inputField = {
                                    InputField(
                                        query = "",
                                        onQueryChange = {},
                                        onSearch = {},
                                        expanded = false,
                                        onExpandedChange = { onSearchClick() },
                                        label = stringResource(R.string.search_hint),
                                        enabled = false,
                                    )
                                },
                                onExpandedChange = { onSearchClick() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onSearchClick() },
                                expanded = false,
                                content = {},
                            )
                            if (headerOptions.carouselVisible) {
                                Spacer(Modifier.height(20.dp))
                                HomeArtworkCarousel(
                                    songs = latestCarouselSongs,
                                    currentSong = latestCarouselCurrentSong,
                                    state = carouselState,
                                    style = headerOptions.carouselStyle,
                                    showLyrics = headerOptions.carouselLyricVisible,
                                    gestureLocked = headerOptions.carouselGestureLocked,
                                    currentLyric = latestLyric,
                                    currentLyricTranslation = latestTranslation,
                                    lyricSong = latestLyricSong,
                                    playbackPositionMs = latestPosition,
                                    isPlaying = latestPlaying,
                                    onNavigate = onQueueNavigate,
                                    onSelectSong = { song, index ->
                                        onCarouselSongSelect(latestCarouselSongs, song, index)
                                    },
                                    onCurrentArtworkLongPress = onCurrentArtworkLongPress,
                                    onCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
                                    hideCenterForFullscreenTransition = hideCenterForFullscreenTransition,
                                    centerReflectionAlpha = centerReflectionAlpha,
                                    centerReflectionArtworkKey = centerReflectionArtworkKey,
                                    hostFrozen = latestSceneMotionFrozen,
                                )
                            }
                        }
                    }
                    kind == HOME_HOLDER_LIBRARY_HEADER -> {
                        Box(
                            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            HomeSectionTitle(
                                text = stringResource(R.string.home_library_section),
                                statistics = stringResource(
                                    R.string.home_library_section_statistics,
                                    visibleLibraryScenes.size,
                                    songs.size,
                                ),
                                editing = homeCardEditMode,
                                onDone = { onHomeCardEditModeChange(false) },
                            )
                        }
                    }
                    kind.startsWith("$HOME_HOLDER_LIBRARY_ROW:") -> {
                        val rowIndex = kind.substringAfter(':').toIntOrNull() ?: -1
                        val row = libraryRows.getOrNull(rowIndex).orEmpty()
                        Box(
                            modifier = modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        ) {
                            HomeCardSlotRow(
                                row = row,
                                group = HomeCardGroup.LIBRARY,
                                editMode = homeCardEditMode,
                                contentCounts = homeContentCounts,
                                layoutState = homeCardLayoutState,
                                visibleOrder = visibleLibraryScenes,
                                dragPreview = libraryCardDragPreview,
                                onEnterEdit = { onHomeCardEditModeChange(true) },
                                onAdd = { onAddHomeCard(HomeCardGroup.LIBRARY) },
                                onNavigate = onNavigate,
                            )
                        }
                    }
                    kind == HOME_HOLDER_MOST_HEADER -> {
                        Box(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp), contentAlignment = Alignment.CenterStart) {
                            SectionTitle(stringResource(R.string.home_most_played_section))
                        }
                    }
                    kind.startsWith("$HOME_HOLDER_MOST_ROW:") -> {
                        val id = kind.substringAfter(':').toLongOrNull()
                        val mostSong = id?.let(mostPlayedById::get)
                        if (mostSong != null) {
                            val sourceIndex = mostPlayedIndex[mostSong.id] ?: 0
                            val isCurrentSong = latestCarouselCurrentSong?.let { current ->
                                current.path == mostSong.path &&
                                    current.cueOffsetMs == mostSong.cueOffsetMs &&
                                    current.cueTrackIndex == mostSong.cueTrackIndex
                            } == true
                            Box(modifier = modifier.fillMaxSize().padding(horizontal = 16.dp)) {
                                MostPlayedRow(
                                    song = mostSong,
                                    playCount = playCounts[mostSong.id] ?: 0,
                                    isCurrentSong = isCurrentSong,
                                    isPlaying = isCurrentSong && latestPlaying,
                                    onClick = { onSongClick(mostSong, sourceIndex) },
                                    onPlayPauseClick = {
                                        if (isCurrentSong) onCurrentPlayPause() else onSongClick(mostSong, sourceIndex)
                                    },
                                    contentHeight = mostPlayedContentHeight,
                                )
                            }
                        }
                    }
                    kind == HOME_HOLDER_TOOLS_HEADER -> {
                        Box(
                            modifier = modifier.fillMaxSize().padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            HomeSectionTitle(
                                text = stringResource(R.string.home_tools_section),
                                statistics = stringResource(R.string.home_tools_section_statistics, visibleToolScenes.size),
                                editing = homeCardEditMode,
                                onDone = { onHomeCardEditModeChange(false) },
                            )
                        }
                    }
                    kind.startsWith("$HOME_HOLDER_TOOLS_ROW:") -> {
                        val rowIndex = kind.substringAfter(':').toIntOrNull() ?: -1
                        val row = toolRows.getOrNull(rowIndex).orEmpty()
                        Box(
                            modifier = modifier
                                .fillMaxSize()
                                .padding(horizontal = 16.dp, vertical = 7.dp),
                        ) {
                            HomeCardSlotRow(
                                row = row,
                                group = HomeCardGroup.TOOLS,
                                editMode = homeCardEditMode,
                                contentCounts = homeContentCounts,
                                layoutState = homeCardLayoutState,
                                visibleOrder = visibleToolScenes,
                                dragPreview = toolCardDragPreview,
                                onEnterEdit = { onHomeCardEditModeChange(true) },
                                onAdd = { onAddHomeCard(HomeCardGroup.TOOLS) },
                                onNavigate = onNavigate,
                            )
                        }
                    }
                    kind == HOME_HOLDER_BOTTOM_SPACER -> Unit
                }
            },
        )
    }
    return remember(items, provider) { HomeVirtualListProviderSpec(items, provider) }
}

private fun homeSyntheticAudioFile(id: Long, kind: String, title: String): AudioFile = AudioFile(
    id = id,
    path = "",
    title = title,
    artist = "",
    album = "",
    albumArtPath = "",
    duration = 0L,
    sampleRate = 0,
    bitsPerSample = 0,
    format = "",
    fileSize = 0L,
    trackNumber = 0,
    year = 0,
    dateAdded = 0L,
    dateModified = 0L,
    genre = "",
    composer = "",
    discNumber = 0,
    channelCount = 0,
    bpm = 0,
    albumArtist = "",
    encodingFormat = "$HOME_VIRTUAL_LIST_ENCODING_PREFIX$kind",
    isFavorite = false,
    trackGain = 0f,
    trackPeak = 1f,
    albumGain = 0f,
    albumPeak = 1f,
    cueOffsetMs = 0L,
    cueEndMs = 0L,
    cueTrackIndex = 0,
)
