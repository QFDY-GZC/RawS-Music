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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.COLLECTION_HEADER_RADIUS_DP
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape

// retained-view implementation `scene_header` retained artwork view is match-parent with scale=1; the detail provider gives the
// header a window-width square. The actual artwork side is therefore resolved from maxWidth below,
// never from a fixed dp constant.
internal val CollectionHeroMetaHeight = 146.dp

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
        // retained-view implementation collection/header artwork is the window-width square itself. Do not apply the
        // player-only 8dp + 0.975 inner ArtworkImageNode geometry to a detail/header scene.
        val heroCoverHeight = maxWidth
        val heroTotalHeight = heroCoverHeight + CollectionHeroMetaHeight
        ComposeVirtualListFull(
            songs = sortedSongs,
            state = songListState,
            selectedPositions = selectedPositions,
            contentBottomPadding = selectionListBottomPadding,
            persistentHeaderHeight = heroTotalHeight,
            // The header is cover + metadata/actions as one moving list item. Keep the full
            // header extent authoritative until its final pixel crosses the top boundary.
            persistentHeaderVisibilityHeight = heroTotalHeight,
            persistentHeaderSceneItemVisibilityHeight = heroCoverHeight,
            persistentHeaderSceneItemId = hero.sharedElementId,
            virtualListEdgeEnabled = true,
            persistentHeaderContent = { _, coverVisible ->
                CollectionHeroHeader(
                    hero = sortedHero,
                    coverHeight = heroCoverHeight,
                    listScene = listScene,
                    detailScene = detailScene,
                    coverVisible = coverVisible,
                    freezeArtworkUpdates = songListState.isTransitioning,
                    onBack = onBack,
                    onMore = { showMoreDialog = true },
                    onShuffle = { onShuffle(sortedSongs) },
                    onSearch = onSearch,
                    onBiography = onBiography,
                    onHeroArtworkLongPress = onHeroArtworkLongPress,
                    onHeroArtworkBoundsChanged = onHeroArtworkBoundsChanged,
                    hideHeroArtwork = hideHeroArtwork,
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
private fun CollectionHeroHeader(
    hero: CollectionHeroData,
    coverHeight: androidx.compose.ui.unit.Dp,
    listScene: NavScene,
    detailScene: NavScene,
    coverVisible: Boolean,
    freezeArtworkUpdates: Boolean,
    onBack: () -> Unit,
    onMore: () -> Unit,
    onShuffle: () -> Unit,
    onSearch: () -> Unit,
    onBiography: (() -> Unit)?,
    onHeroArtworkLongPress: (() -> Unit)?,
    onHeroArtworkBoundsChanged: ((Rect) -> Unit)?,
    hideHeroArtwork: Boolean,
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val staticForeground = usesReferenceStaticForeground()
    val onBg = if (staticForeground) Color.White else MiuixTheme.colorScheme.onBackground
    val onBgVariant = if (staticForeground) Color.White.copy(alpha = 0.72f) else MiuixTheme.colorScheme.onSurfaceVariantSummary

    // Exactly one artwork owner, matching retained-view implementation's migrated retained item view. Do not keep a second
    // header bitmap underneath the promoted physical holder and do not wait for an overlay-ready
    // frame: both create a separate raster/visibility clock at the Item<->Header boundary.
    val physicalSharedOwner = coverRegistry.isPhysicalPromotedElement(hero.sharedElementId)
    // The fullscreen artist-image layer is a same-scene shared-element owner. Keep exactly one
    // raster owner during the Hero <-> fullscreen motion; the Hero resumes only after the overlay
    // has reached the source endpoint and is dismissed.
    val coverAlpha = if (physicalSharedOwner || hideHeroArtwork) 0f else 1f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
        ) {
            // Reference collection/header recording: the artwork itself is edge-to-edge and begins
            // at window y=0. Only the lower edge joins the detail surface; the top system/navigation
            // chrome is overlaid on the pixels instead of reserving an artwork inset.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = coverAlpha }
                    .clip(RoundedCornerShape(ReferenceHeroArtworkGeometry.cornerRadius))
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
                CrossfadeAlbumArt(
                    key = hero.coverKey,
                    modifier = Modifier.fillMaxSize(),
                    // retained-view implementation keeps the same artwork scale type while the concrete retained artwork view
                    // changes from list/grid SceneParams to scene_header. Raw's collection/list
                    // provider is KeepAspect, so the terminal header must keep the same matrix too.
                    contentScale = ContentScale.Fit,
                    aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                    // Collection heroes are terminal artwork consumers too. If the representative
                    // track has no embedded/sidecar image, show the same default artwork as the list.
                    showPlaceholder = true,
                    fadeMillis = 0,
                    freezeBitmapUpdates = freezeArtworkUpdates || physicalSharedOwner
                )

                CollectionSharedCoverAnchor(
                    scene = detailScene,
                    elementId = hero.sharedElementId,
                    coverKey = hero.coverKey,
                    radiusDp = COLLECTION_HEADER_RADIUS_DP,
                    enabled = coverVisible,
                    modifier = Modifier.fillMaxSize()
                )
            }

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
                    .padding(start = 12.dp, top = 12.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = stringResource(R.string.common_back),
                    tint = onBg
                )
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(CollectionHeroMetaHeight)
                .padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = hero.title,
                    color = onBg,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Black,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Text(
                    text = hero.subtitle,
                    color = onBgVariant,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                Row(
                    modifier = Modifier.padding(top = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(9.dp)
                ) {
                    CollectionHeroResourceActionBubble(
                        iconRes = R.drawable.ic_library_more,
                        contentDescription = stringResource(R.string.common_more_operations),
                        onClick = onMore
                    )
                    CollectionHeroResourceActionBubble(
                        iconRes = R.drawable.ic_shuffle_custom,
                        contentDescription = stringResource(R.string.library_action_shuffle),
                        onClick = onShuffle
                    )
                    CollectionHeroActionBubble(
                        imageVector = MiuixIcons.Regular.Search,
                        contentDescription = stringResource(R.string.library_action_search),
                        onClick = onSearch
                    )
                    if (onBiography != null) {
                        CollectionHeroTextActionBubble(
                            text = stringResource(R.string.artist_biography_button),
                            contentDescription = stringResource(R.string.artist_biography_action),
                            onClick = onBiography,
                        )
                    }
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Music,
                    contentDescription = null,
                    tint = onBgVariant,
                    modifier = Modifier.size(16.dp)
                )

                Text(
                    text = hero.meta,
                    color = onBgVariant,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
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
    val scheme = MiuixTheme.colorScheme
    val staticForeground = usesReferenceStaticForeground()
    val bubbleBackground = if (staticForeground) Color.Black.copy(alpha = 0.26f) else scheme.surfaceContainerHigh.copy(alpha = 0.72f)
    val iconColor = if (staticForeground) Color.White else scheme.onBackground
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(bubbleBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = iconColor,
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun CollectionHeroResourceActionBubble(
    iconRes: Int,
    contentDescription: String,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    val staticForeground = usesReferenceStaticForeground()
    val bubbleBackground = if (staticForeground) Color.Black.copy(alpha = 0.26f) else scheme.surfaceContainerHigh.copy(alpha = 0.72f)
    val iconColor = if (staticForeground) Color.White else scheme.onBackground
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(bubbleBackground)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(iconColor),
            modifier = Modifier.size(21.dp)
        )
    }
}

@Composable
private fun CollectionHeroTextActionBubble(
    text: String,
    contentDescription: String,
    onClick: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val staticForeground = usesReferenceStaticForeground()
    val bubbleBackground = if (staticForeground) {
        Color.Black.copy(alpha = 0.26f)
    } else {
        scheme.surfaceContainerHigh.copy(alpha = 0.72f)
    }
    val contentColor = if (staticForeground) Color.White else scheme.onBackground
    Box(
        modifier = Modifier
            .height(38.dp)
            .clip(RoundedCornerShape(19.dp))
            .background(bubbleBackground)
            .clickable(onClick = onClick)
            .padding(horizontal = 13.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = contentColor,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
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
