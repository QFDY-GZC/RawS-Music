package com.rawsmusic.core.ui.scene.pages

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalSharedTransitionRegistry
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.sharedTextAnimated
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListFull
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Folder
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme

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
    onSortClick: () -> Unit,
    onSortSelected: (SortOrder) -> Unit = {},
    onSelectionAddToPlaylist: (List<AudioFile>) -> Unit = {},
    onSelectionAddToQueue: (List<AudioFile>) -> Unit = {},
    onSelectionDelete: (List<AudioFile>) -> Unit = {},
    onSelectionPlayNext: (List<AudioFile>) -> Unit = {},
    onSelectionModeChanged: (Boolean) -> Unit = {},
    powerListState: ComposePowerListState = rememberComposePowerListState(),
    onPlayingCoverBoundsChanged: (android.graphics.RectF?) -> Unit = {},
    onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit = {},
    onRevealCoverTargetResolved: (CoverTransitionTarget?) -> Unit = {},
    onMiniPlayerCoverBoundsChanged: (android.graphics.RectF?) -> Unit = {}
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearchActive by remember { mutableStateOf(false) }
    var showSortSheet by remember { mutableStateOf(false) }
    var selectionMode by remember { mutableStateOf(false) }
    var selectedSongIds by remember { mutableStateOf<Set<Long>>(emptySet()) }
    var showSelectionSheet by remember { mutableStateOf(false) }

    val density = LocalDensity.current
    val backgroundColor = MiuixTheme.colorScheme.background
    val isLightGlass = backgroundColor.luminance() > 0.5f

    val visibleSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter {
            it.displayName.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true) ||
                it.path.substringAfterLast('/').contains(searchQuery, ignoreCase = true)
        }
    }

    val selectedSongs = remember(visibleSongs, selectedSongIds) {
        visibleSongs.filter { it.id in selectedSongIds }
    }

    val selectedPositions = remember(visibleSongs, selectedSongIds) {
        visibleSongs.mapIndexedNotNull { index, song ->
            if (song.id in selectedSongIds) index else null
        }.toSet()
    }

    val alphabetIndexData = rememberAdaptiveAlphabetIndexData(visibleSongs) {
        it.displayName
    }

    val statusBarTop = WindowInsets.statusBars
        .asPaddingValues()
        .calculateTopPadding()

    val toolbarContentHeight = if (isSearchActive && !selectionMode) 104.dp else 56.dp
    val toolbarTotalHeight = statusBarTop + toolbarContentHeight

    fun clearSelection() {
        selectionMode = false
        selectedSongIds = emptySet()
        showSelectionSheet = false
    }

    BackHandler(enabled = selectionMode) {
        clearSelection()
    }

    LaunchedEffect(selectionMode) {
        onSelectionModeChanged(selectionMode)
    }

    val topOverlayProgress by remember {
        derivedStateOf {
            val triggerPx = with(density) { 80.dp.toPx() }
            (powerListState.viewportScrollYPx / triggerPx).coerceIn(0f, 1f)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
    ) {
        ComposePowerListFull(
            songs = visibleSongs,
            currentPlayingIndex = currentPlayingIndex,
            revealIndexRequest = playerReturnRevealIndex,
            hidePlayingCover = hidePlayingCover,
            state = powerListState,
            selectedPositions = selectedPositions,
            contentTopPadding = toolbarTotalHeight + 8.dp,
            onPlayingCoverBoundsChanged = onPlayingCoverBoundsChanged,
            onPlayingCoverTargetChanged = onPlayingCoverTargetChanged,
            onRevealCoverTargetResolved = onRevealCoverTargetResolved,
            onSongClick = { song, index ->
                if (selectionMode) {
                    val next = if (song.id in selectedSongIds) {
                        selectedSongIds - song.id
                    } else {
                        selectedSongIds + song.id
                    }
                    selectedSongIds = next
                    selectionMode = next.isNotEmpty()
                    showSelectionSheet = next.isNotEmpty()
                } else {
                    onSongClick(song, index)
                }
            },
            onSongLongClick = { song, _ ->
                selectionMode = true
                selectedSongIds = setOf(song.id)
                showSelectionSheet = true
            },
            modifier = Modifier.fillMaxSize()
        )

        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .zIndex(40f)
        ) {
            TopGradientGlassTail(
                isLight = isLightGlass,
                overlayProgress = topOverlayProgress,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(toolbarTotalHeight + 34.dp)
            )

            SongsTopMenuBar(
                title = "歌曲",
                sceneId = NavScene.SONGS.name,
                isSearchActive = isSearchActive && !selectionMode,
                searchQuery = searchQuery,
                onSearchQueryChange = { searchQuery = it },
                onToggleSearch = { isSearchActive = !isSearchActive },
                onCancelSearch = {
                    searchQuery = ""
                    isSearchActive = false
                },
                selectionMode = selectionMode,
                selectedCount = selectedSongIds.size,
                onCancelSelection = {
                    clearSelection()
                },
                onSelectAll = {
                    selectedSongIds = visibleSongs.map { it.id }.toSet()
                    selectionMode = selectedSongIds.isNotEmpty()
                    showSelectionSheet = selectedSongIds.isNotEmpty()
                },
                onBack = onBack,
                onOpenFolderPicker = onOpenFolderPicker,
                onSortClick = {
                    onSortClick()
                    showSortSheet = true
                },
                isLight = isLightGlass,
                overlayProgress = topOverlayProgress,
                modifier = Modifier.fillMaxWidth()
            )
        }

        RawAlphabetIndex(
            data = alphabetIndexData,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(
                    top = 92.dp,
                    bottom = 118.dp,
                    end = 0.dp
                )
                .zIndex(30f),
            onSelect = { _, index ->
                powerListState.requestScrollToIndex(index)
            }
        )

        SongsSortLayoutSheet(
            visible = showSortSheet,
            currentSortOrder = currentSortOrder,
            powerListState = powerListState,
            onSortSelected = { order ->
                onSortSelected(order)
                showSortSheet = false
            },
            onDismiss = { showSortSheet = false }
        )

        SongSelectionActionSheet(
            visible = showSelectionSheet && selectionMode && selectedSongs.isNotEmpty(),
            selectedCount = selectedSongs.size,
            onAddToPlaylist = {
                onSelectionAddToPlaylist(selectedSongs)
                clearSelection()
            },
            onAddToQueue = {
                onSelectionAddToQueue(selectedSongs)
                clearSelection()
            },
            onDelete = {
                onSelectionDelete(selectedSongs)
                clearSelection()
            },
            onPlayNext = {
                onSelectionPlayNext(selectedSongs)
                clearSelection()
            },
            onDismiss = { clearSelection() }
        )
    }
}

// ─────────────── 顶部菜单 ───────────────

@Composable
private fun SongsTopMenuBar(
    title: String,
    sceneId: String,
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
    onOpenFolderPicker: () -> Unit,
    onSortClick: () -> Unit,
    isLight: Boolean,
    overlayProgress: Float,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(
        bottomStart = 22.dp,
        bottomEnd = 22.dp
    )

    val scheme = MiuixTheme.colorScheme
    val surfaceColor = blendColor(
        start = scheme.background,
        end = scheme.primary,
        fraction = if (isLight) 0.035f + 0.035f * overlayProgress else 0.12f + 0.06f * overlayProgress
    )
    val searchSurfaceColor = blendColor(
        start = scheme.surfaceContainer,
        end = scheme.primary,
        fraction = if (isLight) 0.025f else 0.08f
    )
    val shadowAlpha = 0.06f + 0.12f * overlayProgress

    Column(
        modifier = modifier
            .shadow(
                elevation = (2 + 4 * overlayProgress).dp,
                shape = shape,
                ambientColor = scheme.onBackground.copy(alpha = shadowAlpha),
                spotColor = scheme.onBackground.copy(alpha = shadowAlpha)
            )
            .clip(shape)
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        surfaceColor.copy(alpha = 0.98f),
                        surfaceColor.copy(alpha = 0.92f),
                        surfaceColor.copy(alpha = 0.82f)
                    )
                )
            )
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .padding(horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(
                onClick = {
                    if (selectionMode) onCancelSelection() else onBack()
                },
                modifier = Modifier.size(48.dp)
            ) {
                Icon(
                    imageVector = MiuixIcons.Regular.Back,
                    contentDescription = if (selectionMode) "取消选择" else "返回",
                    tint = scheme.onSurface
                )
            }

            Text(
                text = if (selectionMode) "已选择 ${selectedCount} 首" else title,
                color = scheme.onBackground,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .weight(1f)
                    .sharedTextAnimated(
                        registry = LocalSharedTransitionRegistry.current,
                        sceneId = sceneId,
                        elementId = "songs_title",
                        text = if (selectionMode) "已选择 ${selectedCount} 首" else title,
                        color = scheme.onBackground,
                        fontSizeSp = 18f,
                        fontWeight = android.graphics.Typeface.BOLD
                    ),
                textAlign = TextAlign.Center
            )

            if (selectionMode) {
                Text(
                    text = "全选",
                    color = scheme.primary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .height(48.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelectAll() }
                        .padding(horizontal = 20.dp),
                    textAlign = TextAlign.Center
                )
            } else {
                IconButton(
                    onClick = onOpenFolderPicker,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Folder,
                        contentDescription = "文件夹",
                        tint = scheme.onSurface
                    )
                }

                IconButton(
                    onClick = onSortClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Sort,
                        contentDescription = "排序",
                        tint = scheme.onSurface
                    )
                }

                IconButton(
                    onClick = onToggleSearch,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        imageVector = MiuixIcons.Basic.Search,
                        contentDescription = "搜索",
                        tint = scheme.onSurface
                    )
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
                    .padding(horizontal = 14.dp),
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
                            color = scheme.onBackground,
                            fontSize = 14.sp
                        ),
                        singleLine = true,
                        cursorBrush = SolidColor(scheme.primary)
                    )

                    Text(
                        text = "取消",
                        color = scheme.primary,
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
                        text = "搜索歌曲",
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun TopGradientGlassTail(
    isLight: Boolean,
    overlayProgress: Float,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    val base = blendColor(
        start = scheme.background,
        end = scheme.primary,
        fraction = if (isLight) 0.025f + 0.02f * overlayProgress else 0.10f + 0.04f * overlayProgress
    )

    Box(
        modifier = modifier.background(
            Brush.verticalGradient(
                colors = listOf(
                    base.copy(alpha = 0.90f + 0.06f * overlayProgress),
                    base.copy(alpha = 0.58f + 0.12f * overlayProgress),
                    base.copy(alpha = 0.20f + 0.08f * overlayProgress),
                    Color.Transparent
                )
            )
        )
    )
}

@Composable
private fun SongsSortLayoutSheet(
    visible: Boolean,
    currentSortOrder: SortOrder,
    powerListState: ComposePowerListState,
    onSortSelected: (SortOrder) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val scope = rememberCoroutineScope()
    val interaction = remember { MutableInteractionSource() }
    val scrollState = rememberScrollState()
    val scheme = MiuixTheme.colorScheme
    val sheetColor = blendColor(scheme.background, scheme.primary, 0.035f)
    val secondaryColor = scheme.onSurfaceVariantSummary
    val selectedColor = scheme.primary

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(scheme.onBackground.copy(alpha = 0.20f))
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onDismiss
                ),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .fillMaxHeight(0.60f)
                    .clip(RoundedCornerShape(28.dp))
                    .background(sheetColor)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = {}
                    )
                    .padding(horizontal = 18.dp, vertical = 14.dp)
            ) {
                SheetHandle(
                    color = secondaryColor,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(14.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(scrollState)
                ) {
                    SheetTitle("排序")
                    Spacer(Modifier.height(8.dp))

                    SortSheetRow("根据标题", currentSortOrder.baseSortOrder() == SortOrder.TITLE_ASC, selectedColor) {
                        onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.TITLE_ASC))
                    }
                    SortSheetRow("根据文件名", currentSortOrder.baseSortOrder() == SortOrder.FILE_NAME_ASC, selectedColor) {
                        onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.FILE_NAME_ASC))
                    }
                    SortSheetRow("根据路径", currentSortOrder.baseSortOrder() == SortOrder.PATH_ASC, selectedColor) {
                        onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.PATH_ASC))
                    }
                    SortSheetRow("根据艺术家", currentSortOrder.baseSortOrder() == SortOrder.ARTIST_ASC, selectedColor) {
                        onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.ARTIST_ASC))
                    }
                    SortSheetRow("根据专辑", currentSortOrder.baseSortOrder() == SortOrder.ALBUM_ASC, selectedColor) {
                        onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.ALBUM_ASC))
                    }
                    SortSheetRow("根据年份", currentSortOrder.baseSortOrder() == SortOrder.YEAR_ASC, selectedColor) {
                        onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.YEAR_ASC))
                    }
                    SortSheetRow("根据播放次数", currentSortOrder.baseSortOrder() == SortOrder.PLAYBACK_INFO, selectedColor) {
                        onSortSelected(currentSortOrder.withBaseSortOrder(SortOrder.PLAYBACK_INFO))
                    }
                    SortSheetRow("倒序", currentSortOrder.isDescendingSortOrder(), selectedColor) {
                        onSortSelected(currentSortOrder.reversedSortOrder())
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(1.dp)
                            .background(secondaryColor.copy(alpha = 0.16f))
                    )

                    Spacer(Modifier.height(10.dp))
                    SheetTitle("布局")
                    Spacer(Modifier.height(8.dp))

                    SortSheetRow("紧凑列表", powerListState.currentLevel == ListZoomIndex.SMALL && !powerListState.isGrid, selectedColor) {
                        scope.launch { powerListState.snapToLevel(ListZoomIndex.SMALL) }
                    }
                    SortSheetRow("标准列表", powerListState.currentLevel == ListZoomIndex.NORMAL && !powerListState.isGrid, selectedColor) {
                        scope.launch { powerListState.snapToLevel(ListZoomIndex.NORMAL) }
                    }
                    SortSheetRow("大封面列表", powerListState.currentLevel == ListZoomIndex.ZOOMED && !powerListState.isGrid, selectedColor) {
                        scope.launch { powerListState.snapToLevel(ListZoomIndex.ZOOMED) }
                    }
                    SortSheetRow("四列网格", powerListState.columns == 4, selectedColor) {
                        scope.launch { powerListState.snapToColumns(4) }
                    }
                    SortSheetRow("三列网格", powerListState.columns == 3, selectedColor) {
                        scope.launch { powerListState.snapToColumns(3) }
                    }
                    SortSheetRow("双列网格", powerListState.columns == 2, selectedColor) {
                        scope.launch { powerListState.snapToColumns(2) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SongSelectionActionSheet(
    visible: Boolean,
    selectedCount: Int,
    onAddToPlaylist: () -> Unit,
    onAddToQueue: () -> Unit,
    onDelete: () -> Unit,
    onPlayNext: () -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return

    val interaction = remember { MutableInteractionSource() }
    val scheme = MiuixTheme.colorScheme
    val sheetColor = blendColor(scheme.background, scheme.primary, 0.04f)
    val secondaryColor = scheme.onSurfaceVariantSummary

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tweenMillis(140)) + slideInVertically(tweenMillis(220)) { it / 3 },
        exit = fadeOut(tweenMillis(120)) + slideOutVertically(tweenMillis(180)) { it / 3 }
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 0.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .navigationBarsPadding()
                    .padding(bottom = 12.dp)
                    .clip(RoundedCornerShape(28.dp))
                    .background(sheetColor)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = {}
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                SheetHandle(
                    color = secondaryColor,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Spacer(Modifier.height(14.dp))
                SheetTitle("已选择 ${selectedCount} 首")
                Spacer(Modifier.height(10.dp))

                SelectionSheetRow("下一首播放", onClick = onPlayNext)
                SelectionSheetRow("添加到队列", onClick = onAddToQueue)
                SelectionSheetRow("添加到歌单", onClick = onAddToPlaylist)
                SelectionSheetRow("删除歌曲", danger = true, onClick = onDelete)

                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(1.dp)
                        .background(secondaryColor.copy(alpha = 0.13f))
                )

                SelectionSheetRow("取消", onClick = onDismiss)
            }
        }
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
        Text(
            text = title,
            color = textColor,
            fontSize = 16.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
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
    SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> SortOrder.DURATION_ASC
}

private fun SortOrder.withBaseSortOrder(base: SortOrder): SortOrder = base

private fun SortOrder.isDescendingSortOrder(): Boolean = when (this) {
    SortOrder.TITLE_DESC,
    SortOrder.FILE_NAME_DESC,
    SortOrder.PATH_DESC,
    SortOrder.ARTIST_DESC,
    SortOrder.ALBUM_DESC,
    SortOrder.YEAR_DESC,
    SortOrder.PLAYBACK_INFO_DESC,
    SortOrder.DATE_ADDED_DESC,
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
    SortOrder.DURATION_ASC -> SortOrder.DURATION_DESC
    SortOrder.DURATION_DESC -> SortOrder.DURATION_ASC
}
