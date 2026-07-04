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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.zIndex
import androidx.compose.ui.unit.dp
import com.rawsmusic.core.common.model.AudioFile
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
    powerListState: ComposePowerListState = rememberComposePowerListState("genres"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("genre_detail_songs")
    val groups by remember(songs) {
        derivedStateOf { songs.toGenreGroups() }
    }

    if (selectedGenreKey.isNullOrBlank()) {
        CategoryListPage(
            groups = groups,
            listScene = NavScene.GENRE,
            indexMode = RawIndexMode.AUTO,
            state = powerListState,
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
    powerListState: ComposePowerListState = rememberComposePowerListState("years"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("year_detail_songs")
    val groups by remember(songs) {
        derivedStateOf { songs.toYearGroups() }
    }

    if (selectedYearKey.isNullOrBlank()) {
        CategoryListPage(
            groups = groups,
            listScene = NavScene.YEAR,
            indexMode = RawIndexMode.LATIN,
            yearIndex = true,
            state = powerListState,
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
    powerListState: ComposePowerListState = rememberComposePowerListState("composers"),
    modifier: Modifier = Modifier
) {
    val detailState = rememberComposePowerListState("composer_detail_songs")
    val groups by remember(songs) {
        derivedStateOf { songs.toComposerGroups() }
    }

    if (selectedComposerKey.isNullOrBlank()) {
        CategoryListPage(
            groups = groups,
            listScene = NavScene.COMPOSER,
            indexMode = RawIndexMode.AUTO,
            state = powerListState,
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
    toItem: (CategoryGroupUi) -> T,
    onGroupClick: (String) -> Unit,
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MiuixTheme.colorScheme.background)
    ) {
        ComposeGenericPowerList(
            items = items,
            state = state,
            sharedCoverSceneId = listScene.name,
            modifier = Modifier.fillMaxSize(),
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
                .zIndex(30f),
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

        if (labels.isEmpty()) {
            labels.add("#")
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
            meta = "♫ $songCount | ${formatPowerListDuration(totalDurationMs)}",
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
                coverKey = coverSong?.albumArtPath?.ifBlank { coverSong.path }.orEmpty(),
                totalDurationMs = categorySongs.sumOf { it.duration.coerceAtLeast(0L) }
            )
        }
}
