package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import com.rawsmusic.core.ui.widget.virtuallist.ComposeGenericVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListVisualItem

/** retained-view implementation collection-provider selection shell (`merge_selection_menu_list_items_w_aa`). */
@Composable
internal fun SelectableCollectionList(
    items: List<VirtualListVisualItem>,
    state: ComposeVirtualListState,
    contentTopPadding: Dp,
    sharedCoverSceneId: String,
    selectionActions: LibrarySongSelectionActions,
    songsForIndex: (Int) -> List<AudioFile>,
    artworkSongForIndex: (Int) -> AudioFile?,
    modifier: Modifier = Modifier,
    onItemClick: (VirtualListVisualItem, Int, CoverTransitionTarget?) -> Unit,
) {
    var selectionMode by remember(state) { mutableStateOf(false) }
    var selectedPositions by remember(state) { mutableStateOf<Set<Int>>(emptySet()) }
    val validSelected = remember(items.size, selectedPositions) {
        selectedPositions.filterTo(linkedSetOf()) { it in items.indices }
    }
    val selectedSongs = remember(validSelected, items) {
        validSelected.sorted()
            .asSequence()
            .flatMap { songsForIndex(it).asSequence() }
            .distinctBy { "${it.path}|${it.cueOffsetMs}|${it.cueTrackIndex}" }
            .toList()
    }
    val singleContextSong = remember(validSelected, items) {
        validSelected.singleOrNull()?.let(artworkSongForIndex)
    }
    val listSelectedPositions = remember(selectionMode, validSelected) {
        if (selectionMode && validSelected.isEmpty()) setOf(-1) else validSelected
    }
    val stableSystemBottom = rawStableNavigationBottomPadding()
    val normalBottomPadding = LocalBottomChromeInsets.current.contentBottom
    val bottomPadding by animateDpAsState(
        targetValue = if (selectionMode) 198.dp + stableSystemBottom else normalBottomPadding,
        animationSpec = tween(
            durationMillis = if (selectionMode) 500 else 200,
            easing = SelectionTransitionEasing,
        ),
        label = "collection-selection-bottom-reserve",
    )

    fun clearSelection() {
        selectionMode = false
        selectedPositions = emptySet()
    }
    fun toggle(index: Int) {
        if (index !in items.indices) return
        selectedPositions = if (index in selectedPositions) selectedPositions - index else selectedPositions + index
    }
    fun toggleAll() {
        val all = items.indices.toSet()
        selectedPositions = if (all.isNotEmpty() && selectedPositions.containsAll(all)) emptySet() else all
    }
    fun toggleRange() {
        val sorted = validSelected.sorted()
        if (sorted.size < 2) return
        val first = sorted.first()
        val last = sorted.last()
        if (last - first <= 1) return
        val inside = (first + 1 until last).toSet()
        selectedPositions = if (inside.all { it in selectedPositions }) selectedPositions - inside else selectedPositions + inside
    }

    LaunchedEffect(items.size) {
        selectedPositions = validSelected
        if (items.isEmpty() && selectionMode) clearSelection()
    }
    LaunchedEffect(selectionMode) { selectionActions.onSelectionModeChanged(selectionMode) }
    DisposableEffect(state) { onDispose { selectionActions.onSelectionModeChanged(false) } }

    ComposeGenericVirtualList(
        items = items,
        state = state,
        selectedPositions = listSelectedPositions,
        contentTopPadding = contentTopPadding,
        contentBottomPadding = bottomPadding,
        sharedCoverSceneId = sharedCoverSceneId,
        modifier = modifier,
        onItemClick = { item, index, target ->
            if (selectionMode) toggle(index) else onItemClick(item, index, target)
        },
        onItemLongClick = { _, index ->
            if (!selectionMode) selectionMode = true
            selectedPositions = selectedPositions + index
        },
    )

    SongSelectionMenu(
        visible = selectionMode,
        selectedSongs = selectedSongs,
        singleContextSong = singleContextSong,
        selectedCount = validSelected.size,
        totalCount = items.size,
        allSelected = items.isNotEmpty() && validSelected.size == items.size,
        rangeEnabled = validSelected.size >= 2,
        kind = SelectionMenuKind.COLLECTION,
        onToggleSelectAll = ::toggleAll,
        onToggleRange = ::toggleRange,
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
}

