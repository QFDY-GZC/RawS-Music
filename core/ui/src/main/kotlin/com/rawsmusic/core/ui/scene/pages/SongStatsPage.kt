package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import android.net.Uri
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.luminance
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListCustomProvider
import com.rawsmusic.core.ui.widget.virtuallist.ComposeGenericVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.SongVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.rememberComposeVirtualListState
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.icon.extended.Filter
import top.yukonga.miuix.kmp.icon.extended.Sort
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 统一的曲库分析页面。
 *
 * 所有分析维度共用这一个页面、这一个图表和这一个详情列表；维度只是数据投影，
 * 不再为 MP3/FLAC/无损等分类创建独立页面，从而可以继续复用 LibraryListScaffold 的
 * LayoutRes/顶部玻璃层/场景转场以及 VirtualList 的歌曲列表动画。
 */
internal class LibraryAnalysisPageState {
    var dimension by mutableStateOf(LibraryAnalysisDimension.FORMAT)
    var metric by mutableStateOf(LibraryAnalysisMetric.SIZE)
    var snapshot by mutableStateOf<LibraryAnalysisSnapshot?>(null)
    var snapshotSongs: List<AudioFile>? = null
}

@Composable
internal fun SongStatsPage(
    pageState: LibraryAnalysisPageState,
    bucketArgument: String?,
    rootListState: ComposeVirtualListState,
    detailListState: ComposeVirtualListState,
    onOpenBucket: (String) -> Unit,
    songs: List<AudioFile> = emptyList(),
    currentPlayingId: Long = -1L,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit = { _, _ -> },
    onSongLongClick: (AudioFile, Int) -> Unit = { _, _ -> },
    onShuffle: (List<AudioFile>) -> Unit = {},
) {
    val inDetail = bucketArgument != null
    val selectedBucketKey = bucketArgument?.substringAfter(':')?.let(Uri::decode)
    var dimension by pageState::dimension
    var metric by pageState::metric
    val activeDimension = bucketArgument?.substringBefore(':')?.let { key ->
        LibraryAnalysisDimension.entries.firstOrNull { it.name == key }
    } ?: dimension
    var filterMenuVisible by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }
    var sortMenuVisible by remember { mutableStateOf(false) }
    var sortField by remember { mutableStateOf(LibraryAnalysisSort.TITLE) }
    var sortDescending by remember { mutableStateOf(false) }

    LaunchedEffect(songs) {
        if (pageState.snapshotSongs !== songs) {
            val snapshot = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                buildLibraryAnalysis(songs)
            }
            pageState.snapshotSongs = songs
            pageState.snapshot = snapshot
        }
    }
    val analysis = pageState.snapshot
    val buckets = remember(analysis, activeDimension) {
        analysis?.bucketsFor(activeDimension).orEmpty()
    }
    val displayBuckets = remember(buckets) { collapseAnalysisBuckets(buckets) }
    val selectedBucket = remember(displayBuckets, selectedBucketKey) {
        displayBuckets.firstOrNull { it.key == selectedBucketKey }
    }
    val detailSongs = remember(selectedBucket) { selectedBucket?.songs.orEmpty() }
    val filteredDetailSongs = remember(detailSongs, searchQuery, sortField, sortDescending) {
        val query = searchQuery.trim()
        val filtered = if (query.isBlank()) detailSongs else detailSongs.filter { song ->
            song.displayName.contains(query, ignoreCase = true) ||
                song.artist.contains(query, ignoreCase = true) ||
                song.album.contains(query, ignoreCase = true) ||
                song.path.substringAfterLast('/').contains(query, ignoreCase = true)
        }
        val sorted = when (sortField) {
            LibraryAnalysisSort.TITLE -> filtered.sortedBy { it.displayName.lowercase() }
            LibraryAnalysisSort.ARTIST -> filtered.sortedBy { it.artist.lowercase() }
            LibraryAnalysisSort.ALBUM -> filtered.sortedBy { it.album.lowercase() }
            LibraryAnalysisSort.SIZE -> filtered.sortedBy { it.fileSize }
            LibraryAnalysisSort.DURATION -> filtered.sortedBy { it.duration }
        }
        if (sortDescending) sorted.asReversed() else sorted
    }
    val detailItems = remember(filteredDetailSongs) {
        filteredDetailSongs.map(::SongVirtualListItem)
    }
    val scheme = MiuixTheme.colorScheme
    val totalCount = analysis?.totalCount ?: songs.size
    val totalSize = analysis?.totalSizeBytes ?: songs.sumOf { it.fileSize }
    val pageTitle = selectedBucket?.let { analysisDisplayLabel(it.key) }
        ?: stringResource(R.string.library_analysis_title)

    LibraryListScaffold(
        title = pageTitle,
        sceneId = if (inDetail) NavScene.LIBRARY_ANALYSIS_DETAIL.name else NavScene.SONG_STATS.name,
        statisticsText = if (inDetail) stringResource(R.string.library_analysis_detail_summary,
            filteredDetailSongs.size, formatPercentOfLibrary(filteredDetailSongs.size, totalCount),
            formatLibraryFileSize(selectedBucket?.sizeBytes ?: 0L)) else "",
        onBack = onBack,
        virtualListState = if (inDetail) detailListState else rootListState,
        onShuffle = if (inDetail) null else ({ onShuffle(songs) }),
        onHeaderSearch = if (inDetail) ({ searchActive = !searchActive }) else null,
        headerSearchActive = inDetail && searchActive,
        headerSearchQuery = searchQuery,
        onHeaderSearchQueryChange = { searchQuery = it },
        onHeaderSearchCancel = {
            searchActive = false
            searchQuery = ""
        },
        showHeaderMore = false,
        showHeaderSearch = inDetail,
        showHeaderShuffle = false,
        headerTrailingContent = {
            if (inDetail) {
                Box {
                    IconButton(onClick = { sortMenuVisible = true }, modifier = Modifier.size(42.dp)) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Sort,
                            contentDescription = stringResource(R.string.library_analysis_sort),
                            tint = scheme.onSurface,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = sortMenuVisible,
                        onDismissRequest = { sortMenuVisible = false }
                    ) {
                        Text(
                            text = stringResource(R.string.library_analysis_sort),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LibraryAnalysisSort.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(analysisSortLabel(option)) },
                                onClick = {
                                    if (sortField == option) {
                                        sortDescending = !sortDescending
                                    } else {
                                        sortField = option
                                        sortDescending = false
                                    }
                                    sortMenuVisible = false
                                }
                            )
                        }
                    }
                }
            } else {
                Box {
                    IconButton(onClick = { filterMenuVisible = true }, modifier = Modifier.size(42.dp)) {
                        Icon(
                            imageVector = MiuixIcons.Regular.Filter,
                            contentDescription = stringResource(R.string.library_analysis_filter),
                            tint = scheme.onSurface,
                            modifier = Modifier.size(23.dp)
                        )
                    }
                    DropdownMenu(
                        expanded = filterMenuVisible,
                        onDismissRequest = { filterMenuVisible = false }
                    ) {
                        Text(
                            text = stringResource(R.string.library_analysis_dimension),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LibraryAnalysisDimension.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(analysisDimensionLabel(option)) },
                                onClick = {
                                    dimension = option
                                    filterMenuVisible = false
                                }
                            )
                        }
                        Text(
                            text = stringResource(R.string.library_analysis_metric),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = scheme.onSurfaceVariantSummary,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                        LibraryAnalysisMetric.entries.forEach { option ->
                            DropdownMenuItem(
                                text = { Text(analysisMetricLabel(option)) },
                                onClick = {
                                    metric = option
                                    filterMenuVisible = false
                                }
                            )
                        }
                    }
                }
            }
        },
    ) { contentTopPadding, backdropSource ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(backdropSource)
        ) {
            if (inDetail) {
                LibraryAnalysisDetail(
                    topPadding = contentTopPadding + if (searchActive) 48.dp else 0.dp,
                    songs = filteredDetailSongs,
                    items = detailItems,
                    listState = detailListState,
                    currentPlayingId = currentPlayingId,
                    onPlayQueue = onPlayQueue,
                    onSongLongClick = onSongLongClick,
                )
            } else {
                LibraryAnalysisDashboard(
                    topPadding = contentTopPadding,
                    songs = songs,
                    totalCount = totalCount,
                    totalSizeBytes = totalSize,
                    dimension = dimension,
                    metric = metric,
                    buckets = displayBuckets,
                    analysisReady = analysis != null,
                    listState = rootListState,
                    onBucketSelected = { onOpenBucket("${dimension.name}:${Uri.encode(it.key)}") },
                    onBucketLongClick = { onOpenBucket("${dimension.name}:${Uri.encode(it.key)}") },
                )
            }
        }
    }
}

internal enum class LibraryAnalysisDimension {
    FORMAT,
    QUALITY,
    SAMPLE_RATE,
    BIT_DEPTH,
}

internal enum class LibraryAnalysisMetric {
    SIZE,
    COUNT,
}

private enum class LibraryAnalysisSort {
    TITLE,
    ARTIST,
    ALBUM,
    SIZE,
    DURATION,
}

internal data class LibraryAnalysisBucket(
    val key: String,
    val count: Int,
    val sizeBytes: Long,
    val songs: List<AudioFile>,
)

internal data class LibraryAnalysisSnapshot(
    val byDimension: Map<LibraryAnalysisDimension, List<LibraryAnalysisBucket>>,
    val totalCount: Int,
    val totalSizeBytes: Long,
) {
    fun bucketsFor(dimension: LibraryAnalysisDimension): List<LibraryAnalysisBucket> =
        byDimension[dimension].orEmpty()
}

@Composable
private fun LibraryAnalysisDashboard(
    topPadding: androidx.compose.ui.unit.Dp,
    songs: List<AudioFile>,
    totalCount: Int,
    totalSizeBytes: Long,
    dimension: LibraryAnalysisDimension,
    metric: LibraryAnalysisMetric,
    buckets: List<LibraryAnalysisBucket>,
    analysisReady: Boolean,
    listState: ComposeVirtualListState,
    onBucketSelected: (LibraryAnalysisBucket) -> Unit,
    onBucketLongClick: (LibraryAnalysisBucket) -> Unit,
) {
    val holderItems = remember { listOf(AudioFile(id = -9_300_001L, title = "Library analysis", encodingFormat = "library_analysis_chart")) }
    val height = (56.dp * buckets.size).coerceIn(300.dp, 440.dp) + 84.dp
    val customProvider = remember(songs, totalCount, totalSizeBytes, dimension, metric, buckets, analysisReady, onBucketSelected, onBucketLongClick) {
        VirtualListCustomProvider(
            itemHeight = { _, _ -> height },
            handles = { item, _ -> item.id == -9_300_001L },
            content = { _, _, modifier ->
                Column(modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        text = stringResource(R.string.library_analysis_summary, totalCount, formatLibraryFileSize(totalSizeBytes)),
                        fontSize = 13.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                        modifier = Modifier.padding(horizontal = 4.dp),
                    )
                    LibraryAnalysisCylinderCard(
                        title = analysisDimensionLabel(dimension),
                        buckets = buckets,
                        total = totalCount,
                        totalSizeBytes = totalSizeBytes,
                        metric = metric,
                        loading = !analysisReady && songs.isNotEmpty(),
                        onBucketClick = onBucketSelected,
                        onBucketLongClick = onBucketLongClick,
                    )
                }
            },
        )
    }
    CompositionLocalProvider(LocalVirtualListCustomProvider provides customProvider) {
        ComposeVirtualList(
            songs = holderItems,
            state = listState,
            modifier = Modifier.fillMaxSize(),
            pinchEnabled = false,
            contentTopPadding = topPadding,
            contentBottomPadding = 160.dp,
        )
    }
}

@Composable
private fun LibraryAnalysisDetail(
    topPadding: androidx.compose.ui.unit.Dp,
    songs: List<AudioFile>,
    items: List<SongVirtualListItem>,
    listState: ComposeVirtualListState,
    currentPlayingId: Long,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    onSongLongClick: (AudioFile, Int) -> Unit,
) {
    ComposeGenericVirtualList(
        items = items,
        state = listState,
        modifier = Modifier.fillMaxSize(),
        playingItemId = currentPlayingId,
        contentTopPadding = topPadding,
        contentBottomPadding = 128.dp,
        onItemClick = { _, index, _ -> onPlayQueue(songs, index) },
        onItemLongClick = { _, index -> songs.getOrNull(index)?.let { onSongLongClick(it, index) } },
    )
}

@Composable
private fun LibraryAnalysisCylinderCard(
    title: String,
    buckets: List<LibraryAnalysisBucket>,
    total: Int,
    totalSizeBytes: Long,
    metric: LibraryAnalysisMetric,
    loading: Boolean,
    onBucketClick: (LibraryAnalysisBucket) -> Unit,
    onBucketLongClick: (LibraryAnalysisBucket) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    Card(
        modifier = Modifier.fillMaxWidth(),
        cornerRadius = 20.dp,
        colors = CardDefaults.defaultColors(
            color = scheme.surfaceContainer.copy(alpha = 0.42f),
            contentColor = scheme.onSurface,
        ),
    ) {
        if (loading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.library_analysis_loading), color = scheme.onSurfaceVariantSummary)
            }
        } else if (buckets.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(300.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (total == 0) stringResource(R.string.library_analysis_empty)
                    else title,
                    color = scheme.onSurfaceVariantSummary,
                )
            }
        } else {
            val palette = remember(title) { analysisPalette(title) }
            val weights = remember(buckets, metric) {
                buckets.map {
                    if (metric == LibraryAnalysisMetric.SIZE) it.sizeBytes.toDouble()
                    else it.count.toDouble()
                }
            }
            val weightSum = weights.sum().coerceAtLeast(1.0)
            val rawFractions = weights.map { (it / weightSum).toFloat().coerceIn(0f, 1f) }
            val nonZeroCount = rawFractions.count { it > 0f }
            val minimum = if (nonZeroCount == 0) 0f else minOf(0.06f, 0.22f / nonZeroCount)
            val remaining = (1f - minimum * nonZeroCount).coerceAtLeast(0.1f)
            val visual = rawFractions.map { if (it > 0f) minimum + it * remaining else 0f }
            val visualSum = visual.sum().takeIf { it > 0f } ?: 1f
            val normalized = if (nonZeroCount == 0) List(buckets.size) { 1f / buckets.size }
                else visual.map { it / visualSum }
            val animatedFractions = normalized.map { fraction ->
                animateFloatAsState(
                    targetValue = fraction,
                    animationSpec = tween(450, easing = FastOutSlowInEasing),
                    label = "library-analysis-cylinder-fraction",
                ).value
            }
            val cylinderHeight = (56.dp * buckets.size).coerceIn(300.dp, 440.dp)
            var rootCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            var cylinderCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
            val dotCoordinates = remember { mutableStateMapOf<Int, LayoutCoordinates>() }
            var activeIndex by remember { mutableStateOf<Int?>(null) }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .onGloballyPositioned { rootCoordinates = it },
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AnalysisCylinder(
                        buckets = buckets,
                        fractions = animatedFractions,
                        palette = palette,
                        activeIndex = activeIndex,
                        onActiveIndexChange = { activeIndex = it },
                        modifier = Modifier
                            .width(94.dp)
                            .height(cylinderHeight)
                            .onGloballyPositioned { cylinderCoordinates = it },
                    )
                    Spacer(Modifier.width(16.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        buckets.forEachIndexed { index, bucket ->
                            val selected = activeIndex == index
                            val dotSize by animateDpAsState(
                                targetValue = if (selected) 8.5.dp else 7.dp,
                                animationSpec = tween(180),
                                label = "library-analysis-dot-size",
                            )
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .combinedClickable(
                                        onClick = { onBucketClick(bucket) },
                                        onLongClick = { onBucketLongClick(bucket) },
                                    )
                                    .padding(horizontal = 4.dp, vertical = 7.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(18.dp)
                                        .onGloballyPositioned { dotCoordinates[index] = it },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(dotSize)
                                            .clip(androidx.compose.foundation.shape.CircleShape)
                                            .background(palette[index % palette.size]),
                                    )
                                }
                                Spacer(Modifier.width(7.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        text = analysisDisplayLabel(bucket.key),
                                        fontSize = 14.sp,
                                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                                        color = if (selected) scheme.onSurface else scheme.onSurfaceVariantSummary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    val value = if (metric == LibraryAnalysisMetric.SIZE) {
                                        formatLibraryFileSize(bucket.sizeBytes)
                                    } else {
                                        stringResource(R.string.library_analysis_song_count, bucket.count)
                                    }
                                    val denominator = if (metric == LibraryAnalysisMetric.SIZE) {
                                        totalSizeBytes.toDouble().coerceAtLeast(1.0)
                                    } else {
                                        total.toDouble().coerceAtLeast(1.0)
                                    }
                                    val numerator = if (metric == LibraryAnalysisMetric.SIZE) {
                                        bucket.sizeBytes.toDouble()
                                    } else {
                                        bucket.count.toDouble()
                                    }
                                    Text(
                                        text = "$value · ${"%.1f".format(java.util.Locale.US, numerator * 100.0 / denominator)}%",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = scheme.onSurface,
                                        maxLines = 1,
                                    )
                                }
                                Text(
                                    text = "›",
                                    fontSize = 22.sp,
                                    color = scheme.onSurfaceVariantSummary.copy(alpha = if (selected) 1f else 0.55f),
                                )
                            }
                        }
                    }
                }

                Canvas(Modifier.matchParentSize()) {
                    val active = activeIndex ?: return@Canvas
                    val root = rootCoordinates ?: return@Canvas
                    val cylinder = cylinderCoordinates ?: return@Canvas
                    val dot = dotCoordinates[active] ?: return@Canvas
                    if (!root.isAttached || !cylinder.isAttached || !dot.isAttached) return@Canvas
                    val cylinderOrigin = root.localPositionOf(cylinder, Offset.Zero)
                    val dotOrigin = root.localPositionOf(dot, Offset.Zero)
                    val startY = cylinderOrigin.y + cylinderSliceCenterY(
                        index = active,
                        height = cylinder.size.height.toFloat(),
                        fractions = animatedFractions,
                        density = density,
                    )
                    val startX = cylinderOrigin.x + cylinder.size.width.toFloat() - 4.dp.toPx()
                    val targetX = dotOrigin.x + dot.size.width / 2f
                    val targetY = dotOrigin.y + dot.size.height / 2f
                    val stubX = startX + 6.dp.toPx()
                    val elbowX = (targetX - 14.dp.toPx()).coerceAtLeast(stubX + 2.dp.toPx())
                    val path = Path().apply {
                        moveTo(startX, startY)
                        lineTo(stubX, startY)
                        lineTo(elbowX, targetY)
                        lineTo(targetX, targetY)
                    }
                    drawPath(
                        path = path,
                        color = palette[active % palette.size],
                        style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisCylinder(
    buckets: List<LibraryAnalysisBucket>,
    fractions: List<Float>,
    palette: List<Color>,
    activeIndex: Int?,
    onActiveIndexChange: (Int?) -> Unit,
    modifier: Modifier,
) {
    val view = LocalView.current
    val density = androidx.compose.ui.platform.LocalDensity.current.density
    val isDark = MiuixTheme.colorScheme.background.luminance() < 0.5f
    Canvas(
        modifier = modifier.pointerInput(buckets, fractions) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                var current = cylinderSliceIndex(down.position.y, size.height.toFloat(), fractions, density)
                if (current != null) {
                    down.consume()
                    view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                    onActiveIndexChange(current)
                }
                while (true) {
                    val event = awaitPointerEvent()
                    val change = event.changes.firstOrNull() ?: break
                    if (!change.pressed) {
                        onActiveIndexChange(null)
                        break
                    }
                    if (current != null) change.consume()
                    val next = cylinderSliceIndex(change.position.y, size.height.toFloat(), fractions, density)
                    if (next != null && next != current) {
                        current = next
                        change.consume()
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK)
                        onActiveIndexChange(next)
                    }
                }
            }
        },
    ) {
        val w = size.width
        val h = size.height
        val padX = 4.dp.toPx()
        val padY = 6.dp.toPx()
        val cylWidth = w - 2 * padX
        val rx = cylWidth / 2f
        val ry = rx * 0.18f
        val cx = padX + rx
        val cylTopY = padY + ry
        val cylBotY = h - padY - ry
        val bodyHeight = (cylBotY - cylTopY).coerceAtLeast(10f)

        // Top translucent glass chamber height (~22% of total height, mirroring Xiaomi's remaining capacity look)
        val glassH = (bodyHeight * 0.22f).coerceAtLeast(16.dp.toPx())
        val coloredTotalH = bodyHeight - glassH
        val coloredTopY = cylTopY + glassH

        // 1. Ground drop shadow beneath cylinder base
        drawOval(
            brush = Brush.radialGradient(
                colors = listOf(Color.Black.copy(alpha = 0.32f), Color.Transparent),
                center = Offset(cx, cylBotY + ry * 0.35f),
                radius = rx * 1.15f
            ),
            topLeft = Offset(cx - rx * 1.15f, cylBotY - ry * 0.25f),
            size = Size(rx * 2.3f, ry * 1.6f)
        )

        // Calculate positions from TOP to BOTTOM so item 0 is at the top of the stack (matches list order!)
        val sliceCount = buckets.size
        val sliceBounds = mutableListOf<Pair<Float, Float>>()
        var currY = coloredTopY
        for (i in 0 until sliceCount) {
            val frac = fractions.getOrElse(i) { 0f }
            val sliceH = (coloredTotalH * frac).coerceAtLeast(0f)
            val yTop = currY
            val yBottom = if (i == sliceCount - 1) cylBotY else (currY + sliceH).coerceAtMost(cylBotY)
            sliceBounds.add(yTop to yBottom)
            currY = yBottom
        }

        // Draw colored slices from bottom to top (Painter's algorithm: lower slices drawn first)
        for (i in (sliceCount - 1) downTo 0) {
            val (yTop, yBottom) = sliceBounds[i]
            if (yBottom <= yTop + 0.5f) continue

            val baseColor = palette[i % palette.size]
            val lateralBrush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to baseColor.darken(0.12f),
                    0.15f to baseColor.darken(0.04f),
                    0.50f to baseColor,
                    0.85f to baseColor.darken(0.04f),
                    1.00f to baseColor.darken(0.12f)
                ),
                startX = cx - rx,
                endX = cx + rx
            )

            val slicePath = Path().apply {
                moveTo(cx - rx, yTop)
                lineTo(cx - rx, yBottom)
                arcTo(
                    rect = Rect(cx - rx, yBottom - ry, cx + rx, yBottom + ry),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = -180f,
                    forceMoveTo = false
                )
                lineTo(cx + rx, yTop)
                arcTo(
                    rect = Rect(cx - rx, yTop - ry, cx + rx, yTop + ry),
                    startAngleDegrees = 0f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false
                )
                close()
            }
            drawPath(path = slicePath, brush = lateralBrush)

            // Curved downward seam line between adjacent slices (matte soft seam)
            if (i > 0) {
                drawArc(
                    brush = Brush.horizontalGradient(
                        colorStops = arrayOf(
                            0.00f to Color.Black.copy(alpha = 0.18f),
                            0.50f to Color.Black.copy(alpha = 0.10f),
                            1.00f to Color.Black.copy(alpha = 0.20f)
                        ),
                        startX = cx - rx,
                        endX = cx + rx
                    ),
                    startAngle = 0f,
                    sweepAngle = 180f,
                    useCenter = false,
                    topLeft = Offset(cx - rx, yTop - ry),
                    size = Size(rx * 2f, ry * 2f),
                    style = Stroke(width = 0.75.dp.toPx())
                )
            }
        }

        // Draw top face oval of Slice 0 (natural matte top cap of the colored stack)
        val topColor = palette[0 % palette.size]
        drawOval(
            brush = Brush.verticalGradient(
                colors = listOf(
                    topColor.lighten(0.10f),
                    topColor
                ),
                startY = coloredTopY - ry,
                endY = coloredTopY + ry
            ),
            topLeft = Offset(cx - rx, coloredTopY - ry),
            size = Size(rx * 2f, ry * 2f)
        )
        drawOval(
            brush = Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color.Black.copy(alpha = 0.10f),
                    0.50f to Color.Black.copy(alpha = 0.04f),
                    1.00f to Color.Black.copy(alpha = 0.12f)
                ),
                startX = cx - rx,
                endX = cx + rx
            ),
            topLeft = Offset(cx - rx, coloredTopY - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = 0.75.dp.toPx())
        )

        // 3. Draw translucent empty glass chamber from cylTopY down to coloredTopY
        val glassPath = Path().apply {
            moveTo(cx - rx, cylTopY)
            lineTo(cx - rx, coloredTopY)
            arcTo(
                rect = Rect(cx - rx, coloredTopY - ry, cx + rx, coloredTopY + ry),
                startAngleDegrees = 180f,
                sweepAngleDegrees = -180f,
                forceMoveTo = false
            )
            lineTo(cx + rx, cylTopY)
            arcTo(
                rect = Rect(cx - rx, cylTopY - ry, cx + rx, cylTopY + ry),
                startAngleDegrees = 0f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            close()
        }
        val glassBrush = if (isDark) {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color(0x38000000),
                    0.20f to Color(0x22000000),
                    0.50f to Color(0x1C000000),
                    0.80f to Color(0x22000000),
                    1.00f to Color(0x40000000)
                ),
                startX = cx - rx,
                endX = cx + rx
            )
        } else {
            Brush.horizontalGradient(
                colorStops = arrayOf(
                    0.00f to Color(0x24000000),
                    0.20f to Color(0x12000000),
                    0.50f to Color(0x0C000000),
                    0.80f to Color(0x12000000),
                    1.00f to Color(0x2C000000)
                ),
                startX = cx - rx,
                endX = cx + rx
            )
        }
        drawPath(path = glassPath, brush = glassBrush)

        // 4. Top rim oval at cylTopY
        val topRimStroke = if (isDark) Color(0x26FFFFFF) else Color(0x20000000)
        val topRimFill = if (isDark) Color(0x12FFFFFF) else Color(0x08000000)
        drawOval(
            color = topRimFill,
            topLeft = Offset(cx - rx, cylTopY - ry),
            size = Size(rx * 2f, ry * 2f)
        )
        drawOval(
            color = topRimStroke,
            topLeft = Offset(cx - rx, cylTopY - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = 0.75.dp.toPx())
        )

        // 5. Bottom rim arc at cylBotY
        drawArc(
            brush = Brush.horizontalGradient(
                colors = listOf(
                    Color.Black.copy(alpha = 0.25f),
                    Color.Black.copy(alpha = 0.10f),
                    Color.Black.copy(alpha = 0.30f)
                ),
                startX = cx - rx,
                endX = cx + rx
            ),
            startAngle = 0f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(cx - rx, cylBotY - ry),
            size = Size(rx * 2f, ry * 2f),
            style = Stroke(width = 0.75.dp.toPx())
        )
    }
}

private fun Color.lighten(fraction: Float): Color {
    val r = (red + (1f - red) * fraction).coerceIn(0f, 1f)
    val g = (green + (1f - green) * fraction).coerceIn(0f, 1f)
    val b = (blue + (1f - blue) * fraction).coerceIn(0f, 1f)
    return Color(r, g, b, alpha)
}

private fun Color.darken(fraction: Float): Color {
    val factor = (1f - fraction).coerceIn(0f, 1f)
    return Color(red * factor, green * factor, blue * factor, alpha)
}

private fun buildLibraryAnalysis(songs: List<AudioFile>): LibraryAnalysisSnapshot {
    fun bucketBy(selector: (AudioFile) -> String): List<LibraryAnalysisBucket> =
        songs.groupBy(selector).map { (key, grouped) ->
            LibraryAnalysisBucket(key, grouped.size, grouped.sumOf { it.fileSize }, grouped)
        }.sortedByDescending { it.count }

    val format = bucketBy(::analysisFormatLabel)
    val qualityOrder = listOf("DSD", "HI_RES", "LOSSLESS", "HIGH_QUALITY", "LOSSY", "UNKNOWN", "OTHER")
    val quality = bucketBy(::analysisQualityKey).sortedWith(
        compareBy<LibraryAnalysisBucket> {
            qualityOrder.indexOf(it.key).let { index -> if (index < 0) Int.MAX_VALUE else index }
        }.thenByDescending { it.count }
    )
    val sampleRates = bucketBy(::analysisSampleRateKey).sortedWith(
        compareByDescending<LibraryAnalysisBucket> { it.key.removeSuffix(" kHz").toFloatOrNull() ?: -1f }
            .thenByDescending { it.count }
    )
    val bitDepths = bucketBy(::analysisBitDepthKey).sortedWith(
        compareByDescending<LibraryAnalysisBucket> { it.key.removeSuffix("-bit").toIntOrNull() ?: -1 }
            .thenByDescending { it.count }
    )
    return LibraryAnalysisSnapshot(
        byDimension = mapOf(
            LibraryAnalysisDimension.FORMAT to format,
            LibraryAnalysisDimension.QUALITY to quality,
            LibraryAnalysisDimension.SAMPLE_RATE to sampleRates,
            LibraryAnalysisDimension.BIT_DEPTH to bitDepths,
        ),
        totalCount = songs.size,
        totalSizeBytes = songs.sumOf { it.fileSize },
    )
}

private fun collapseAnalysisBuckets(buckets: List<LibraryAnalysisBucket>): List<LibraryAnalysisBucket> {
    if (buckets.size <= 7) return buckets
    val remainder = buckets.drop(6)
    return buckets.take(6) + LibraryAnalysisBucket(
        key = "__OTHER__",
        count = remainder.sumOf { it.count },
        sizeBytes = remainder.sumOf { it.sizeBytes },
        songs = remainder.flatMap { it.songs },
    )
}

private fun analysisFormatLabel(song: AudioFile): String {
    val source = song.format.ifBlank { song.encodingFormat }.ifBlank { song.extension }
        .trim().uppercase()
    return when {
        source.contains("FLAC") -> "FLAC"
        source.contains("ALAC") -> "ALAC"
        source.contains("MP3") || source.contains("MPEG") -> "MP3"
        source.contains("AAC") -> "AAC"
        source.contains("WAV") || source.contains("WAVE") -> "WAV"
        source.contains("OGG") -> "OGG"
        source.contains("OPUS") -> "OPUS"
        source.contains("APE") -> "APE"
        source.contains("DSF") || source.contains("DSD") -> "DSD"
        source.isBlank() -> "OTHER"
        else -> source
    }
}

private fun analysisQualityKey(song: AudioFile): String = when {
    song.isDsdFormat -> "DSD"
    song.isHiRes -> "HI_RES"
    analysisFormatLabel(song) in setOf("FLAC", "ALAC", "WAV", "APE") -> "LOSSLESS"
    song.isHighQuality -> "HIGH_QUALITY"
    song.format.isBlank() && song.encodingFormat.isBlank() -> "UNKNOWN"
    else -> "LOSSY"
}

private fun analysisSampleRateKey(song: AudioFile): String {
    if (song.sampleRate <= 0) return "UNKNOWN"
    return if (song.sampleRate % 1000 == 0) {
        "${song.sampleRate / 1000} kHz"
    } else {
        "%.1f kHz".format(java.util.Locale.US, song.sampleRate / 1000.0)
    }
}

private fun analysisBitDepthKey(song: AudioFile): String =
    if (song.bitsPerSample > 0) "${song.bitsPerSample}-bit" else "UNKNOWN"

@Composable
private fun analysisDisplayLabel(key: String): String = when (key) {
    "HI_RES" -> stringResource(R.string.library_analysis_quality_hi_res)
    "LOSSLESS" -> stringResource(R.string.library_analysis_quality_lossless)
    "LOSSY" -> stringResource(R.string.library_analysis_quality_lossy)
    "HIGH_QUALITY" -> stringResource(R.string.library_analysis_quality_high)
    "DSD" -> stringResource(R.string.library_analysis_quality_dsd)
    "UNKNOWN" -> stringResource(R.string.library_analysis_unknown)
    "OTHER", "__OTHER__" -> stringResource(R.string.library_analysis_other)
    else -> key
}

@Composable
private fun analysisDimensionLabel(dimension: LibraryAnalysisDimension): String = when (dimension) {
    LibraryAnalysisDimension.FORMAT -> stringResource(R.string.library_analysis_dimension_format)
    LibraryAnalysisDimension.QUALITY -> stringResource(R.string.library_analysis_dimension_quality)
    LibraryAnalysisDimension.SAMPLE_RATE -> stringResource(R.string.library_analysis_dimension_sample_rate)
    LibraryAnalysisDimension.BIT_DEPTH -> stringResource(R.string.library_analysis_dimension_bit_depth)
}

@Composable
private fun analysisMetricLabel(metric: LibraryAnalysisMetric): String = when (metric) {
    LibraryAnalysisMetric.SIZE -> stringResource(R.string.library_analysis_metric_size)
    LibraryAnalysisMetric.COUNT -> stringResource(R.string.library_analysis_metric_count)
}

@Composable
private fun analysisSortLabel(sort: LibraryAnalysisSort): String = when (sort) {
    LibraryAnalysisSort.TITLE -> stringResource(R.string.library_analysis_sort_title)
    LibraryAnalysisSort.ARTIST -> stringResource(R.string.library_analysis_sort_artist)
    LibraryAnalysisSort.ALBUM -> stringResource(R.string.library_analysis_sort_album)
    LibraryAnalysisSort.SIZE -> stringResource(R.string.library_analysis_sort_size)
    LibraryAnalysisSort.DURATION -> stringResource(R.string.library_analysis_sort_duration)
}

private fun analysisPalette(title: String): List<Color> = when {
    title.contains("质量") || title.contains("Quality", true) -> listOf(
        Color(0xFF6A5ACD), Color(0xFF1AA6A6), Color(0xFFE0A458), Color(0xFFCA6670),
        Color(0xFF7C8798), Color(0xFF8DBA61), Color(0xFF9673B7),
    )
    title.contains("采样") || title.contains("Sample", true) -> listOf(
        Color(0xFF3B82F6), Color(0xFF14B8A6), Color(0xFFF59E0B), Color(0xFF8B5CF6),
        Color(0xFFEC6B7A), Color(0xFF64748B), Color(0xFF72A37B),
    )
    title.contains("位深") || title.contains("Bit", true) -> listOf(
        Color(0xFFE2A93B), Color(0xFF20A9B0), Color(0xFF4E72D8), Color(0xFF9A6BC0),
        Color(0xFFE27B67), Color(0xFF6BAA63), Color(0xFF758294),
    )
    else -> listOf(
        Color(0xFFE2A93B), Color(0xFF20A9B0), Color(0xFF4E72D8), Color(0xFF9A6BC0),
        Color(0xFFE27B67), Color(0xFF6BAA63), Color(0xFF758294),
    )
}

private fun cylinderSliceIndex(y: Float, height: Float, fractions: List<Float>, density: Float): Int? {
    val rim = (6f + 43f * 0.18f) * density
    val bottom = height - rim
    val top = rim + maxOf((bottom - rim) * 0.22f, 16f * density)
    if (fractions.isEmpty() || y < top - 24f * density || y > bottom + 24f * density) return null
    val body = (bottom - top).coerceAtLeast(1f)
    var cursor = top
    fractions.forEachIndexed { index, fraction ->
        cursor += body * fraction
        if (y <= cursor) return index
    }
    return fractions.lastIndex.takeIf { it >= 0 }
}

private fun cylinderSliceCenterY(
    index: Int,
    height: Float,
    fractions: List<Float>,
    density: Float,
): Float {
    val rim = (6f + 43f * 0.18f) * density
    val bottom = height - rim
    val top = rim + maxOf((bottom - rim) * 0.22f, 16f * density)
    val body = (bottom - top).coerceAtLeast(1f)
    var cursor = top
    fractions.forEachIndexed { current, fraction ->
        val slice = body * fraction
        if (current == index) return cursor + slice / 2f
        cursor += slice
    }
    return top
}

private fun formatLibraryFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> "%.1f GB".format(java.util.Locale.US, bytes / 1024.0 / 1024.0 / 1024.0)
    bytes >= 1024L * 1024L -> "%.1f MB".format(java.util.Locale.US, bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "%.1f KB".format(java.util.Locale.US, bytes / 1024.0)
    else -> "$bytes B"
}

private fun formatPercentOfLibrary(bucketCount: Int, totalCount: Int): String =
    "%.1f%%".format(java.util.Locale.US, if (totalCount > 0) bucketCount * 100f / totalCount else 0f)
