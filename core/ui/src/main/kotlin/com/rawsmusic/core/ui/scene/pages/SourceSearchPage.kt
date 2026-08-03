package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.source.RawSourceMediaItem
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.ui.R
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.InstalledMusicSource
import com.rawsmusic.module.data.source.runtime.AggregatedMusicSourceSearch
import com.rawsmusic.module.data.source.runtime.MusicSourceSearchCoordinator
import com.rawsmusic.module.data.source.runtime.MusicSourceSearchStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
internal fun SourceSearchPage(
    installedSources: List<InstalledMusicSource>,
    installedLxSources: List<InstalledLxSource>,
    artworkPaths: Map<String, String>,
    currentPlayingIdentity: String?,
    onBack: () -> Unit,
    onSelectItem: (RawSourceMediaItem, List<RawSourceMediaItem>) -> Unit,
    onDownload: (RawSourceMediaItem, RawSourceQuality) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val latest = remember { MusicSourceSearchStore.latest() }
    var query by rememberSaveable { mutableStateOf(latest?.query.orEmpty()) }
    var result by remember { mutableStateOf(latest?.result ?: AggregatedMusicSourceSearch()) }
    var searching by remember { mutableStateOf(false) }
    var searchError by rememberSaveable { mutableStateOf("") }
    var pendingDownloadItem by remember { mutableStateOf<RawSourceMediaItem?>(null) }
    val history by MusicSourceSearchStore.history.collectAsState()
    val enabledCount = installedSources.count { it.enabled } + installedLxSources.count { it.enabled }

    fun search(targetQuery: String = query) {
        val normalized = targetQuery.trim()
        if (normalized.isBlank() || searching) return
        scope.launch {
            searching = true
            searchError = ""
            try {
                val loaded = MusicSourceSearchCoordinator.search(
                    context = context,
                    sources = installedSources,
                    lxSources = installedLxSources,
                    query = normalized,
                )
                result = loaded
                MusicSourceSearchStore.remember(normalized, loaded)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Throwable) {
                searchError = error.message.orEmpty().ifBlank { context.getString(R.string.source_search_failed) }
            } finally {
                searching = false
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        SourceSearchTopBar(
            query = query,
            sourceLabel = if (enabledCount > 0) context.getString(R.string.source_aggregate_count, enabledCount)
            else context.getString(R.string.source_aggregate_label),
            onQueryChange = { next ->
                query = next
                searchError = ""
                // Never keep showing results that belong to a different or now-empty query.
                result = MusicSourceSearchStore.resultFor(next) ?: AggregatedMusicSourceSearch()
            },
            onSearch = ::search,
            onBack = onBack,
        )

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 174.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (history.isNotEmpty()) {
                item(key = "history-title") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            stringResource(R.string.source_search_history_title),
                            color = MiuixTheme.colorScheme.onBackground,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        Text(
                            stringResource(R.string.common_clear),
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 12.sp,
                            modifier = Modifier.clickable { MusicSourceSearchStore.clearHistory() },
                        )
                    }
                }
                item(key = "history-list") {
                    Column {
                        history.take(15).forEach { keyword ->
                            SearchHistoryRow(
                                keyword = keyword,
                                onClick = {
                                    query = keyword
                                    result = MusicSourceSearchStore.resultFor(keyword) ?: AggregatedMusicSourceSearch()
                                    if (result.items.isEmpty()) search(keyword)
                                },
                                onRemove = { MusicSourceSearchStore.removeHistory(keyword) },
                            )
                        }
                    }
                }
            }

            item(key = "result-header") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.source_search_results_title),
                        color = MiuixTheme.colorScheme.onBackground,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(1f),
                    )
                    if (searching) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        if (result.groups.isNotEmpty() || searchError.isNotBlank()) {
                            Text(
                                text = stringResource(R.string.common_clear),
                                color = MiuixTheme.colorScheme.primary,
                                fontSize = 12.sp,
                                modifier = Modifier
                                    .clickable {
                                        result = AggregatedMusicSourceSearch()
                                        searchError = ""
                                        MusicSourceSearchStore.clearResults()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = result.items.size.toString(),
                            color = MiuixTheme.colorScheme.primary,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            if (searchError.isNotBlank()) {
                item(key = "global-error") { SourceRuntimeErrorCard(stringResource(R.string.source_search_group_title), searchError) }
            }

            result.errors.forEach { group ->
                item(key = "error-${group.sourceId}") {
                    SourceRuntimeErrorCard(group.sourceName, group.error)
                }
            }

            val sourceNames = result.groups
                .flatMap { group ->
                    group.items.map { item -> item.stableIdentity to group.sourceName }
                }
                .toMap()
            items(result.items, key = { it.stableIdentity }) { item ->
                SourceSearchResultCard(
                    item = item,
                    sourceName = sourceNames[item.stableIdentity].orEmpty().ifBlank { stringResource(R.string.source_search_online_source) },
                    localArtworkPath = artworkPaths[item.stableIdentity],
                    selected = currentPlayingIdentity == item.stableIdentity,
                    onClick = { onSelectItem(item, result.items) },
                    onDownload = { pendingDownloadItem = item },
                )
            }

            if (!searching && result.items.isEmpty() && result.errors.isEmpty()) {
                item(key = "empty") {
                    SourceEmptyPanel(
                        title = if (query.isBlank()) stringResource(R.string.source_search_input_hint)
                        else stringResource(R.string.source_search_no_results),
                        summary = if (query.isBlank()) stringResource(R.string.source_search_history_summary)
                        else stringResource(R.string.source_search_no_results_summary),
                    )
                }
            }
        }
    }

    pendingDownloadItem?.let { item ->
        SourceQualitySelectionDialog(
            title = stringResource(R.string.source_quality_download_title),
            qualities = item.availableQualities,
            selectedQuality = null,
            summary = stringResource(R.string.source_quality_download_summary, item.title),
            onDismiss = { pendingDownloadItem = null },
            onSelect = { quality ->
                pendingDownloadItem = null
                onDownload(item, quality)
            },
        )
    }
}

@Composable
private fun SearchHistoryRow(keyword: String, onClick: () -> Unit, onRemove: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = MiuixIcons.Regular.Search,
            contentDescription = null,
            tint = scheme.onSurfaceVariantSummary,
            modifier = Modifier.size(19.dp),
        )
        Spacer(Modifier.width(12.dp))
        Text(
            keyword,
            color = scheme.onBackground,
            fontSize = 14.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onRemove, modifier = Modifier.size(38.dp)) {
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = stringResource(R.string.source_delete_history),
                tint = scheme.onSurfaceVariantSummary,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

@Composable
private fun SourceSearchResultCard(
    item: RawSourceMediaItem,
    sourceName: String,
    localArtworkPath: String?,
    selected: Boolean,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SourceArtwork(
            item = item,
            localPath = localArtworkPath,
            modifier = Modifier.size(64.dp),
            targetSize = 384,
            cornerRadiusDp = 17,
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                item.title,
                color = if (selected) scheme.primary else scheme.onBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                buildString {
                    append(item.artists.joinToString(" / ").ifBlank { "未知歌手" })
                    if (item.album.isNotBlank()) append(" · ${item.album}")
                },
                color = scheme.onSurfaceVariantSummary,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            Text(sourceName, color = scheme.primary, fontSize = 11.sp, maxLines = 1)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (item.durationMs > 0L) {
                val seconds = item.durationMs / 1_000L
                Text(
                    "%d:%02d".format(seconds / 60, seconds % 60),
                    color = scheme.onSurfaceVariantSummary,
                    fontSize = 11.sp,
                )
                Spacer(Modifier.height(6.dp))
            }
            IconButton(
                onClick = onDownload,
                modifier = Modifier.size(42.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_source_download),
                    contentDescription = stringResource(R.string.source_download),
                    tint = scheme.primary,
                    modifier = Modifier.size(21.dp),
                )
            }
        }
    }
}

@Composable
internal fun SourceRuntimeErrorCard(sourceName: String, message: String) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFFE15B64).copy(alpha = 0.10f))
            .padding(horizontal = 16.dp, vertical = 13.dp),
    ) {
        Text(sourceName, color = scheme.onBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(message, color = Color(0xFFE15B64), fontSize = 12.sp, lineHeight = 17.sp)
    }
}

@Composable
internal fun SourceEmptyPanel(title: String, summary: String, modifier: Modifier = Modifier) {
    val scheme = MiuixTheme.colorScheme
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(scheme.surfaceContainer.copy(alpha = 0.68f))
            .padding(horizontal = 24.dp, vertical = 30.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(R.drawable.ic_source_download),
            contentDescription = null,
            colorFilter = ColorFilter.tint(scheme.primary),
            modifier = Modifier.size(42.dp),
        )
        Spacer(Modifier.height(14.dp))
        Text(title, color = scheme.onBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(7.dp))
        Text(summary, color = scheme.onSurfaceVariantSummary, fontSize = 13.sp, lineHeight = 19.sp)
    }
}
