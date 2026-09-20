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
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.systemui.rawStableNavigationBottomPadding
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState

/**
 * retained-view implementation-style selection owner for ordinary song-list providers.
 *
 * Selection identity is position-based rather than song-id based. Queue providers may contain the
 * same track more than once; using AudioFile.id would make one long-press select every duplicate.
 * This mirrors VirtualList's holder/list-position selection contract and also keeps range selection
 * deterministic when duplicate tracks are present.
 */
@Composable
internal fun SelectableSongList(
    songs: List<AudioFile>,
    state: ComposeVirtualListState,
    contentTopPadding: Dp,
    modifier: Modifier = Modifier,
    currentPlayingIndex: Int = -1,
    menuKind: SelectionMenuKind = SelectionMenuKind.TRACKS,
    selectionActions: LibrarySongSelectionActions,
    onSongClick: (AudioFile, Int) -> Unit,
) {
    var selectionMode by remember(state) { mutableStateOf(false) }
    var selectedPositions by remember(state) { mutableStateOf<Set<Int>>(emptySet()) }

    val validSelectedPositions = remember(songs.size, selectedPositions) {
        selectedPositions.filterTo(linkedSetOf()) { it in songs.indices }
    }
    val selectedSongs = remember(songs, validSelectedPositions) {
        validSelectedPositions.sorted().mapNotNull(songs::getOrNull)
    }
    // ComposeVirtualList uses a non-row sentinel to keep holder checkboxes mounted while the user
    // has temporarily deselected every item. No real row can ever equal -1.
    val listSelectedPositions = remember(selectionMode, validSelectedPositions) {
        if (selectionMode && validSelectedPositions.isEmpty()) setOf(-1) else validSelectedPositions
    }

    val stableSystemBottom = rawStableNavigationBottomPadding()
    val normalBottomPadding = LocalBottomChromeInsets.current.contentBottom
    val selectionListBottomPadding by animateDpAsState(
        targetValue = if (selectionMode) 198.dp + stableSystemBottom else normalBottomPadding,
        animationSpec = tween(
            durationMillis = if (selectionMode) 500 else 200,
            easing = SelectionTransitionEasing,
        ),
        label = "provider-selection-bottom-reserve",
    )

    fun clearSelection() {
        selectionMode = false
        selectedPositions = emptySet()
    }

    fun togglePosition(index: Int) {
        if (index !in songs.indices) return
        selectedPositions = if (index in selectedPositions) {
            selectedPositions - index
        } else {
            selectedPositions + index
        }
    }

    fun toggleSelectAll() {
        val all = songs.indices.toSet()
        selectedPositions = if (all.isNotEmpty() && selectedPositions.containsAll(all)) {
            emptySet()
        } else {
            all
        }
    }

    fun toggleSelectionRange() {
        val sorted = validSelectedPositions.sorted()
        if (sorted.size < 2) return
        val first = sorted.first()
        val last = sorted.last()
        if (last - first <= 1) return
        val inside = (first + 1 until last).toSet()
        selectedPositions = if (inside.all { it in selectedPositions }) {
            selectedPositions - inside
        } else {
            selectedPositions + inside
        }
    }

    LaunchedEffect(songs.size) {
        if (selectedPositions.any { it !in songs.indices }) {
            selectedPositions = validSelectedPositions
        }
        if (songs.isEmpty() && selectionMode) clearSelection()
    }
    LaunchedEffect(selectionMode) {
        selectionActions.onSelectionModeChanged(selectionMode)
    }
    DisposableEffect(state) {
        onDispose { selectionActions.onSelectionModeChanged(false) }
    }

    ComposeVirtualListFull(
        songs = songs,
        currentPlayingIndex = currentPlayingIndex,
        selectedPositions = listSelectedPositions,
        state = state,
        contentTopPadding = contentTopPadding,
        contentBottomPadding = selectionListBottomPadding,
        modifier = modifier,
        onSongClick = { song, index ->
            if (selectionMode) togglePosition(index) else onSongClick(song, index)
        },
        onSongLongClick = { _, index ->
            if (!selectionMode) selectionMode = true
            selectedPositions = selectedPositions + index
        },
    )

    SongSelectionMenu(
        visible = selectionMode,
        selectedSongs = selectedSongs,
        selectedCount = selectedSongs.size,
        totalCount = songs.size,
        allSelected = songs.isNotEmpty() && validSelectedPositions.size == songs.size,
        rangeEnabled = validSelectedPositions.size >= 2,
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
        kind = menuKind,
        onClearQueue = selectionActions.clearQueue,
        onDismiss = ::clearSelection,
    )
}

