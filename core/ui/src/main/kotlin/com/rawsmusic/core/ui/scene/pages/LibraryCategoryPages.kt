package com.rawsmusic.core.ui.scene.pages

import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndexData
import com.rawsmusic.core.ui.widget.index.RawIndexMode
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.powerlist.ComposerPowerListItem
import com.rawsmusic.core.ui.widget.powerlist.ComposeGenericPowerList
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.GenrePowerListItem
import com.rawsmusic.core.ui.widget.powerlist.PowerListVisualItem
import com.rawsmusic.core.ui.widget.powerlist.YearPowerListItem
import com.rawsmusic.core.ui.widget.powerlist.formatPowerListDuration
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.stablePowerListHash64
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun GenresPage(
    songs: List<AudioFile> = emptyList(),
    selectedGenreKey: String? = null,
    onBack: () -> Unit,
    onGenreClick: (String) -> Unit = {},
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    powerListState: ComposePowerListState = rememberComposePowerListState("genres"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("genre_detail_songs")
    val groups by remember(songs) {
        derivedStateOf { songs.toGenreGroups() }
    }
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.TITLE_ASC) }
    val sortedGroups = remember(groups, sortOrder) { groups.sortedFor(sortOrder) }

    if (selectedGenreKey.isNullOrBlank()) {
        CategoryListPage(
            groups = sortedGroups,
            listScene = NavScene.GENRE,
            indexMode = RawIndexMode.AUTO,
            state = powerListState,
            onBack = onBack,
            toItem = { group ->
                GenrePowerListItem(
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
            onSortOrderChange = { sortOrder = it },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedGenreKey) { Uri.decode(selectedGenreKey) }
        val group = remember(decodedKey, groups) {
            groups.firstOrNull { it.key == decodedKey } ?: CategoryGroupUi.empty(
                key = decodedKey,
                title = decodedKey.ifBlank { "未知流派" },
                subtitle = "流派"
            )
        }

        CollectionHeroDetailPage(
            hero = group.toHeroData(prefix = "cover:genre"),
            listScene = NavScene.GENRE,
            detailScene = NavScene.GENRE_DETAIL,
            songListState = detailState,
            onBack = onBack,
            onPlayQueue = onPlayQueue,
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
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    powerListState: ComposePowerListState = rememberComposePowerListState("years"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("year_detail_songs")
    val groups by remember(songs) {
        derivedStateOf { songs.toYearGroups() }
    }
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.YEAR_DESC) }
    val sortedGroups = remember(groups, sortOrder) { groups.sortedFor(sortOrder) }

    if (selectedYearKey.isNullOrBlank()) {
        CategoryListPage(
            groups = sortedGroups,
            listScene = NavScene.YEAR,
            indexMode = RawIndexMode.LATIN,
            yearIndex = true,
            state = powerListState,
            onBack = onBack,
            toItem = { group ->
                YearPowerListItem(
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
            onSortOrderChange = { sortOrder = it },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedYearKey) { Uri.decode(selectedYearKey) }
        val group = remember(decodedKey, groups) {
            groups.firstOrNull { it.key == decodedKey } ?: CategoryGroupUi.empty(
                key = decodedKey,
                title = decodedKey.ifBlank { "未知年份" },
                subtitle = "年份"
            )
        }

        CollectionHeroDetailPage(
            hero = group.toHeroData(prefix = "cover:year"),
            listScene = NavScene.YEAR,
            detailScene = NavScene.YEAR_DETAIL,
            songListState = detailState,
            onBack = onBack,
            onPlayQueue = onPlayQueue,
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
    onShuffle: (List<AudioFile>) -> Unit = {},
    onOpenFolder: () -> Unit = {},
    onSearch: () -> Unit = {},
    powerListState: ComposePowerListState = rememberComposePowerListState("composers"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("composer_detail_songs")
    val groups by remember(songs) {
        derivedStateOf { songs.toComposerGroups() }
    }
    var sortOrder by rememberSaveable { mutableStateOf(SortOrder.TITLE_ASC) }
    val sortedGroups = remember(groups, sortOrder) { groups.sortedFor(sortOrder) }

    if (selectedComposerKey.isNullOrBlank()) {
        CategoryListPage(
            groups = sortedGroups,
            listScene = NavScene.COMPOSER,
            indexMode = RawIndexMode.AUTO,
            state = powerListState,
            onBack = onBack,
            toItem = { group ->
                ComposerPowerListItem(
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
            onSortOrderChange = { sortOrder = it },
            modifier = modifier
        )
    } else {
        val decodedKey = remember(selectedComposerKey) { Uri.decode(selectedComposerKey) }
        val group = remember(decodedKey, groups) {
            groups.firstOrNull { it.key == decodedKey } ?: CategoryGroupUi.empty(
                key = decodedKey,
                title = decodedKey.ifBlank { "未知作曲家" },
                subtitle = "作曲家"
            )
        }

        CollectionHeroDetailPage(
            hero = group.toHeroData(prefix = "cover:composer"),
            listScene = NavScene.COMPOSER,
            detailScene = NavScene.COMPOSER_DETAIL,
            songListState = detailState,
            onBack = onBack,
            onPlayQueue = onPlayQueue,
            onOpenFolder = onOpenFolder,
            onShuffle = onShuffle,
            onSearch = onSearch,
            modifier = modifier
        )
    }
}

@Composable
private fun <T : PowerListVisualItem> CategoryListPage(
    groups: List<CategoryGroupUi>,
    listScene: NavScene,
    indexMode: RawIndexMode,
    yearIndex: Boolean = false,
    state: ComposePowerListState,
    onBack: () -> Unit,
    toItem: (CategoryGroupUi) -> T,
    onGroupClick: (String) -> Unit,
    onShuffle: () -> Unit,
    sortOrder: SortOrder,
    onSortOrderChange: (SortOrder) -> Unit,
    modifier: Modifier = Modifier
) {
    val coverRegistry = LocalSharedCoverRegistry.current
    val items = remember(groups) { groups.map(toItem) }
    val alphabetIndexData = if (yearIndex) {
        rememberYearIndexData(items)
    } else {
        rememberAdaptiveAlphabetIndexData(
            items = items,
            mode = indexMode
        ) { item -> item.title }
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
        onBack = onBack,
        powerListState = state,
        onShuffle = onShuffle,
        currentSortOrder = sortOrder,
        onSortSelected = onSortOrderChange,
        sortOptions = buildList {
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_name) to SortOrder.TITLE_ASC)
            if (listScene == NavScene.YEAR) {
                add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_year) to SortOrder.YEAR_ASC)
            }
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_modified) to SortOrder.DATE_ADDED_ASC)
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_duration) to SortOrder.DURATION_ASC)
            add(stringResource(com.rawsmusic.core.ui.R.string.sort_by_song_count) to SortOrder.PLAYBACK_INFO)
        },
        modifier = modifier
    ) { topPadding, backdropSource ->
        ComposeGenericPowerList(
            items = items,
            state = state,
            contentTopPadding = topPadding,
            sharedCoverSceneId = listScene.name,
            modifier = Modifier.fillMaxSize().then(backdropSource),
            onItemClick = { item, _, _ ->
                coverRegistry.freeze(
                    sceneId = listScene.name,
                    elementId = item.sharedCoverElementId
                )
                onGroupClick(item.stableKey)
            }
        )

        RawAlphabetIndex(
            data = alphabetIndexData,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(top = 92.dp, bottom = 118.dp, end = 0.dp)
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
    items: List<PowerListVisualItem>
): RawAlphabetIndexData {
    return remember(items) {
        val labels = mutableListOf<String>()
        val targets = linkedMapOf<String, Int>()

        items.forEachIndexed { index, item ->
            val year = item.title.toIntOrNull()
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

        RawAlphabetIndexData(
            labels = labels,
            targets = targets,
            mode = RawIndexMode.LATIN
        )
    }
}

@Stable
private data class CategoryGroupUi(
    val key: String,
    val name: String,
    val subtitle: String,
    val songs: List<AudioFile>,
    val coverKey: String,
    val totalDurationMs: Long
) {
    val songCount: Int get() = songs.size

    fun toHeroData(prefix: String): CollectionHeroData {
        return CollectionHeroData(
            stableKey = key,
            sharedElementId = "$prefix:${stablePowerListHash64(key)}",
            coverKey = coverKey,
            title = name,
            subtitle = subtitle,
            meta = "$songCount | ${formatPowerListDuration(totalDurationMs)}",
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

private fun List<AudioFile>.toGenreGroups(): List<CategoryGroupUi> {
    return groupByCategory(
        unknownName = "未知流派",
        subtitle = "流派",
        keySelector = { it.genre }
    ).sortedWith(compareBy<CategoryGroupUi> { it.name.lowercase() })
}

private fun List<AudioFile>.toComposerGroups(): List<CategoryGroupUi> {
    return groupByCategory(
        unknownName = "未知作曲家",
        subtitle = "作曲家",
        keySelector = { it.composer }
    ).sortedWith(compareBy<CategoryGroupUi> { it.name.lowercase() })
}

private fun List<AudioFile>.toYearGroups(): List<CategoryGroupUi> {
    return groupByCategory(
        unknownName = "未知年份",
        subtitle = "年份",
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
        SortOrder.DATE_ADDED_DESC, SortOrder.DURATION_DESC, SortOrder.YEAR_DESC,
        SortOrder.FILE_NAME_DESC, SortOrder.PATH_DESC, SortOrder.PLAYBACK_INFO_DESC
    )
    val comparator = when (order) {
        SortOrder.YEAR_ASC, SortOrder.YEAR_DESC -> compareBy<CategoryGroupUi> { it.key.toIntOrNull() ?: Int.MIN_VALUE }
        SortOrder.DURATION_ASC, SortOrder.DURATION_DESC -> compareBy { it.totalDurationMs }
        SortOrder.DATE_ADDED_ASC, SortOrder.DATE_ADDED_DESC -> compareBy { group -> group.songs.maxOfOrNull { it.dateModified } ?: 0L }
        SortOrder.PLAYBACK_INFO, SortOrder.PLAYBACK_INFO_DESC -> compareBy { it.songCount }
        else -> compareBy<CategoryGroupUi> { it.name.lowercase() }
    }
    return sortedWith(if (descending) comparator.reversed() else comparator)
}
