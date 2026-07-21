package com.rawsmusic.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.systemGestureExclusion
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.rawsmusic.R
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.scene.GlobalSearchScope
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.powerlist.ListZoomIndex
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.ComposeGenericPowerList
import com.rawsmusic.core.ui.widget.powerlist.PowerListSectionHeader
import com.rawsmusic.core.ui.widget.powerlist.PowerListVisualItem
import com.rawsmusic.core.ui.widget.powerlist.rememberComposePowerListState
import com.rawsmusic.core.ui.widget.powerlist.stablePowerListHash64
import com.rawsmusic.module.data.repository.MusicRepository
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val ALBUM_KEY_SEPARATOR = "␟"

private data class SearchEntry(
    val scope: GlobalSearchScope,
    val key: String,
    val title: String,
    val subtitle: String,
    val meta: String,
    val coverKey: String,
    val songs: List<AudioFile>,
    val song: AudioFile? = null
) {
    val searchText: String = listOf(title, subtitle, meta, key)
        .joinToString(separator = "\n")
        .lowercase()
}

private data class SearchPowerListItem(
    val entry: SearchEntry
) : PowerListVisualItem {
    override val stableId: Long = stablePowerListHash64("${entry.scope.token}:${entry.key}")
    override val stableKey: String = entry.key
    override val sharedCoverElementId: String = "cover:search:$stableId"
    override val coverKey: String = entry.coverKey
    override val title: String = entry.title
    override val subtitle: String = entry.subtitle
    override val meta: String = entry.meta
}

private data class SearchDimension(
    val scope: GlobalSearchScope,
    val label: String
)

@Composable
fun GlobalSearchScreen(
    initialScope: GlobalSearchScope?,
    onBack: () -> Unit,
    onSongClick: (AudioFile) -> Unit,
    onCategoryClick: (GlobalSearchScope, String) -> Unit,
    onShuffle: (List<AudioFile>) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val session = remember(context, initialScope) {
        GlobalSearchSessionStore.get(context, initialScope)
    }
    val songs by MusicRepository.songs.collectAsState()
    val historyStore = remember(context) { SearchHistoryStore(context) }
    var history by remember { mutableStateOf(historyStore.load()) }
    var debouncedQuery by remember(session) { mutableStateOf(session.query.trim()) }
    var showSortDialog by remember { mutableStateOf(false) }

    val dimensions = listOf(
        SearchDimension(GlobalSearchScope.ALBUM, stringResource(R.string.global_search_category_album)),
        SearchDimension(GlobalSearchScope.ARTIST, stringResource(R.string.global_search_category_artist)),
        SearchDimension(GlobalSearchScope.FOLDER, stringResource(R.string.global_search_category_folder)),
        SearchDimension(GlobalSearchScope.GENRE, stringResource(R.string.global_search_category_genre)),
        SearchDimension(GlobalSearchScope.YEAR, stringResource(R.string.global_search_category_year)),
        SearchDimension(GlobalSearchScope.COMPOSER, stringResource(R.string.global_search_category_composer)),
        SearchDimension(GlobalSearchScope.SONG, stringResource(R.string.global_search_category_songs))
    )
    val dimensionLabels = dimensions.associate { it.scope to it.label }
    val powerListState = rememberComposePowerListState("global_search_results")
    val catalog = remember(songs, dimensionLabels) {
        buildCatalog(
            songs = songs,
            labels = dimensionLabels,
            unknownAlbum = context.getString(R.string.global_search_unknown_album),
            unknownArtist = context.getString(R.string.global_search_unknown_artist),
            unknownGenre = context.getString(R.string.global_search_unknown_genre),
            unknownYear = context.getString(R.string.global_search_unknown_year),
            unknownComposer = context.getString(R.string.global_search_unknown_composer),
            musicLabel = context.getString(R.string.global_search_music),
            songCountLabel = {
                context.resources.getQuantityString(R.plurals.global_search_song_count, it, it)
            }
        )
    }

    LaunchedEffect(session.query) {
        delay(140)
        debouncedQuery = session.query.trim()
    }

    val shownDimensions = if (session.entryScopeMode && session.activeScopes.size == 1) {
        dimensions.filter { it.scope in session.activeScopes }
    } else {
        dimensions
    }
    val enabledScopes = session.activeScopes.ifEmpty {
        dimensions.mapTo(linkedSetOf()) { it.scope }
    }
    val normalizedQuery = debouncedQuery.lowercase()
    val results = remember(
        catalog,
        normalizedQuery,
        enabledScopes,
        session.sortMode,
        session.sortDescending
    ) {
        enabledScopes.associateWith { scope ->
            val filtered = catalog[scope].orEmpty().filter { normalizedQuery in it.searchText }
            val sorted = when (session.sortMode) {
                GlobalSearchSortMode.RELEVANCE -> filtered.sortedWith(
                    compareBy<SearchEntry> { it.relevanceRank(normalizedQuery) }
                        .thenBy { it.title.lowercase() }
                )
                GlobalSearchSortMode.NAME -> filtered.sortedBy { it.title.lowercase() }
                GlobalSearchSortMode.SONG_COUNT -> filtered.sortedWith(
                    compareByDescending<SearchEntry> { it.songs.size }
                        .thenBy { it.title.lowercase() }
                )
            }
            if (session.sortDescending) sorted.asReversed() else sorted
        }
    }
    val visibleDimensions = dimensions.filter { dimension ->
        dimension.scope in enabledScopes && results[dimension.scope].orEmpty().isNotEmpty()
    }
    val shuffleCandidates = remember(results, enabledScopes) {
        enabledScopes.asSequence()
            .flatMap { results[it].orEmpty().asSequence() }
            .flatMap { it.songs.asSequence() }
            .distinctBy { it.path }
            .toList()
    }
    val flattenedResults = remember(results, visibleDimensions) {
        visibleDimensions.flatMap { dimension -> results[dimension.scope].orEmpty() }
    }
    val resultItems = remember(flattenedResults) { flattenedResults.map(::SearchPowerListItem) }
    val sectionHeaders = remember(results, visibleDimensions) {
        var itemOffset = 0
        visibleDimensions.map { dimension ->
            val count = results[dimension.scope].orEmpty().size
            PowerListSectionHeader(
                stableKey = dimension.scope.token,
                beforeItemIndex = itemOffset,
                title = dimension.label,
                count = count
            ).also { itemOffset += count }
        }
    }
    val restoreResultIndex = remember(results, visibleDimensions) {
        var itemOffset = 0
        var restored = -1
        for (dimension in visibleDimensions) {
            val entries = results[dimension.scope].orEmpty()
            val focusedKey = session.focusedKey(dimension.scope)
            val localIndex = focusedKey
                ?.let { key -> entries.indexOfFirst { it.key == key } }
                ?.takeIf { it >= 0 }
                ?: session.focusedIndex(dimension.scope)
            if (restored < 0 && localIndex in entries.indices) restored = itemOffset + localIndex
            itemOffset += entries.size
        }
        restored
    }

    fun saveCurrentQuery() {
        if (session.query.isNotBlank()) history = historyStore.add(session.query)
    }

    LaunchedEffect(restoreResultIndex, flattenedResults) {
        if (restoreResultIndex in flattenedResults.indices) {
            powerListState.requestScrollToIndex(restoreResultIndex)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
            .statusBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .zIndex(10f)
                .background(MiuixTheme.colorScheme.background)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 8.dp, end = 14.dp, top = 6.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Back,
                        contentDescription = stringResource(R.string.global_search_back),
                        tint = MiuixTheme.colorScheme.onBackground
                    )
                }
                SearchBar(
                    inputField = {
                        InputField(
                            query = session.query,
                            onQueryChange = session::updateQuery,
                            onSearch = { saveCurrentQuery() },
                            expanded = false,
                            onExpandedChange = {},
                            label = stringResource(R.string.global_search_hint)
                        )
                    },
                    onExpandedChange = {},
                    modifier = Modifier.weight(1f),
                    expanded = false,
                    content = {}
                )
            }

            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .systemGestureExclusion(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(shownDimensions, key = { it.scope.token }) { dimension ->
                    val selected = dimension.scope in session.activeScopes
                    SearchFilterBubble(
                        label = dimension.label,
                        selected = selected,
                        showClose = session.entryScopeMode && selected,
                        onClick = {
                            if (session.entryScopeMode) {
                                session.updateFilters(emptySet(), entryMode = false)
                            } else {
                                val updated = if (selected) {
                                    session.activeScopes - dimension.scope
                                } else if (session.activeScopes.isEmpty()) {
                                    setOf(dimension.scope)
                                } else {
                                    session.activeScopes + dimension.scope
                                }
                                session.updateFilters(updated, entryMode = false)
                            }
                        }
                    )
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        saveCurrentQuery()
                        onShuffle(shuffleCandidates.shuffled())
                    },
                    enabled = shuffleCandidates.isNotEmpty()
                ) {
                    Icon(
                        painter = painterResource(com.rawsmusic.core.ui.R.drawable.ic_shuffle_custom),
                        contentDescription = stringResource(R.string.global_search_shuffle),
                        tint = if (shuffleCandidates.isNotEmpty()) {
                            MiuixTheme.colorScheme.onBackground
                        } else {
                            MiuixTheme.colorScheme.onSurfaceVariantSummary
                        },
                        modifier = Modifier.size(22.dp)
                    )
                }
                IconButton(onClick = { showSortDialog = true }) {
                    Icon(
                        imageVector = MiuixIcons.Regular.Sort,
                        contentDescription = stringResource(R.string.global_search_sort),
                        tint = MiuixTheme.colorScheme.onBackground,
                        modifier = Modifier.size(23.dp)
                    )
                }
            }
        }

        if (session.query.isBlank()) {
            SearchHistory(
                history = history,
                onSelect = session::updateQuery,
                onClear = {
                    historyStore.clear()
                    history = emptyList()
                },
                modifier = Modifier.weight(1f)
            )
        } else if (visibleDimensions.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.global_search_no_results),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 15.sp
                )
            }
        } else {
            ComposeGenericPowerList(
                items = resultItems,
                state = powerListState,
                sectionHeaders = sectionHeaders,
                sectionHeaderHeight = 54.dp,
                sectionHeaderContent = { header ->
                    SearchSectionHeader(label = header.title, count = header.count)
                },
                contentBottomPadding = 112.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 14.dp),
                onItemClick = { item, _, _ ->
                    val entry = (item as? SearchPowerListItem)?.entry ?: return@ComposeGenericPowerList
                    val localIndex = results[entry.scope].orEmpty().indexOf(entry)
                    saveCurrentQuery()
                    session.saveFocusedEntry(entry.scope, entry.key, localIndex)
                    entry.song?.let(onSongClick)
                        ?: onCategoryClick(entry.scope, entry.key)
                }
            )
        }
    }

    GlobalSearchSortLayoutDialog(
        visible = showSortDialog,
        session = session,
        powerListState = powerListState,
        onDismiss = { showSortDialog = false }
    )
}

@Composable
private fun SearchFilterBubble(
    label: String,
    selected: Boolean,
    showClose: Boolean,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.16f) else scheme.surface)
            .clickable(onClick = onClick)
            .padding(
                start = 14.dp,
                end = if (showClose) 9.dp else 14.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = if (selected) scheme.primary else scheme.onBackground,
            fontSize = 13.sp,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal
        )
        if (showClose) {
            Spacer(Modifier.width(5.dp))
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = stringResource(R.string.global_search_remove_filter),
                tint = scheme.primary,
                modifier = Modifier.size(15.dp)
            )
        }
    }
}

@Composable
private fun SearchHistory(
    history: List<String>,
    onSelect: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scheme = MiuixTheme.colorScheme
    LazyColumn(
        modifier = modifier.fillMaxWidth().navigationBarsPadding(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp, vertical = 8.dp)
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                SearchSectionBubble(
                    label = stringResource(R.string.global_search_history),
                    count = history.size
                )
                Spacer(Modifier.weight(1f))
                if (history.isNotEmpty()) {
                    IconButton(onClick = onClear) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = stringResource(R.string.global_search_clear_history),
                            tint = scheme.onSurfaceVariantSummary,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }
        }
        if (history.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.global_search_no_history),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(top = 28.dp)
                )
            }
        } else {
            items(history, key = { it }) { item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .clickable { onSelect(item) }
                        .padding(vertical = 13.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(com.rawsmusic.core.ui.R.drawable.ic_schedule),
                        contentDescription = null,
                        tint = scheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = item,
                        color = scheme.onBackground,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun SearchSectionHeader(
    label: String,
    count: Int
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = scheme.onBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = count.toString(),
            color = scheme.onSurfaceVariantSummary,
            fontSize = 11.sp
        )
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .height(1.dp)
                .background(scheme.onSurfaceVariantSummary.copy(alpha = 0.24f))
        )
    }
}

@Composable
private fun GlobalSearchSortLayoutDialog(
    visible: Boolean,
    session: GlobalSearchSessionState,
    powerListState: ComposePowerListState,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val scheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    val scrollState = rememberScrollState()
    RawMiuixOverlayDialog(
        show = true,
        title = stringResource(R.string.global_search_sort_title),
        backgroundColor = scheme.surface,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 520.dp)
                .verticalScroll(scrollState)
        ) {
            SearchDialogSectionTitle(stringResource(R.string.global_search_sort_section))
            SearchDialogRow(
                title = stringResource(R.string.global_search_sort_relevance),
                selected = session.sortMode == GlobalSearchSortMode.RELEVANCE
            ) { session.updateSort(GlobalSearchSortMode.RELEVANCE) }
            SearchDialogRow(
                title = stringResource(R.string.global_search_sort_name),
                selected = session.sortMode == GlobalSearchSortMode.NAME
            ) { session.updateSort(GlobalSearchSortMode.NAME) }
            SearchDialogRow(
                title = stringResource(R.string.global_search_sort_song_count),
                selected = session.sortMode == GlobalSearchSortMode.SONG_COUNT
            ) { session.updateSort(GlobalSearchSortMode.SONG_COUNT) }
            SearchDialogRow(
                title = stringResource(R.string.global_search_sort_reverse),
                selected = session.sortDescending
            ) { session.toggleSortDirection() }

            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 10.dp)
                    .height(1.dp)
                    .background(scheme.onSurfaceVariantSummary.copy(alpha = 0.18f))
            )

            SearchDialogSectionTitle(stringResource(R.string.global_search_layout_section))
            SearchDialogRow(
                title = stringResource(R.string.global_search_layout_compact),
                selected = !powerListState.isGrid && powerListState.currentLevel == ListZoomIndex.SMALL
            ) { scope.launch { powerListState.snapToLevel(ListZoomIndex.SMALL) } }
            SearchDialogRow(
                title = stringResource(R.string.global_search_layout_standard),
                selected = !powerListState.isGrid && powerListState.currentLevel == ListZoomIndex.NORMAL
            ) { scope.launch { powerListState.snapToLevel(ListZoomIndex.NORMAL) } }
            SearchDialogRow(
                title = stringResource(R.string.global_search_layout_large),
                selected = !powerListState.isGrid && powerListState.currentLevel == ListZoomIndex.ZOOMED
            ) { scope.launch { powerListState.snapToLevel(ListZoomIndex.ZOOMED) } }
            SearchDialogRow(
                title = stringResource(R.string.global_search_layout_grid_4),
                selected = powerListState.columns == 4
            ) { scope.launch { powerListState.snapToColumns(4) } }
            SearchDialogRow(
                title = stringResource(R.string.global_search_layout_grid_3),
                selected = powerListState.columns == 3
            ) { scope.launch { powerListState.snapToColumns(3) } }
            SearchDialogRow(
                title = stringResource(R.string.global_search_layout_grid_2),
                selected = powerListState.columns == 2
            ) { scope.launch { powerListState.snapToColumns(2) } }
        }
    }
}

@Composable
private fun SearchDialogSectionTitle(title: String) {
    Text(
        text = title,
        color = MiuixTheme.colorScheme.onBackground,
        fontSize = 18.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
    )
}

@Composable
private fun SearchDialogRow(
    title: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    val scheme = MiuixTheme.colorScheme
    Text(
        text = title,
        color = if (selected) scheme.primary else scheme.onBackground,
        fontSize = 15.sp,
        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        modifier = Modifier
            .fillMaxWidth()
            .height(44.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) scheme.primary.copy(alpha = 0.10f) else androidx.compose.ui.graphics.Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 11.dp)
    )
}

private fun SearchEntry.relevanceRank(query: String): Int {
    if (query.isBlank()) return 0
    val normalizedTitle = title.lowercase()
    val normalizedSubtitle = subtitle.lowercase()
    return when {
        normalizedTitle == query -> 0
        normalizedTitle.startsWith(query) -> 1
        query in normalizedTitle -> 2
        normalizedSubtitle.startsWith(query) -> 3
        query in normalizedSubtitle -> 4
        else -> 5
    }
}

@Composable
private fun SearchSectionBubble(label: String, count: Int) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(18.dp))
            .background(scheme.surface)
            .padding(horizontal = 13.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            color = scheme.onBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.width(7.dp))
        Text(
            text = count.toString(),
            color = scheme.onSurfaceVariantSummary,
            fontSize = 11.sp
        )
    }
}

private fun buildCatalog(
    songs: List<AudioFile>,
    labels: Map<GlobalSearchScope, String>,
    unknownAlbum: String,
    unknownArtist: String,
    unknownGenre: String,
    unknownYear: String,
    unknownComposer: String,
    musicLabel: String,
    songCountLabel: (Int) -> String
): Map<GlobalSearchScope, List<SearchEntry>> {
    fun coverFor(group: List<AudioFile>): String {
        return group.firstOrNull { it.coverKey.isNotBlank() }?.coverKey.orEmpty()
    }

    fun entry(
        scope: GlobalSearchScope,
        key: String,
        title: String,
        subtitle: String,
        group: List<AudioFile>
    ) = SearchEntry(
        scope = scope,
        key = key,
        title = title,
        subtitle = subtitle,
        meta = songCountLabel(group.size),
        coverKey = coverFor(group),
        songs = group
    )

    val albums = songs.groupBy { song ->
        val artist = song.albumArtist.ifBlank { song.artist }.ifBlank { unknownArtist }
        val album = song.album.ifBlank { unknownAlbum }
        "$artist$ALBUM_KEY_SEPARATOR$album"
    }.map { (key, group) ->
        entry(
            GlobalSearchScope.ALBUM,
            key,
            key.substringAfter(ALBUM_KEY_SEPARATOR),
            key.substringBefore(ALBUM_KEY_SEPARATOR),
            group
        )
    }.sortedBy { it.title.lowercase() }

    val artists = songs.groupBy {
        it.artist.ifBlank { it.albumArtist }.ifBlank { unknownArtist }
    }.map { (key, group) ->
        entry(GlobalSearchScope.ARTIST, key, key, labels[GlobalSearchScope.ARTIST].orEmpty(), group)
    }.sortedBy { it.title.lowercase() }

    val folders = songs.asSequence()
        .filter { it.path.isNotBlank() }
        .groupBy { File(it.path).parentFile?.absolutePath.orEmpty() }
        .filterKeys { it.isNotBlank() }
        .map { (path, group) ->
            val folder = File(path)
            entry(
                GlobalSearchScope.FOLDER,
                path,
                folder.name.ifBlank { path },
                folder.parentFile?.name.orEmpty(),
                group
            )
        }.sortedBy { it.title.lowercase() }

    val genres = songs.groupBy { it.genre.trim().ifBlank { unknownGenre } }
        .map { (key, group) ->
            entry(GlobalSearchScope.GENRE, key, key, labels[GlobalSearchScope.GENRE].orEmpty(), group)
        }.sortedBy { it.title.lowercase() }

    val years = songs.groupBy {
        it.year.takeIf { year -> year > 0 }?.toString() ?: unknownYear
    }.map { (key, group) ->
        entry(GlobalSearchScope.YEAR, key, key, labels[GlobalSearchScope.YEAR].orEmpty(), group)
    }.sortedByDescending { it.key.toIntOrNull() ?: Int.MIN_VALUE }

    val composers = songs.groupBy { it.composer.trim().ifBlank { unknownComposer } }
        .map { (key, group) ->
            entry(GlobalSearchScope.COMPOSER, key, key, labels[GlobalSearchScope.COMPOSER].orEmpty(), group)
        }.sortedBy { it.title.lowercase() }

    val songEntries = songs.map { song ->
        SearchEntry(
            scope = GlobalSearchScope.SONG,
            key = "${song.id}_${song.path}",
            title = song.displayName.ifBlank { File(song.path).nameWithoutExtension },
            subtitle = song.artist.ifBlank { song.album.ifBlank { musicLabel } },
            meta = song.album,
            coverKey = song.coverKey,
            songs = listOf(song),
            song = song
        )
    }

    return linkedMapOf(
        GlobalSearchScope.ALBUM to albums,
        GlobalSearchScope.ARTIST to artists,
        GlobalSearchScope.FOLDER to folders,
        GlobalSearchScope.GENRE to genres,
        GlobalSearchScope.YEAR to years,
        GlobalSearchScope.COMPOSER to composers,
        GlobalSearchScope.SONG to songEntries
    )
}
