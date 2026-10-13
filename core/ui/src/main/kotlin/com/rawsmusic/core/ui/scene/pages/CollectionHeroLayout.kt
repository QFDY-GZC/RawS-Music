package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.module.data.prefs.CollectionSortPreferences
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.RawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.COLLECTION_HEADER_RADIUS_DP
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.VIRTUAL_LIST_HEADER_CHILD_STRETCH_DP
import com.rawsmusic.core.ui.widget.virtuallist.VIRTUAL_LIST_HEADER_MAX_STRETCH_DP
import com.rawsmusic.core.ui.widget.virtuallist.virtualListHeaderChildTranslationY
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.shape.CircleShape
import kotlin.math.roundToInt
import androidx.compose.foundation.shape.RoundedCornerShape
import kotlinx.coroutines.isActive

// Collection header metrics. The artwork view fills the retained header bounds inside
// the header holder; status-bar height is added separately because addStatusBarHeightToHeaders=true.
private val CollectionHeaderSize = 236.dp
private val HumanCollectionHeaderSize = 276.dp

private enum class CollectionHeaderMotionDirection { FORWARD, BACK }

private data class CollectionHeaderMotion(
    val direction: CollectionHeaderMotionDirection,
    val listEndpoint: SharedCoverSnapshot,
    val headerEndpoint: SharedCoverSnapshot,
    val progressProvider: () -> Float,
) {
    fun headerPresence(): Float {
        val progress = progressProvider().coerceIn(0f, 1f)
        return when (direction) {
            CollectionHeaderMotionDirection.FORWARD -> progress
            CollectionHeaderMotionDirection.BACK -> 1f - progress
        }
    }
}

internal fun collectionHeaderBaseHeight(scene: NavScene): androidx.compose.ui.unit.Dp =
    when (scene) {
        NavScene.ARTIST_DETAIL,
        NavScene.ARTIST_ALL_SONGS,
        NavScene.COMPOSER_DETAIL -> HumanCollectionHeaderSize
        else -> CollectionHeaderSize
    }

internal data class CollectionHeroWindowGeometry(
    val headerHeight: Dp,
    val artworkLeft: Dp,
    val artworkTop: Dp,
    val artworkWidth: Dp,
    val artworkHeight: Dp,
    val wide: Boolean,
)

internal fun resolveCollectionHeroWindowGeometry(
    scene: NavScene,
    window: RawWindowLayoutProfile,
    statusBarTop: Dp,
): CollectionHeroWindowGeometry {
    val legacyHeight = collectionHeaderBaseHeight(scene) + statusBarTop
    if (!window.supportsSupportingPane) {
        return CollectionHeroWindowGeometry(
            headerHeight = legacyHeight,
            artworkLeft = 0.dp,
            artworkTop = 0.dp,
            artworkWidth = window.libraryContentMaxWidth,
            artworkHeight = legacyHeight,
            wide = false,
        )
    }

    val artworkSize = minOf(
        320.dp,
        ((window.libraryContentMaxWidth - 96.dp) * 0.42f).coerceAtLeast(260.dp),
    )
    val artworkTop = statusBarTop + 12.dp
    return CollectionHeroWindowGeometry(
        headerHeight = artworkTop + artworkSize + 20.dp,
        artworkLeft = 16.dp,
        artworkTop = artworkTop,
        artworkWidth = artworkSize,
        artworkHeight = artworkSize,
        wide = true,
    )
}

@Stable
internal data class CollectionHeroData(
    val stableKey: String,
    val sharedElementId: String,
    val coverKey: String,
    val title: String,
    val subtitle: String,
    val meta: String,
    val songs: List<AudioFile>
)

data class LibrarySongSelectionActions(
    val addToPlaylist: (List<AudioFile>) -> Unit = {},
    val addToQueue: (List<AudioFile>) -> Unit = {},
    val delete: (List<AudioFile>) -> Unit = {},
    val playNext: (List<AudioFile>) -> Unit = {},
    val transcode: (List<AudioFile>) -> Unit = {},
    val batchMatchLyrics: (List<AudioFile>) -> Unit = {},
    val autoMatch: (List<AudioFile>) -> Unit = {},
    val clearQueue: () -> Unit = {},
    val onSelectionModeChanged: (Boolean) -> Unit = {},
)

@Composable
internal fun CollectionHeroDetailPage(
    hero: CollectionHeroData,
    listScene: NavScene,
    detailScene: NavScene,
    songListState: ComposeVirtualListState,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onOpenFolder: () -> Unit = {},
    onShuffle: (List<AudioFile>) -> Unit = {},
    onSearch: () -> Unit = {},
    onBiography: (() -> Unit)? = null,
    onHeroArtworkLongPress: (() -> Unit)? = null,
    onHeroArtworkBoundsChanged: ((Rect) -> Unit)? = null,
    hideHeroArtwork: Boolean = false,
    modifier: Modifier = Modifier
) {
    val persistedSortOwner = detailScene.tag
    var sortOrder by rememberSaveable(persistedSortOwner, hero.stableKey) {
        mutableStateOf(
            CollectionSortPreferences.read(
                ownerTag = persistedSortOwner,
                stableKey = hero.stableKey,
                default = SortOrder.PLAYBACK_INFO,
            )
        )
    }
    var showSortLayout by rememberSaveable(hero.stableKey) { mutableStateOf(false) }
    var showMoreDialog by rememberSaveable(hero.stableKey) { mutableStateOf(false) }
    val chromeInfo = LocalLibraryChromeInfo.current
    val sortedSongs = remember(hero.songs, sortOrder) { hero.songs.sortedForCollection(sortOrder) }
    val sortedHero = remember(hero, sortedSongs) { hero.copy(songs = sortedSongs) }
    val alphabetIndexData = com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData(sortedSongs) { it.title }
    var selectionMode by remember(hero.stableKey) { mutableStateOf(false) }
    var selectedSongIds by remember(hero.stableKey) { mutableStateOf<Set<Long>>(emptySet()) }
    val selectedSongs = remember(sortedSongs, selectedSongIds) {
        if (selectedSongIds.isEmpty()) emptyList()
        else sortedSongs.filter { it.id in selectedSongIds }
    }
    val selectedPositions = remember(sortedSongs, selectedSongIds, selectionMode) {
        val actual = sortedSongs.mapIndexedNotNull { index, song ->
            if (song.id in selectedSongIds) index else null
        }.toSet()
        // Keep selection mode explicit even when the user temporarily deselects everything.  The
        // sentinel is outside every real row and therefore only keeps holder checkboxes visible.
        if (selectionMode && actual.isEmpty()) setOf(-1) else actual
    }
    val stableSystemBottom = rawStableNavigationBottomPadding()
    val normalBottomPadding = LocalBottomChromeInsets.current.contentBottom
    val selectionListBottomPadding by animateDpAsState(
        targetValue = if (selectionMode) 198.dp + stableSystemBottom else normalBottomPadding,
        animationSpec = tween(
            durationMillis = if (selectionMode) 500 else 200,
            easing = SelectionTransitionEasing,
        ),
        label = "collection-selection-bottom-reserve",
    )

    fun clearSelection() {
        selectionMode = false
        selectedSongIds = emptySet()
    }

    fun toggleSelectAll() {
        val allIds = sortedSongs.map { it.id }.toSet()
        selectedSongIds = if (allIds.isNotEmpty() && selectedSongIds.containsAll(allIds)) {
            emptySet()
        } else {
            allIds
        }
    }

    fun toggleSelectionRange() {
        val positions = sortedSongs.mapIndexedNotNull { index, song ->
            if (song.id in selectedSongIds) index else null
        }
        if (positions.size < 2) return
        val first = positions.minOrNull() ?: return
        val last = positions.maxOrNull() ?: return
        if (last - first <= 1) return
        val insideIds = (first + 1 until last).map { sortedSongs[it].id }.toSet()
        selectedSongIds = if (insideIds.all { it in selectedSongIds }) {
            selectedSongIds - insideIds
        } else {
            selectedSongIds + insideIds
        }
    }

    LaunchedEffect(selectionMode) {
        selectionActions.onSelectionModeChanged(selectionMode)
    }
    DisposableEffect(hero.stableKey) {
        onDispose { selectionActions.onSelectionModeChanged(false) }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val windowLayoutProfile = LocalRawWindowLayoutProfile.current
        val heroGeometry = resolveCollectionHeroWindowGeometry(
            scene = detailScene,
            window = windowLayoutProfile,
            statusBarTop = rawStableStatusBarTopPadding(),
        )
        val heroTotalHeight = heroGeometry.headerHeight
        ComposeVirtualListFull(
            songs = sortedSongs,
            state = songListState,
            selectedPositions = selectedPositions,
            contentBottomPadding = selectionListBottomPadding,
            persistentHeaderHeight = heroTotalHeight,
            // The header is cover + metadata/actions as one moving list item. Keep the full
            // header extent authoritative until its final pixel crosses the top boundary.
            persistentHeaderVisibilityHeight = heroTotalHeight,
            persistentHeaderSceneItemVisibilityHeight = heroGeometry.artworkHeight,
            persistentHeaderSceneItemLeft = heroGeometry.artworkLeft,
            persistentHeaderSceneItemTop = heroGeometry.artworkTop,
            persistentHeaderSceneItemWidth = heroGeometry.artworkWidth,
            persistentHeaderSceneItemId = hero.sharedElementId,
            virtualListEdgeEnabled = true,
            persistentHeaderContent = { _, coverVisible ->
                CollectionHeroHeader(
                    hero = sortedHero,
                    geometry = heroGeometry,
                    listScene = listScene,
                    detailScene = detailScene,
                    coverVisible = coverVisible,
                    freezeArtworkUpdates = songListState.isTransitioning,
                    onBack = onBack,
                    onMore = { showMoreDialog = true },
                    onShuffle = { onShuffle(sortedSongs) },
                    onPlay = {
                        if (sortedSongs.isNotEmpty()) onPlayQueue(sortedSongs, 0)
                    },
                    onSearch = onSearch,
                    onSelect = { selectionMode = true },
                    onBiography = onBiography,
                    onHeroArtworkLongPress = onHeroArtworkLongPress,
                    onHeroArtworkBoundsChanged = onHeroArtworkBoundsChanged,
                    hideHeroArtwork = hideHeroArtwork,
                    headerEdgeOvershootProvider = { songListState.virtualListHeaderEdgeOvershootPx },
                    listEdgeOvershootProvider = { songListState.virtualListEdgeOvershootPx },
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            sharedCoverSceneId = detailScene.name,
            sharedCoverElementIdProvider = { song, index ->
                "${detailScene.name}:song:${song.id}:${song.path}:$index"
            },
            onSongClick = { song, index ->
                if (selectionMode) {
                    selectedSongIds = if (song.id in selectedSongIds) {
                        selectedSongIds - song.id
                    } else {
                        selectedSongIds + song.id
                    }
                } else {
                    onPlayQueue(sortedSongs, index)
                }
            },
            onSongLongClick = { song, _ ->
                if (selectionMode) {
                    selectedSongIds = selectedSongIds + song.id
                } else {
                    selectionMode = true
                    selectedSongIds = setOf(song.id)
                }
            }
        )

        if (!com.rawsmusic.core.ui.scene.LocalReferenceLibraryFullScenePreflight.current &&
            !com.rawsmusic.core.ui.scene.LocalReferenceLibraryTopChromeOverlayOnly.current) {
            com.rawsmusic.core.ui.widget.index.RawAlphabetIndex(
                sceneId = detailScene.name,
                data = alphabetIndexData,
                scrollActiveProvider = { songListState.isListScrollInProgress },
                modifier = Modifier.align(Alignment.CenterEnd)
                    .padding(top = 92.dp, bottom = normalBottomPadding),
                onTopSelect = { songListState.requestScrollToIndex(0) },
                onSelect = { _, index -> songListState.requestScrollToIndex(index) },
            )
        }

        SongSelectionMenu(
            visible = selectionMode,
            selectedSongs = selectedSongs,
            selectedCount = selectedSongs.size,
            totalCount = sortedSongs.size,
            allSelected = sortedSongs.isNotEmpty() && selectedSongIds.size == sortedSongs.size,
            rangeEnabled = selectedSongIds.size >= 2,
            onToggleSelectAll = ::toggleSelectAll,
            onToggleRange = ::toggleSelectionRange,
            onAddToPlaylist = {
                if (selectedSongs.isNotEmpty()) selectionActions.addToPlaylist(selectedSongs)
                clearSelection()
            },
            onAddToQueue = {
                if (selectedSongs.isNotEmpty()) selectionActions.addToQueue(selectedSongs)
                clearSelection()
            },
            onDelete = {
                if (selectedSongs.isNotEmpty()) selectionActions.delete(selectedSongs)
                clearSelection()
            },
            onPlayNext = {
                if (selectedSongs.isNotEmpty()) selectionActions.playNext(selectedSongs)
                clearSelection()
            },
            onTranscode = {
                if (selectedSongs.isNotEmpty()) selectionActions.transcode(selectedSongs)
                clearSelection()
            },
            onBatchMatchLyrics = {
                if (selectedSongs.isNotEmpty()) selectionActions.batchMatchLyrics(selectedSongs)
                clearSelection()
            },
            onAutoMatch = {
                if (selectedSongs.isNotEmpty()) selectionActions.autoMatch(selectedSongs)
                clearSelection()
            },
            onDismiss = ::clearSelection,
        )

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
            virtualListState = songListState,
            onSortSelected = { selectedOrder ->
                sortOrder = selectedOrder
                CollectionSortPreferences.write(
                    ownerTag = persistedSortOwner,
                    stableKey = hero.stableKey,
                    value = selectedOrder,
                )
            },
            sortOptions = listOf(
                stringResource(R.string.queue_sort_original) to SortOrder.PLAYBACK_INFO,
                stringResource(R.string.sort_by_name) to SortOrder.TITLE_ASC,
                stringResource(R.string.sort_by_artist) to SortOrder.ARTIST_ASC,
        stringResource(R.string.queue_sort_album) to SortOrder.ALBUM_ASC,
        stringResource(R.string.sort_by_duration) to SortOrder.DURATION_ASC,
        stringResource(R.string.sort_by_added) to SortOrder.DATE_ADDED_ASC,
        stringResource(R.string.sort_by_modified) to SortOrder.DATE_MODIFIED_ASC
            ),
            onDismiss = { showSortLayout = false }
        )
    }
}

@Composable
internal fun CollectionHeroHeader(
    hero: CollectionHeroData,
    geometry: CollectionHeroWindowGeometry,
    listScene: NavScene,
    detailScene: NavScene,
    coverVisible: Boolean,
    freezeArtworkUpdates: Boolean,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onShuffle: () -> Unit,
    onPlay: (() -> Unit)? = null,
    onSearch: () -> Unit,
    onSelect: (() -> Unit)? = null,
    onBiography: (() -> Unit)?,
    onHeroArtworkLongPress: (() -> Unit)?,
    onHeroArtworkBoundsChanged: ((Rect) -> Unit)?,
    hideHeroArtwork: Boolean,
    showBackButton: Boolean = true,
    showMoreAction: Boolean = true,
    showShuffleAction: Boolean = true,
    showSearchAction: Boolean = true,
    publishSharedAnchor: Boolean = true,
    headerEdgeOvershootProvider: () -> Float = { 0f },
    listEdgeOvershootProvider: () -> Float = { 0f },
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val sharedSpec = LocalSharedTransitionSpec.current
    val sharedPair = sharedSpec.transitionKey
        .takeIf { sharedSpec.active && it.isNotBlank() }
        ?.let(coverRegistry::getPreparedPair)
    val statusBarTop = rawStableStatusBarTopPadding()
    val density = LocalDensity.current
    val hostView = LocalView.current
    val onBg = if (geometry.wide) MiuixTheme.colorScheme.onBackground else Color.White
    val onBgVariant = if (geometry.wide) MiuixTheme.colorScheme.onSurfaceVariantSummary else Color.White

    val headerMotion = sharedPair?.let { pair ->
        val firstIsList = pair.first.itemViewportBounds != null
        val secondIsList = pair.second.itemViewportBounds != null
        val direction = when {
            sharedSpec.toSceneId == detailScene.name &&
                sharedSpec.fromSceneId != detailScene.name -> CollectionHeaderMotionDirection.FORWARD
            sharedSpec.fromSceneId == detailScene.name &&
                sharedSpec.toSceneId != detailScene.name -> CollectionHeaderMotionDirection.BACK
            firstIsList && !secondIsList -> CollectionHeaderMotionDirection.FORWARD
            secondIsList && !firstIsList -> CollectionHeaderMotionDirection.BACK
            else -> null
        } ?: return@let null
        val listEndpoint = if (direction == CollectionHeaderMotionDirection.FORWARD) pair.first else pair.second
        val headerEndpoint = if (direction == CollectionHeaderMotionDirection.FORWARD) pair.second else pair.first
        if (headerEndpoint.elementId != hero.sharedElementId || listEndpoint.elementId != hero.sharedElementId) {
            null
        } else {
            CollectionHeaderMotion(
                direction = direction,
                listEndpoint = listEndpoint,
                headerEndpoint = headerEndpoint,
                progressProvider = {
                    sharedSpec.progressProvider?.invoke() ?: sharedSpec.progress
                },
            )
        }
    }
    val latestHeaderMotion by rememberUpdatedState(headerMotion)
    val headerMotionFrame = remember(
        sharedSpec.transitionKey,
        headerMotion?.direction,
        hero.sharedElementId,
    ) {
        mutableFloatStateOf(headerMotion?.headerPresence() ?: 1f)
    }
    LaunchedEffect(
        sharedSpec.active,
        sharedSpec.transitionKey,
        headerMotion?.direction,
        hero.sharedElementId,
    ) {
        if (!sharedSpec.active || latestHeaderMotion == null) {
            headerMotionFrame.floatValue = 1f
            return@LaunchedEffect
        }
        // progressProvider is a render-time clock, not snapshot State. Sample it once per vsync
        // into this narrow State; graphicsLayer reads below then update without recomposing the
        // complete collection-detail page.
        while (isActive) {
            val motion = latestHeaderMotion ?: break
            headerMotionFrame.floatValue = motion.headerPresence()
            withFrameNanos { }
        }
    }

    // Exactly one artwork owner, matching retained-view implementation's migrated retained item view. Do not keep a second
    // header bitmap underneath the promoted physical holder and do not wait for an overlay-ready
    // frame: both create a separate raster/visibility clock at the Item<->Header boundary.
    val physicalSharedOwner = coverRegistry.isPhysicalPromotedElement(hero.sharedElementId)
    // The fullscreen artist-image layer is a same-scene shared-element owner. Keep exactly one
    // raster owner during the Hero <-> fullscreen motion; the Hero resumes only after the overlay
    // has reached the source endpoint and is dismissed.
    val coverAlpha = if (physicalSharedOwner || hideHeroArtwork) 0f else 1f

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxWidth()
            .height(geometry.headerHeight)
    ) {
        val headerWidth = maxWidth
        val headerHeightPx = with(density) { geometry.headerHeight.toPx() }
        val headerMaxStretchPx = with(density) { VIRTUAL_LIST_HEADER_MAX_STRETCH_DP.dp.toPx() }
        val headerChildStretchPx = with(density) { VIRTUAL_LIST_HEADER_CHILD_STRETCH_DP.dp.toPx() }
        val titleLeft = if (geometry.wide) {
            geometry.artworkLeft + geometry.artworkWidth + 28.dp
        } else {
            12.dp
        }
        val titleTop = if (geometry.wide) statusBarTop + 68.dp else geometry.headerHeight - 124.333333.dp
        val titleWidth = if (geometry.wide) {
            (headerWidth - titleLeft - 72.dp).coerceAtLeast(1.dp)
        } else {
            (headerWidth - 30.dp).coerceAtLeast(1.dp)
        }
        val titleHeight = 26.333333.dp
        val subtitleLeft = titleLeft
        val subtitleTop = if (geometry.wide) titleTop + 38.dp else geometry.headerHeight - 97.dp
        val subtitleWidth = if (geometry.wide) titleWidth else (headerWidth - 24.dp).coerceAtLeast(1.dp)
        val subtitleHeight = 23.666667.dp
        val metaLeft = titleLeft
        val metaTop = if (geometry.wide) subtitleTop + 31.dp else geometry.headerHeight - 72.333333.dp
        val metaHeight = 23.333333.dp
        val actionLeft = titleLeft
        val actionTop = if (geometry.wide) metaTop + 46.dp else geometry.headerHeight - 48.dp
        val menuLeft = headerWidth - 56.dp
        val menuTop = if (geometry.wide) statusBarTop + 12.dp else geometry.headerHeight - 52.dp

        fun headerEdgeChildMotion(childTop: Dp): Modifier {
            val childTopPx = with(density) { childTop.toPx() }
            return Modifier.graphicsLayer {
                translationY = virtualListHeaderChildTranslationY(
                    edgePx = headerEdgeOvershootProvider(),
                    maxStretchPx = headerMaxStretchPx,
                    childStretchPx = headerChildStretchPx,
                    headerHeightPx = headerHeightPx,
                    childTopPx = childTopPx,
                )
            }
        }

        fun sharedChildMotion(
            sourceBounds: Rect?,
            targetLeft: Dp,
            targetTop: Dp,
            sourceSceneScale: Float,
            fallbackStartOffsetY: Dp = 0.dp,
        ): Modifier {
            val motion = headerMotion ?: return Modifier
            val targetLeftPx = with(density) { targetLeft.toPx() }
            val targetTopPx = with(density) { targetTop.toPx() }
            val fallbackOffsetYPx = with(density) { fallbackStartOffsetY.toPx() }
            val artworkLeftPx = with(density) { geometry.artworkLeft.toPx() }
            val artworkTopPx = with(density) { geometry.artworkTop.toPx() }
            val headerWindowLeft = motion.headerEndpoint.boundsInWindow.left - artworkLeftPx
            val headerWindowTop = motion.headerEndpoint.boundsInWindow.top - artworkTopPx
            val targetWindowLeft = headerWindowLeft + targetLeftPx
            val targetWindowTop = headerWindowTop + targetTopPx
            val pivotX = sharedSpec.detailPivotX.takeIf { it.isFinite() }
                ?: (hostView.width * 0.5f)
            val pivotY = sharedSpec.detailPivotY.takeIf { it.isFinite() }
                ?: (hostView.height * 0.5f)
            return Modifier.graphicsLayer {
                // Snapshot read in the layer block: each vsync update invalidates only this layer.
                val headerPresence = headerMotionFrame.floatValue.coerceIn(0f, 1f)
                val parentScale = 0.5f + 0.5f * headerPresence
                if (sourceBounds != null) {
                    val desiredWindowLeft =
                        sourceBounds.left + (targetWindowLeft - sourceBounds.left) * headerPresence
                    val desiredWindowTop =
                        sourceBounds.top + (targetWindowTop - sourceBounds.top) * headerPresence
                    // GenericPivot already transforms the detail/header provider 0.5 -> 1 around
                    // the viewport centre. Invert that parent affine so this child follows the
                    // absolute source child rect -> scene_header child rect instead of receiving
                    // the page transform twice.
                    val parentTx = pivotX * (1f - parentScale)
                    val parentTy = pivotY * (1f - parentScale)
                    val requiredPreParentLeft = (desiredWindowLeft - parentTx) / parentScale
                    val requiredPreParentTop = (desiredWindowTop - parentTy) / parentScale
                    translationX = requiredPreParentLeft - targetWindowLeft
                    translationY = requiredPreParentTop - targetWindowTop
                } else {
                    translationX = 0f
                    translationY = fallbackOffsetYPx * (1f - headerPresence)
                }
                // Resting title, subtitle and metadata scale is .9; expanded header scale is 1.
                // normal buttons/menu=.75, header=1. Parent GenericPivot owns alpha.
                val sceneScale = sourceSceneScale + (1f - sourceSceneScale) * headerPresence
                // This child lives inside Raw's independently scaled NEXT/CURRENT provider.
                // Divide out that parent scale so the *screen-space* child scale is exactly the
                // LayoutRes value rather than 0.5*.9 -> 1 during ItemToHeader.
                val localCompensatedScale = sceneScale / parentScale.coerceAtLeast(0.001f)
                scaleX = localCompensatedScale
                scaleY = localCompensatedScale
                transformOrigin = TransformOrigin(0f, 0f)
            }
        }

        // Compact/medium windows retain full-header artwork geometry. Expanded windows
        // keep the same single holder/provider but give the artwork a bounded square sub-rect; the
        // physical VirtualList LayoutRes publishes this exact sub-rect as the shared endpoint.
        Box(
            modifier = Modifier
                .offset(x = geometry.artworkLeft, y = geometry.artworkTop)
                .size(width = geometry.artworkWidth, height = geometry.artworkHeight)
                .onGloballyPositioned { coordinates ->
                    val position = coordinates.positionInWindow()
                    val size = coordinates.size
                    onHeroArtworkBoundsChanged?.invoke(
                        Rect(
                            left = position.x,
                            top = position.y,
                            right = position.x + size.width,
                            bottom = position.y + size.height,
                        )
                    )
                }
                .then(
                    if (onHeroArtworkLongPress != null) {
                        Modifier.combinedClickable(
                            onClick = {},
                            onLongClick = onHeroArtworkLongPress,
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            // Extend the artwork draw rect upward by the signed edge amount E and recenter
            // the CENTER_CROP shader by -E/2 instead of translating the entire image layer.
            // With the header still top-anchored this makes the visible artwork height H+E, so the
            // top never exposes empty pixels while the lower edge follows the rubber-band pull.
            // Measure only the artwork raster taller; the shared/header geometry remains H.
            Layout(
                modifier = Modifier.fillMaxSize(),
                content = {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = coverAlpha }
                            .clip(RoundedCornerShape(ReferenceHeroArtworkGeometry.cornerRadius))
                    ) {
                        CrossfadeAlbumArt(
                            key = hero.coverKey,
                            modifier = Modifier.fillMaxSize(),
                            // CENTER_CROP on the taller draw rect preserves
                            // top-=E plus shader -E/2 recentering without geometrically stretching
                            // the bitmap pixels.
                            contentScale = ContentScale.Crop,
                            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                            showPlaceholder = true,
                            fadeMillis = 0,
                            freezeBitmapUpdates = freezeArtworkUpdates || physicalSharedOwner
                        )
                    }
                },
            ) { measurables, constraints ->
                val baseWidth = constraints.maxWidth.coerceAtLeast(constraints.minWidth)
                val baseHeight = constraints.maxHeight.coerceAtLeast(constraints.minHeight)
                val overscanPx = listEdgeOvershootProvider()
                    .coerceAtLeast(0f)
                    .roundToInt()
                val visualHeight = (baseHeight + overscanPx).coerceAtLeast(baseHeight)
                val artwork = measurables.single().measure(
                    Constraints.fixed(baseWidth, visualHeight)
                )
                layout(baseWidth, baseHeight) {
                    artwork.place(0, 0)
                }
            }

            if (publishSharedAnchor) {
                CollectionSharedCoverAnchor(
                    scene = detailScene,
                    elementId = hero.sharedElementId,
                    coverKey = hero.coverKey,
                    radiusDp = COLLECTION_HEADER_RADIUS_DP,
                    enabled = coverVisible,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }

        if (showBackButton) {
            IconButton(
                onClick = {
                    if (coverVisible) {
                        coverRegistry.freeze(
                            sceneId = detailScene.name,
                            elementId = hero.sharedElementId
                        )
                    }
                    onBack()
                },
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(start = 12.dp, top = statusBarTop + 8.dp)
                    .then(headerEdgeChildMotion(statusBarTop + 8.dp))
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.common_back),
                    tint = Color.White
                )
            }
        }

        // Header bounds expressed as dp and anchored from
        // the holder bottom so only the status-bar extension changes when insets differ.
        Text(
            text = hero.title,
            color = onBg,
            fontSize = 16.5.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(x = titleLeft, y = titleTop)
                .width(titleWidth)
                .height(titleHeight)
                .then(headerEdgeChildMotion(titleTop))
                .then(
                    sharedChildMotion(
                        sourceBounds = headerMotion?.listEndpoint?.titleBoundsInWindow,
                        targetLeft = titleLeft,
                        targetTop = titleTop,
                        sourceSceneScale = 0.9f,
                        fallbackStartOffsetY = 12.dp,
                    )
                ),
        )

        Text(
            text = hero.subtitle,
            color = onBgVariant,
            fontSize = 14.5.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .offset(x = subtitleLeft, y = subtitleTop)
                .width(subtitleWidth)
                .height(subtitleHeight)
                .then(headerEdgeChildMotion(subtitleTop))
                .then(
                    sharedChildMotion(
                        sourceBounds = headerMotion?.listEndpoint?.subtitleBoundsInWindow,
                        targetLeft = subtitleLeft,
                        targetTop = subtitleTop,
                        sourceSceneScale = 0.9f,
                        fallbackStartOffsetY = 12.dp,
                    )
                ),
        )

        Row(
            modifier = Modifier
                .offset(x = metaLeft, y = metaTop)
                .height(metaHeight)
                .then(headerEdgeChildMotion(metaTop))
                .then(
                    sharedChildMotion(
                        sourceBounds = headerMotion?.listEndpoint?.metaBoundsInWindow,
                        targetLeft = metaLeft,
                        targetTop = metaTop,
                        sourceSceneScale = 0.9f,
                        fallbackStartOffsetY = 12.dp,
                    )
                ),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = MiuixIcons.Regular.Music,
                contentDescription = null,
                tint = onBgVariant,
                modifier = Modifier.size(12.dp),
            )
            Text(
                text = hero.meta,
                color = onBgVariant,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Row(
            modifier = Modifier
                .offset(x = actionLeft, y = actionTop)
                .then(headerEdgeChildMotion(actionTop))
                .then(
                    sharedChildMotion(
                        sourceBounds = null,
                        targetLeft = actionLeft,
                        targetTop = actionTop,
                        sourceSceneScale = 0.75f,
                        fallbackStartOffsetY = 12.dp,
                    )
                ),
            horizontalArrangement = Arrangement.spacedBy(3.5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (showShuffleAction) {
                CollectionHeroResourceActionBubble(
                    iconRes = R.drawable.ic_shuffle_custom,
                    contentDescription = stringResource(R.string.library_action_shuffle),
                    onClick = onShuffle,
                )
            }
            if (onPlay != null) {
                CollectionHeroResourceActionBubble(
                    iconRes = R.drawable.ic_play,
                    contentDescription = stringResource(R.string.library_action_play),
                    onClick = onPlay,
                )
            }
            if (showSearchAction) {
                CollectionHeroActionBubble(
                    imageVector = MiuixIcons.Regular.Search,
                    contentDescription = stringResource(R.string.library_action_search),
                    onClick = onSearch,
                )
            }
            if (onSelect != null) {
                CollectionHeroTextActionBubble(
                    text = stringResource(R.string.library_action_select),
                    contentDescription = stringResource(R.string.library_action_select),
                    onClick = onSelect,
                )
            }
            if (onBiography != null) {
                CollectionHeroTextActionBubble(
                    text = stringResource(R.string.artist_biography_button),
                    contentDescription = stringResource(R.string.artist_biography_action),
                    onClick = onBiography,
                )
            }
        }

        if (showMoreAction) {
            CollectionHeroMenuActionBubble(
                iconRes = R.drawable.ic_library_more,
                contentDescription = stringResource(R.string.common_more_operations),
                onClick = onMore,
                modifier = Modifier
                    .offset(x = menuLeft, y = menuTop)
                    .then(headerEdgeChildMotion(menuTop))
                    .then(
                        sharedChildMotion(
                            sourceBounds = null,
                            targetLeft = menuLeft,
                            targetTop = menuTop,
                            sourceSceneScale = 0.75f,
                            fallbackStartOffsetY = 8.dp,
                        )
                    ),
            )
        }
    }
}

@Composable
internal fun CollectionSharedCoverAnchor(
    scene: NavScene,
    elementId: String,
    coverKey: String,
    radiusDp: Float,
    enabled: Boolean = true,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val spec = LocalSharedTransitionSpec.current
    val hostView = LocalView.current
    val sceneId = scene.name
    val shouldTrack = enabled && spec.shouldTrackScene(sceneId)
    val registrationOwner = remember(sceneId, elementId) { Any() }
    var lastTrackedBounds by remember(sceneId, elementId) { mutableStateOf<Rect?>(null) }

    DisposableEffect(sceneId, elementId, shouldTrack, registrationOwner) {
        if (!shouldTrack) coverRegistry.unregister(sceneId, elementId, registrationOwner)
        onDispose {
            coverRegistry.unregister(sceneId, elementId, registrationOwner)
        }
    }

    LaunchedEffect(
        sceneId,
        elementId,
        coverKey,
        radiusDp,
        shouldTrack,
        lastTrackedBounds,
        registrationOwner,
    ) {
        val bounds = lastTrackedBounds
        if (!shouldTrack || elementId.isBlank() || bounds == null) return@LaunchedEffect
        // Artwork identity may change without any geometry change (artist temporary image
        // replacement). Re-publish the live endpoint on key changes instead of waiting for another
        // onGloballyPositioned callback which may never happen for a stable header LayoutRes.
        coverRegistry.register(
            sceneId = sceneId,
            elementId = elementId,
            owner = registrationOwner,
            snapshot = SharedCoverSnapshot(
                sceneId = sceneId,
                elementId = elementId,
                boundsInWindow = bounds,
                coverKey = coverKey,
                radiusDp = radiusDp,
            ),
        )
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            if (!shouldTrack || elementId.isBlank()) return@onGloballyPositioned
            val position = coordinates.positionInWindow()
            val size = coordinates.size
            val right = position.x + size.width
            val bottom = position.y + size.height
            if (!isCollectionSharedCoverVisibleInWindow(
                    left = position.x,
                    top = position.y,
                    right = right,
                    bottom = bottom,
                    hostWidth = hostView.width.toFloat(),
                    hostHeight = hostView.height.toFloat(),
                )
            ) {
                lastTrackedBounds = null
                coverRegistry.unregister(sceneId, elementId, registrationOwner)
                return@onGloballyPositioned
            }
            val nextBounds = Rect(
                left = position.x,
                top = position.y,
                right = position.x + size.width,
                bottom = position.y + size.height,
            )
            if (lastTrackedBounds != nextBounds) lastTrackedBounds = nextBounds
        }
    )
}

internal fun isCollectionSharedCoverTransition(
    fromSceneId: String,
    toSceneId: String,
    listScene: NavScene,
    detailScene: NavScene
): Boolean {
    return (fromSceneId == listScene.name && toSceneId == detailScene.name) ||
        (fromSceneId == detailScene.name && toSceneId == listScene.name)
}

@Composable
private fun CollectionHeroActionBubble(
    imageVector: ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 36.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 200f / 255f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun CollectionHeroResourceActionBubble(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(width = 54.dp, height = 36.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 200f / 255f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
private fun CollectionHeroMenuActionBubble(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 200f / 255f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(24.dp),
        )
    }
}

@Composable
private fun CollectionHeroTextActionBubble(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .height(36.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(Color.Black.copy(alpha = 200f / 255f))
            .clickable(onClick = onClick)
            .padding(horizontal = 15.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Normal,
            maxLines = 1,
        )
    }
}

private fun List<AudioFile>.sortedForCollection(order: SortOrder): List<AudioFile> {
    val comparator = when (order) {
        SortOrder.TITLE_ASC, SortOrder.TITLE_DESC -> compareBy<AudioFile> { it.displayName.lowercase() }
        SortOrder.ARTIST_ASC, SortOrder.ARTIST_DESC -> compareBy<AudioFile> { it.artist.lowercase() }
            .thenBy { it.displayName.lowercase() }
        SortOrder.ALBUM_ASC, SortOrder.ALBUM_DESC -> compareBy<AudioFile> { it.album.lowercase() }
            .thenBy { it.discNumber }
            .thenBy { it.trackNumber }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy<AudioFile> { it.duration }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> compareBy<AudioFile> { it.dateAdded }
        SortOrder.DATE_MODIFIED_ASC, SortOrder.DATE_MODIFIED_DESC -> compareBy<AudioFile> { it.dateModified }
        else -> return this
    }
    val descending = order == SortOrder.TITLE_DESC ||
        order == SortOrder.ARTIST_DESC ||
        order == SortOrder.ALBUM_DESC ||
        order == SortOrder.DURATION_DESC ||
        order == SortOrder.DATE_ADDED_DESC ||
        order == SortOrder.DATE_MODIFIED_DESC
    return sortedWith(if (descending) comparator.reversed() else comparator)
}
