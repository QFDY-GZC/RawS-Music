package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.virtuallist.ComposeGenericVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListFull
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.FolderVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.stableVirtualListHash64
import com.rawsmusic.core.ui.widget.virtuallist.retainedMappedList
import com.rawsmusic.module.data.prefs.CollectionSortPreferences
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File
import kotlin.math.roundToInt

@Composable
fun FoldersPage(
    songs: List<AudioFile> = emptyList(),
    selectedFolderPath: String? = null,
    onBack: () -> Unit,
    onFolderClick: (String) -> Unit = {},
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("folders"),
    detailListState: ComposeVirtualListState = rememberComposeVirtualListState("folder_detail_songs"),
    modifier: Modifier = Modifier
) {
    val folders by produceState(
        initialValue = LibrarySceneGroupingWarmup.folders(songs),
        key1 = songs,
    ) {
        value = LibrarySceneGroupingWarmup.loadFolders(songs)
    }
    var sortOrder by remember {
        mutableStateOf(
            CollectionSortPreferences.read("library_root", "folders", SortOrder.TITLE_ASC)
        )
    }
    val sortedFolders = remember(folders, sortOrder) {
        if (sortOrder == SortOrder.TITLE_ASC) folders else folders.sortedFor(sortOrder)
    }

    if (selectedFolderPath.isNullOrBlank()) {
        FolderListPage(
            folders = sortedFolders,
            librarySongCount = songs.size,
            state = virtualListState,
            onBack = onBack,
            onFolderClick = onFolderClick,
            selectionActions = selectionActions,
            onShuffle = { onShuffle(sortedFolders.flatMap { it.songs }) },
            sortOrder = sortOrder,
            onSortOrderChange = {
                sortOrder = it
                CollectionSortPreferences.write("library_root", "folders", it)
            },
            modifier = modifier
        )
    } else {
        val decodedPath = remember(selectedFolderPath) { Uri.decode(selectedFolderPath) }
        val folder = remember(decodedPath, folders) {
            folders.firstOrNull { it.path == decodedPath } ?: FolderGroupUi.empty(decodedPath)
        }
        FolderDetailPage(
            folder = folder,
            songListState = detailListState,
            onBack = onBack,
            onPlayQueue = onPlayQueue,
            onSongLongClick = onSongLongClick,
            selectionActions = selectionActions,
            onOpenFolder = onOpenFolder,
            onShuffle = onShuffle,
            onSearch = onSearch,
            modifier = modifier
        )
    }
}

@Composable
private fun FolderListPage(
    folders: List<FolderGroupUi>,
    librarySongCount: Int,
    state: ComposeVirtualListState,
    onBack: () -> Unit,
    onFolderClick: (String) -> Unit,
    selectionActions: LibrarySongSelectionActions,
    onShuffle: () -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current

    val items = remember(folders) {
        retainedMappedList(folders) { folder ->
            FolderVirtualListItem(
                path = folder.path,
                name = folder.name,
                parentName = folder.parentName,
                cover = folder.coverKey,
                songCount = folder.songCount,
                totalDurationMs = folder.totalDurationMs
            )
        }
    }
    val warmedAlphabetIndex = remember(folders, sortOrder) {
        if (sortOrder == SortOrder.TITLE_ASC) LibrarySceneGroupingWarmup.foldersIndex(folders) else null
    }
    val alphabetIndexData = warmedAlphabetIndex
        ?: rememberAdaptiveAlphabetIndexData(folders) { it.name }

    LibraryListScaffold(
        title = stringResource(com.rawsmusic.core.ui.R.string.library_title_folders),
        sceneId = NavScene.FOLDERS.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_folders, folders.size, librarySongCount),
        onBack = onBack,
        virtualListState = state,
        onShuffle = onShuffle,
        currentSortOrder = sortOrder,
        onSortSelected = onSortOrderChange,
        sortOptions = listOf(
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_name) to SortOrder.TITLE_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_path) to SortOrder.PATH_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_added) to SortOrder.DATE_ADDED_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_modified) to SortOrder.DATE_MODIFIED_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_duration) to SortOrder.DURATION_ASC,
            stringResource(com.rawsmusic.core.ui.R.string.sort_by_song_count) to SortOrder.PLAYBACK_INFO
        ),
        modifier = modifier
    ) { topPadding, backdropSource ->
        SelectableCollectionList(
            items = items,
            state = state,
            contentTopPadding = topPadding,
            sharedCoverSceneId = NavScene.FOLDERS.name,
            selectionActions = selectionActions,
            songsForIndex = { index -> folders.getOrNull(index)?.songs.orEmpty() },
            artworkSongForIndex = { index ->
                folders.getOrNull(index)?.songs?.let { folderSongs ->
                    folderSongs.firstOrNull { it.albumArtPath.isNotBlank() } ?: folderSongs.firstOrNull()
                }
            },
            modifier = Modifier.fillMaxSize().then(backdropSource),
            onItemClick = { item, _, _ ->
                val folder = item as? FolderVirtualListItem ?: return@SelectableCollectionList

                coverRegistry.freeze(
                    sceneId = NavScene.FOLDERS.name,
                    elementId = folder.sharedCoverElementId
                )
                onFolderClick(folder.path)
            }
        )
        if (LocalReferenceLibraryProviderPublicationOnly.current) return@LibraryListScaffold

        RawAlphabetIndex(
            data = alphabetIndexData,
            scrollActiveProvider = { state.isListScrollInProgress },
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 92.dp, bottom = LocalBottomChromeInsets.current.contentBottom, end = 0.dp)
                .then(backdropSource)
                .zIndex(30f),
            onTopSelect = {
                state.requestScrollToIndex(0)
            },
            onSelect = { _, index ->
                state.requestScrollToIndex(index)
            }
        )
    }
}

@Composable
private fun FolderDetailPage(
    folder: FolderGroupUi,
    songListState: ComposeVirtualListState,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
    selectionActions: LibrarySongSelectionActions,
    onOpenFolder: () -> Unit,
    onShuffle: (List<AudioFile>) -> Unit,
    onSearch: () -> Unit,
    modifier: Modifier = Modifier
) {
    CollectionHeroDetailPage(
        hero = CollectionHeroData(
            stableKey = folder.path,
            sharedElementId = folder.sharedElementId,
            coverKey = folder.coverKey,
            title = folder.name,
            subtitle = folder.parentName,
            meta = "${folder.songCount} | ${formatDuration(folder.totalDurationMs)}",
            songs = folder.songs
        ),
        listScene = NavScene.FOLDERS,
        detailScene = NavScene.FOLDER_DETAIL,
        songListState = songListState,
        onBack = onBack,
        onPlayQueue = onPlayQueue,
        onSongLongClick = onSongLongClick,
        selectionActions = selectionActions,
        onOpenFolder = onOpenFolder,
        onShuffle = onShuffle,
        onSearch = onSearch,
        modifier = modifier
    )
}

@Stable
internal data class FolderGroupUi(
    val path: String,
    val name: String,
    val parentName: String,
    val songs: List<AudioFile>,
    val coverKey: String,
    val totalDurationMs: Long
) {
    val songCount: Int get() = songs.size
    val sharedElementId: String get() = "cover:folder:${stableVirtualListHash64(path)}"

    companion object {
        fun empty(path: String): FolderGroupUi {
            val file = File(path)
            return FolderGroupUi(
                path = path,
                name = file.name.ifBlank { path },
                parentName = file.parentFile?.name.orEmpty(),
                songs = emptyList(),
                coverKey = "",
                totalDurationMs = 0L
            )
        }
    }
}

internal fun List<AudioFile>.toFolderGroups(): List<FolderGroupUi> {
    return asSequence()
        .filter { it.path.isNotBlank() }
        .groupBy { song -> File(song.path).parentFile?.absolutePath.orEmpty() }
        .filterKeys { it.isNotBlank() }
        .map { (folderPath, folderSongs) ->
            val folder = File(folderPath)
            val orderedSongs = folderSongs.sortedWith(
                compareBy<AudioFile> { if (it.trackNumber > 0) it.trackNumber else Int.MAX_VALUE }
                    .thenBy { it.displayTitle().lowercase() }
            )
            FolderGroupUi(
                path = folderPath,
                name = folder.name.ifBlank { folderPath },
                parentName = folder.parentFile?.name?.ifBlank { folder.parent.orEmpty() }.orEmpty(),
                songs = orderedSongs,
                coverKey = orderedSongs.firstOrNull()?.coverKey().orEmpty(),
                totalDurationMs = orderedSongs.sumOf { it.duration.coerceAtLeast(0L) }
            )
        }
        // The grouping cache is warmed on Dispatchers.Default. Keep its canonical order equal to
        // the UI default so entering Folders does not immediately sort the whole provider again.
        .sortedWith(compareBy<FolderGroupUi> { it.name.lowercase() }.thenBy { it.path.lowercase() })
}

private fun List<FolderGroupUi>.sortedFor(order: SortOrder): List<FolderGroupUi> {
    val ascending = when (order) {
        SortOrder.TITLE_DESC, SortOrder.FILE_NAME_DESC, SortOrder.PATH_DESC,
        SortOrder.DATE_ADDED_DESC, SortOrder.DATE_MODIFIED_DESC, SortOrder.DURATION_DESC, SortOrder.YEAR_DESC,
        SortOrder.ARTIST_DESC, SortOrder.ALBUM_DESC, SortOrder.PLAYBACK_INFO_DESC -> false
        else -> true
    }
    val comparator = when (order) {
        SortOrder.PATH_ASC, SortOrder.PATH_DESC -> compareBy<FolderGroupUi> { it.path.lowercase() }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy { it.totalDurationMs }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> compareBy { group ->
            group.songs.maxOfOrNull { it.dateAdded } ?: 0L
        }
        SortOrder.DATE_MODIFIED_ASC, SortOrder.DATE_MODIFIED_DESC -> compareBy { group ->
            group.songs.maxOfOrNull { it.dateModified } ?: 0L
        }
        SortOrder.PLAYBACK_INFO, SortOrder.PLAYBACK_INFO_DESC -> compareBy { it.songCount }
        else -> compareBy<FolderGroupUi> { it.name.lowercase() }.thenBy { it.path.lowercase() }
    }
    return sortedWith(if (ascending) comparator else comparator.reversed())
}

private fun AudioFile.coverKey(): String {
    return this.coverKey
}

private fun AudioFile.displayTitle(): String {
    return title.ifBlank { File(path).nameWithoutExtension.ifBlank { "Unknown" } }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = (ms / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0L) "%d:%02d:%02d".format(hours, minutes, seconds)
    else "%d:%02d".format(minutes, seconds)
}
