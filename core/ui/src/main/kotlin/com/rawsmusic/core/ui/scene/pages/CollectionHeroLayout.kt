package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.module.data.prefs.CollectionSortPreferences
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
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

internal val CollectionHeroCoverHeight = 330.dp
internal val CollectionHeroMetaHeight = 146.dp
internal val CollectionHeroTotalHeight = CollectionHeroCoverHeight + CollectionHeroMetaHeight

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

@Composable
internal fun CollectionHeroDetailPage(
    hero: CollectionHeroData,
    listScene: NavScene,
    detailScene: NavScene,
    songListState: ComposePowerListState,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    onOpenFolder: () -> Unit = {},
    onShuffle: (List<AudioFile>) -> Unit = {},
    onSearch: () -> Unit = {},
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

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The list and detail artwork share one square geometry, so the retained visual only
        // needs a uniform scale and never stretches or snaps at hand-off.
        val heroCoverHeight = maxWidth
        val heroTotalHeight = heroCoverHeight + CollectionHeroMetaHeight
        ComposePowerListFull(
            songs = sortedSongs,
            state = songListState,
            persistentHeaderHeight = heroTotalHeight,
            // The header is cover + metadata/actions as one moving list item. Keep the full
            // header extent authoritative until its final pixel crosses the top boundary.
            persistentHeaderVisibilityHeight = heroTotalHeight,
            persistentHeaderSceneItemVisibilityHeight = heroCoverHeight,
            persistentHeaderSceneItemId = hero.sharedElementId,
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
                    onSearch = onSearch
                )
            },
            modifier = Modifier
                .fillMaxSize()
                .clipToBounds(),
            sharedCoverSceneId = detailScene.name,
            sharedCoverElementIdProvider = { song, index ->
                "${detailScene.name}:song:${song.id}:${song.path}:$index"
            },
            onSongClick = { _, index -> onPlayQueue(sortedSongs, index) }
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
            powerListState = songListState,
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
    onSearch: () -> Unit
) {
    val spec = LocalSharedTransitionSpec.current
    val coverRegistry = LocalSharedCoverRegistry.current
    val onBg = MiuixTheme.colorScheme.onBackground
    val onBgVariant = MiuixTheme.colorScheme.onSurfaceVariantSummary

    val collectionTransition = spec.active && isCollectionSharedCoverTransition(
        fromSceneId = spec.fromSceneId,
        toSceneId = spec.toSceneId,
        listScene = listScene,
        detailScene = detailScene
    )

    // Hide the real item only after both layout records exist. This avoids the
    // one-frame hole between target measurement and shared-overlay composition.
    val sharedPairReady = collectionTransition && coverRegistry.isPreparedElement(
        transitionKey = spec.transitionKey,
        sceneId = detailScene.name,
        elementId = hero.sharedElementId
    )
    val coverAlpha = if (sharedPairReady) 0f else 1f

    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(coverHeight)
                .clip(RoundedCornerShape(bottomStart = 14.dp, bottomEnd = 14.dp))
        ) {
            CrossfadeAlbumArt(
                key = hero.coverKey,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        // The persistent hero is not a PowerList zoom holder. Keep its single
                        // artwork owner stable while rows morph around it; only the prepared
                        // collection shared-element overlay may hide it.
                        alpha = coverAlpha
                        clip = true
                    },
                contentScale = ContentScale.Crop,
                showPlaceholder = false,
                fadeMillis = 0,
                freezeBitmapUpdates = freezeArtworkUpdates || sharedPairReady
            )

            CollectionSharedCoverAnchor(
                scene = detailScene,
                elementId = hero.sharedElementId,
                coverKey = hero.coverKey,
                radiusDp = 14f,
                enabled = coverVisible,
                modifier = Modifier.fillMaxSize()
            )

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

    DisposableEffect(sceneId, elementId, shouldTrack, registrationOwner) {
        if (!shouldTrack) coverRegistry.unregister(sceneId, elementId, registrationOwner)
        onDispose {
            coverRegistry.unregister(sceneId, elementId, registrationOwner)
        }
    }

    Box(
        modifier = modifier.onGloballyPositioned { coordinates ->
            if (!shouldTrack || coverKey.isBlank() || elementId.isBlank()) return@onGloballyPositioned
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
                coverRegistry.unregister(sceneId, elementId, registrationOwner)
                return@onGloballyPositioned
            }
            coverRegistry.register(
                sceneId = sceneId,
                elementId = elementId,
                owner = registrationOwner,
                snapshot = SharedCoverSnapshot(
                    sceneId = sceneId,
                    elementId = elementId,
                    boundsInWindow = Rect(
                        left = position.x,
                        top = position.y,
                        right = position.x + size.width,
                        bottom = position.y + size.height
                    ),
                    coverKey = coverKey,
                    radiusDp = radiusDp
                )
            )
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
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = imageVector,
            contentDescription = contentDescription,
            tint = scheme.onBackground,
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
    Box(
        modifier = Modifier
            .size(38.dp)
            .clip(CircleShape)
            .background(scheme.surfaceContainerHigh.copy(alpha = 0.72f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            colorFilter = ColorFilter.tint(scheme.onBackground),
            modifier = Modifier.size(21.dp)
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
