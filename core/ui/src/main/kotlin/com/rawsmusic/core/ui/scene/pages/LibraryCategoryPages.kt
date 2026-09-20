package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndexData
import com.rawsmusic.core.ui.widget.index.RawIndexMode
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.virtuallist.ComposerVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.ComposeGenericVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.GenreVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListVisualItem
import com.rawsmusic.core.ui.widget.virtuallist.YearVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.formatVirtualListDuration
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.stableVirtualListHash64
import com.rawsmusic.core.ui.widget.virtuallist.retainedMappedList
import com.rawsmusic.module.data.prefs.CollectionSortPreferences
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun GenresPage(
    songs: List<AudioFile> = emptyList(),
    selectedGenreKey: String? = null,
    onBack: () -> Unit,
    onGenreClick: (String) -> Unit = {},
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("genres"),
    detailListState: ComposeVirtualListState = rememberComposeVirtualListState("genre_detail_songs"),
    modifier: Modifier = Modifier
) {
    val genreTitle = stringResource(R.string.library_title_genres)
    val groups by produceState(
        initialValue = LibrarySceneGroupingWarmup.genres(songs),
        key1 = songs,
    ) {
        value = LibrarySceneGroupingWarmup.loadGenres(songs)
    }
    var sortOrder by remember {
        mutableStateOf(
            CollectionSortPreferences.read("library_root", "genres", SortOrder.TITLE_ASC)
        )
    }
    val sortedGroups = remember(groups, sortOrder) {
        if (sortOrder == SortOrder.TITLE_ASC) groups else groups.sortedFor(sortOrder)
    }

    if (selectedGenreKey.isNullOrBlank()) {
        CategoryListPage(
            groups = sortedGroups,
            librarySongCount = songs.size,
            listScene = NavScene.GENRE,
            indexMode = RawIndexMode.AUTO,
            state = virtualListState,
            onBack = onBack,
            selectionActions = selectionActions,
            toItem = { group ->
                GenreVirtualListItem(
                    key = group.key,
                    name = group.name,
                    cover = group.coverKey,
                    songCount = group.songCount,
                    totalDurationMs = group.totalDurationMs
                )
            },
            onGroupClick = onGenreClick,
            onShuffle = { onShuffle(sortedGroups.flatMap { it.songs }) },
            sortOrder = sortOrder,
            onSortOrderChange = {
                sortOrder = it
                CollectionSortPreferences.write("library_root", "genres", it)
            },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedGenreKey) { Uri.decode(selectedGenreKey) }
        val group = remember(decodedKey, groups) {
            groups.firstOrNull { it.key == decodedKey } ?: CategoryGroupUi.empty(
                key = decodedKey,
                title = decodedKey.ifBlank { "未知流派" },
                subtitle = genreTitle
            )
        }

        CollectionHeroDetailPage(
            hero = group.toHeroData(prefix = "cover:genre", subtitleOverride = genreTitle),
            listScene = NavScene.GENRE,
            detailScene = NavScene.GENRE_DETAIL,
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
fun YearsPage(
    songs: List<AudioFile> = emptyList(),
    selectedYearKey: String? = null,
    onBack: () -> Unit,
    onYearClick: (String) -> Unit = {},
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("years"),
    detailListState: ComposeVirtualListState = rememberComposeVirtualListState("year_detail_songs"),
    modifier: Modifier = Modifier
) {
    val yearTitle = stringResource(R.string.library_title_years)
    val groups by produceState(
        initialValue = LibrarySceneGroupingWarmup.years(songs),
        key1 = songs,
    ) {
        value = LibrarySceneGroupingWarmup.loadYears(songs)
    }
    var sortOrder by remember {
        mutableStateOf(
            CollectionSortPreferences.read("library_root", "years", SortOrder.YEAR_DESC)
        )
    }
    val sortedGroups = remember(groups, sortOrder) {
        if (sortOrder == SortOrder.YEAR_DESC) groups else groups.sortedFor(sortOrder)
    }

    if (selectedYearKey.isNullOrBlank()) {
        CategoryListPage(
            groups = sortedGroups,
            librarySongCount = songs.size,
            listScene = NavScene.YEAR,
            indexMode = RawIndexMode.LATIN,
            yearIndex = true,
            state = virtualListState,
            onBack = onBack,
            selectionActions = selectionActions,
            toItem = { group ->
                YearVirtualListItem(
                    key = group.key,
                    name = group.name,
                    cover = group.coverKey,
                    songCount = group.songCount,
                    totalDurationMs = group.totalDurationMs
                )
            },
            onGroupClick = onYearClick,
            onShuffle = { onShuffle(sortedGroups.flatMap { it.songs }) },
            sortOrder = sortOrder,
            onSortOrderChange = {
                sortOrder = it
                CollectionSortPreferences.write("library_root", "years", it)
            },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedYearKey) { Uri.decode(selectedYearKey) }
        val group = remember(decodedKey, groups) {
            groups.firstOrNull { it.key == decodedKey } ?: CategoryGroupUi.empty(
                key = decodedKey,
                title = decodedKey.ifBlank { "未知年份" },
                subtitle = yearTitle
            )
        }

        CollectionHeroDetailPage(
            hero = group.toHeroData(prefix = "cover:year", subtitleOverride = yearTitle),
            listScene = NavScene.YEAR,
            detailScene = NavScene.YEAR_DETAIL,
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
fun ComposersPage(
    songs: List<AudioFile> = emptyList(),
    selectedComposerKey: String? = null,
    onBack: () -> Unit,
    onComposerClick: (String) -> Unit = {},
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    selectionActions: LibrarySongSelectionActions = LibrarySongSelectionActions(),
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    virtualListState: ComposeVirtualListState = rememberComposeVirtualListState("composers"),
    detailListState: ComposeVirtualListState = rememberComposeVirtualListState("composer_detail_songs"),
    modifier: Modifier = Modifier
) {
    val composerTitle = stringResource(R.string.library_title_composers)
    val groups by produceState(
        initialValue = LibrarySceneGroupingWarmup.composers(songs),
        key1 = songs,
    ) {
        value = LibrarySceneGroupingWarmup.loadComposers(songs)
    }
    var sortOrder by remember {
        mutableStateOf(
            CollectionSortPreferences.read("library_root", "composers", SortOrder.TITLE_ASC)
        )
    }
    val sortedGroups = remember(groups, sortOrder) {
        if (sortOrder == SortOrder.TITLE_ASC) groups else groups.sortedFor(sortOrder)
    }

    if (selectedComposerKey.isNullOrBlank()) {
        CategoryListPage(
            groups = sortedGroups,
            librarySongCount = songs.size,
            listScene = NavScene.COMPOSER,
            indexMode = RawIndexMode.AUTO,
            state = virtualListState,
            onBack = onBack,
            selectionActions = selectionActions,
            toItem = { group ->
                ComposerVirtualListItem(
                    key = group.key,
                    name = group.name,
                    cover = group.coverKey,
                    songCount = group.songCount,
                    totalDurationMs = group.totalDurationMs
                )
            },
            onGroupClick = onComposerClick,
            onShuffle = { onShuffle(sortedGroups.flatMap { it.songs }) },
            sortOrder = sortOrder,
            onSortOrderChange = {
                sortOrder = it
                CollectionSortPreferences.write("library_root", "composers", it)
            },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedComposerKey) { Uri.decode(selectedComposerKey) }
        val group = remember(decodedKey, groups) {
            groups.firstOrNull { it.key == decodedKey } ?: CategoryGroupUi.empty(
                key = decodedKey,
                title = decodedKey.ifBlank { "未知作曲家" },
                subtitle = composerTitle
            )
        }

        CollectionHeroDetailPage(
            hero = group.toHeroData(prefix = "cover:composer", subtitleOverride = composerTitle),
            listScene = NavScene.COMPOSER,
            detailScene = NavScene.COMPOSER_DETAIL,
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
private fun <T : VirtualListVisualItem> CategoryListPage(
    groups: List<CategoryGroupUi>,
    librarySongCount: Int,
    listScene: NavScene,
    indexMode: RawIndexMode,
    yearIndex: Boolean = false,
    state: ComposeVirtualListState,
    onBack: () -> Unit,
    selectionActions: LibrarySongSelectionActions,
    toItem: (CategoryGroupUi) -> T,
    onGroupClick: (String) -> Unit,
    onShuffle: () -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val items = remember(groups) { retainedMappedList(groups, toItem) }
    val warmedAlphabetIndex = remember(groups, listScene, sortOrder, yearIndex) {
        when {
            yearIndex && sortOrder == SortOrder.YEAR_DESC -> LibrarySceneGroupingWarmup.yearsIndex(groups)
            listScene == NavScene.GENRE && sortOrder == SortOrder.TITLE_ASC -> LibrarySceneGroupingWarmup.genresIndex(groups)
            listScene == NavScene.COMPOSER && sortOrder == SortOrder.TITLE_ASC -> LibrarySceneGroupingWarmup.composersIndex(groups)
            else -> null
        }
    }
    val alphabetIndexData = warmedAlphabetIndex ?: if (yearIndex) {
        rememberYearIndexData(groups)
    } else {
        rememberAdaptiveAlphabetIndexData(
            items = groups,
            mode = indexMode
        ) { group -> group.name }
    }

    val title = when (listScene) {
        NavScene.GENRE -> stringResource(com.rawsmusic.core.ui.R.string.library_title_genres)
        NavScene.YEAR -> stringResource(com.rawsmusic.core.ui.R.string.library_title_years)
        NavScene.COMPOSER -> stringResource(com.rawsmusic.core.ui.R.string.library_title_composers)
        else -> stringResource(com.rawsmusic.core.ui.R.string.library_title_fallback)
    }
    LibraryListScaffold(
        title = title,
        sceneId = listScene.name,
        statisticsText = stringResource(com.rawsmusic.core.ui.R.string.library_statistics_categories, groups.size, librarySongCount),
        onBack = onBack,
        virtualListState = state,
        onShuffle = onShuffle,
        currentSortOrder = sortOrder,
        onSortSelected = onSortOrderChange,
        sortOptions = buildList {
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_name) to SortOrder.TITLE_ASC)
            if (listScene == NavScene.YEAR) {
                add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_year) to SortOrder.YEAR_ASC)
            }
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_added) to SortOrder.DATE_ADDED_ASC)
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_modified) to SortOrder.DATE_MODIFIED_ASC)
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_duration) to SortOrder.DURATION_ASC)
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_song_count) to SortOrder.PLAYBACK_INFO)
        },
        modifier = modifier
    ) { topPadding, backdropSource ->
        SelectableCollectionList(
            items = items,
            state = state,
            contentTopPadding = topPadding,
            sharedCoverSceneId = listScene.name,
            selectionActions = selectionActions,
            songsForIndex = { index -> groups.getOrNull(index)?.songs.orEmpty() },
            artworkSongForIndex = { index ->
                groups.getOrNull(index)?.songs?.let { groupSongs ->
                    groupSongs.firstOrNull { it.albumArtPath.isNotBlank() } ?: groupSongs.firstOrNull()
                }
            },
            modifier = Modifier.fillMaxSize().then(backdropSource),
            onItemClick = { item, _, _ ->
                coverRegistry.freeze(
                    sceneId = listScene.name,
                    elementId = item.sharedCoverElementId
                )
                onGroupClick(item.stableKey)
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
private fun rememberYearIndexData(
    groups: List<CategoryGroupUi>
): RawAlphabetIndexData {
    return remember(groups) { buildYearIndexData(groups) }
}

internal fun buildYearIndexData(groups: List<CategoryGroupUi>): RawAlphabetIndexData {
    val labels = mutableListOf<String>()
    val targets = linkedMapOf<String, Int>()

    groups.forEachIndexed { index, group ->
        val year = group.name.toIntOrNull()
        val label = if (year != null && year > 0) {
            (year % 100).toString().padStart(2, '0')
        } else {
            "#"
        }

        if (!targets.containsKey(label)) {
            targets[label] = index
            labels.add(label)
        }
    }

    return RawAlphabetIndexData(
        labels = labels,
        targets = targets,
        mode = RawIndexMode.LATIN
    )
}

@Stable
internal data class CategoryGroupUi(
    val key: String,
    val name: String,
    val subtitle: String,
    val songs: List<AudioFile>,
    val coverKey: String,
    val totalDurationMs: Long
) {
    val songCount: Int get() = songs.size

    fun toHeroData(prefix: String, subtitleOverride: String? = null): CollectionHeroData {
        return CollectionHeroData(
            stableKey = key,
            sharedElementId = "$prefix:${stableVirtualListHash64(key)}",
            coverKey = coverKey,
            title = name,
            subtitle = subtitleOverride ?: subtitle,
            meta = "$songCount | ${formatVirtualListDuration(totalDurationMs)}",
            songs = songs
        )
    }

    companion object {
        fun empty(
            key: String,
            title: String,
            subtitle: String
        ): CategoryGroupUi {
            return CategoryGroupUi(
                key = key,
                name = title,
                subtitle = subtitle,
                songs = emptyList(),
                coverKey = "",
                totalDurationMs = 0L
            )
        }
    }
}

internal fun List<AudioFile>.toGenreGroups(subtitle: String = ""): List<CategoryGroupUi> {
    return groupByCategory(
        unknownName = "未知流派",
        subtitle = subtitle,
        keySelector = { it.genre }
    ).sortedWith(compareBy<CategoryGroupUi> { it.name.lowercase() })
}

internal fun List<AudioFile>.toComposerGroups(subtitle: String = ""): List<CategoryGroupUi> {
    return groupByCategory(
        unknownName = "未知作曲家",
        subtitle = subtitle,
        keySelector = { it.composer }
    ).sortedWith(compareBy<CategoryGroupUi> { it.name.lowercase() })
}

internal fun List<AudioFile>.toYearGroups(subtitle: String = ""): List<CategoryGroupUi> {
    return groupByCategory(
        unknownName = "未知年份",
        subtitle = subtitle,
        keySelector = { song ->
            song.year
                .takeIf { it > 0 }
                ?.toString()
                .orEmpty()
        }
    ).sortedWith(
        compareByDescending<CategoryGroupUi> {
            it.key.toIntOrNull() ?: Int.MIN_VALUE
        }.thenBy { it.name }
    )
}

private fun List<AudioFile>.groupByCategory(
    unknownName: String,
    subtitle: String,
    keySelector: (AudioFile) -> String
): List<CategoryGroupUi> {
    return asSequence()
        .groupBy { song ->
            keySelector(song).trim().ifBlank { unknownName }
        }
        .map { (key, categorySongs) ->
            val coverSong = categorySongs.firstOrNull { it.albumArtPath.isNotBlank() }
                ?: categorySongs.firstOrNull()
            CategoryGroupUi(
                key = key,
                name = key,
                subtitle = subtitle,
                songs = categorySongs.sortedWith(
                    compareBy<AudioFile> { it.album.lowercase() }
                        .thenBy { it.discNumber }
                        .thenBy { it.trackNumber }
                        .thenBy { it.displayName.lowercase() }
                ),
                coverKey = coverSong?.coverKey.orEmpty(),
                totalDurationMs = categorySongs.sumOf { it.duration.coerceAtLeast(0L) }
            )
        }
}

private fun List<CategoryGroupUi>.sortedFor(order: SortOrder): List<CategoryGroupUi> {
    val descending = order in setOf(
        SortOrder.TITLE_DESC, SortOrder.ARTIST_DESC, SortOrder.ALBUM_DESC,
        SortOrder.DATE_ADDED_DESC, SortOrder.DATE_MODIFIED_DESC, SortOrder.DURATION_DESC, SortOrder.YEAR_DESC,
        SortOrder.FILE_NAME_DESC, SortOrder.PATH_DESC, SortOrder.PLAYBACK_INFO_DESC
    )
    val comparator = when (order) {
        SortOrder.YEAR_ASC, SortOrder.YEAR_DESC -> compareBy<CategoryGroupUi> { it.key.toIntOrNull() ?: Int.MIN_VALUE }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy { it.totalDurationMs }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> compareBy { group -> group.songs.maxOfOrNull { it.dateAdded } ?: 0L }
        SortOrder.DATE_MODIFIED_ASC, SortOrder.DATE_MODIFIED_DESC -> compareBy { group -> group.songs.maxOfOrNull { it.dateModified } ?: 0L }
        SortOrder.PLAYBACK_INFO, SortOrder.PLAYBACK_INFO_DESC -> compareBy { it.songCount }
        else -> compareBy<CategoryGroupUi> { it.name.lowercase() }
    }
    return sortedWith(if (descending) comparator.reversed() else comparator)
}
