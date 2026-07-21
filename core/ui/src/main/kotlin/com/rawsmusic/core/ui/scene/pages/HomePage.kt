package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PageSize
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.scene.LocalSceneBackgroundFrozen
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowMode
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowModeSetter
import com.rawsmusic.core.ui.widget.flow.RawFlowModeDialog
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.absoluteValue
import kotlin.random.Random
import kotlinx.coroutines.delay
import top.yukonga.miuix.kmp.basic.SearchBar
import top.yukonga.miuix.kmp.basic.SearchBarDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.InputField
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Settings

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePage(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    playCounts: Map<Long, Int>,
    listState: LazyListState = rememberLazyListState(),
    onNavigate: (NavScene) -> Unit,
    onSearchClick: () -> Unit,
    showSettingsShortcut: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onSongClick: (AudioFile, Int) -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit
) {
    var showFlowModeDialog by remember { mutableStateOf(false) }
    val rawFlowMode = LocalRawFlowMode.current
    val setRawFlowMode = LocalRawFlowModeSetter.current

    Box(modifier = Modifier.fillMaxSize()) {
        HomePageContent(
            songs = songs,
            currentSong = currentSong,
            playCounts = playCounts,
            listState = listState,
            onNavigate = onNavigate,
            onSearchClick = onSearchClick,
            onFlowBackgroundClick = { showFlowModeDialog = true },
            showSettingsShortcut = showSettingsShortcut,
            onSettingsClick = onSettingsClick,
            onSongClick = onSongClick,
            onPlayQueue = onPlayQueue
        )

        RawFlowModeDialog(
            show = showFlowModeDialog,
            selectedMode = rawFlowMode,
            onSelectMode = setRawFlowMode,
            onDismissRequest = { showFlowModeDialog = false }
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun HomePageContent(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    playCounts: Map<Long, Int>,
    listState: LazyListState = rememberLazyListState(),
    onNavigate: (NavScene) -> Unit,
    onSearchClick: () -> Unit,
    onFlowBackgroundClick: () -> Unit,
    showSettingsShortcut: Boolean,
    onSettingsClick: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit
) {
    var todayKey by remember { mutableStateOf(todayKey()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextDay())
            todayKey = todayKey()
        }
    }

    val backgroundColor = MiuixTheme.colorScheme.background
    val featurePagerState = rememberPagerState(initialPage = 1, pageCount = { 3 })
    val startupRecommendSeed = remember { "for-you-start-${System.currentTimeMillis()}" }
    val randomSong = remember(songs, startupRecommendSeed) { songs.stableShuffled(startupRecommendSeed).firstOrNull() }
    val forYouQueue = remember(songs, startupRecommendSeed, randomSong?.id) {
        if (randomSong == null) {
            emptyList()
        } else {
            listOf(randomSong) + songs.stableShuffled("$startupRecommendSeed-queue").filter { it.id != randomSong.id }
        }
    }
    val forYouDisplaySong = currentSong ?: randomSong
    val dailySongs = remember(songs, todayKey) { daily20Songs(songs, todayKey) }
    val artistSong = remember(songs, todayKey) {
        songs.groupBy { it.artist.ifBlank { "未知艺术家" } }
            .maxByOrNull { it.value.size }
            ?.value
            ?.stableShuffled("artist-$todayKey")
            ?.firstOrNull()
    }
    val libraryCards = remember(songs) { homeLibraryCards(songs) }
    val toolCards = remember(songs) { homeToolCards(songs) }
    val mostPlayed = remember(songs, playCounts) {
        songs.sortedWith(
            compareByDescending<AudioFile> { playCounts[it.id] ?: 0 }
                .thenBy { it.title.lowercase(Locale.getDefault()) }
        ).take(10)
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.home_recommend_title), fontSize = 28.sp, fontWeight = FontWeight.Bold, color = MiuixTheme.colorScheme.onBackground)
                        Text(
                            stringResource(R.string.home_recommend_subtitle),
                            fontSize = 14.sp,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onFlowBackgroundClick) {
                            Icon(
                                painter = painterResource(R.drawable.ic_palette),
                                contentDescription = stringResource(R.string.flow_background_action),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        if (showSettingsShortcut) {
                            IconButton(onClick = onSettingsClick) {
                                Icon(
                                    imageVector = MiuixIcons.Regular.Settings,
                                    contentDescription = stringResource(R.string.bottom_nav_settings),
                                    tint = MiuixTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
                SearchBar(
                    inputField = {
                        InputField(
                            query = "",
                            onQueryChange = {},
                            onSearch = {},
                            expanded = false,
                            onExpandedChange = { onSearchClick() },
                            label = stringResource(R.string.search_hint),
                            enabled = false
                        )
                    },
                    onExpandedChange = { onSearchClick() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onSearchClick() },
                    expanded = false,
                    content = {}
                )
            }
        }

        item {
            HorizontalPager(
                state = featurePagerState,
                pageSize = PageSize.Fixed(250.dp),
                pageSpacing = 14.dp,
                contentPadding = androidx.compose.foundation.layout.PaddingValues(end = 82.dp)
            ) { page ->
                when (page) {
                    0 -> FeatureCard(
                        title = stringResource(R.string.home_feature_artist),
                        subtitle = artistSong?.artist?.takeIf { it.isNotBlank() } ?: "从播放最多的歌手开始",
                        hint = artistSong?.displayName ?: stringResource(R.string.home_feature_artist_hint),
                        song = artistSong,
                        color = pastelColorFor("artist-${artistSong?.artist ?: todayKey}"),
                        onClick = { onNavigate(NavScene.ARTISTS) }
                    )
                    1 -> FeatureCard(
                            title = stringResource(R.string.home_feature_for_you),
                            subtitle = forYouDisplaySong?.displayName ?: stringResource(R.string.home_feature_for_you_hint),
                            hint = stringResource(R.string.home_feature_for_you_hint),
                            song = forYouDisplaySong,
                            color = pastelColorFor(startupRecommendSeed),
                            onClick = {
                                if (forYouQueue.isNotEmpty()) {
                                    onPlayQueue(forYouQueue, 0)
                                }
                            }
                    )
                    else -> FeatureCard(
                        title = "每日20首",
                        subtitle = dailySongs.firstOrNull()?.displayName ?: stringResource(R.string.home_feature_daily20_hint),
                        hint = stringResource(R.string.home_feature_daily20_hint),
                        song = dailySongs.firstOrNull(),
                        color = pastelColorFor("daily-$todayKey"),
                        onClick = { onNavigate(NavScene.DAILY_20) }
                    )
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.home_library_section))
        }

        items(libraryCards.chunked(2)) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                row.forEach { card ->
                    LibraryTile(
                        scene = card.scene,
                        song = card.song,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(card.scene) }
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            SectionTitle(stringResource(R.string.home_most_played_section))
        }

        items(mostPlayed) { song ->
            val index = songs.indexOfFirst { it.id == song.id }.coerceAtLeast(0)
            MostPlayedRow(
                song = song,
                playCount = playCounts[song.id] ?: 0,
                onClick = { onSongClick(song, index) }
            )
        }

        item {
            SectionTitle(stringResource(R.string.home_tools_section))
        }

        items(toolCards.chunked(2)) { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                row.forEach { card ->
                    LibraryTile(
                        scene = card.scene,
                        song = card.song,
                        modifier = Modifier.weight(1f),
                        onClick = { onNavigate(card.scene) }
                    )
                }
                if (row.size == 1) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }

        item {
            Spacer(modifier = Modifier.height(170.dp))
        }
    }
}

@Composable
fun Daily20Page(
    songs: List<AudioFile>,
    onBack: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit
) {
    var todayKey by remember { mutableStateOf(todayKey()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(millisUntilNextDay())
            todayKey = todayKey()
        }
    }

    val dailySongs = remember(songs, todayKey) { daily20Songs(songs, todayKey) }
    val coverSong = dailySongs.firstOrNull()
    LazyColumn(
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(520.dp)
            ) {
                if (coverSong?.coverKey?.isNotBlank() == true) {
                    BitmapImage(
                        key = coverSong.coverKey,
                        contentDescription = null,
                        modifier = Modifier
                            .fillMaxSize(),
                        contentScale = ContentScale.Crop,
                        targetWidth = 1600,
                        targetHeight = 1600
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(pastelColorFor("daily-bg-$todayKey"))
                    )
                }
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Black.copy(alpha = 0.04f),
                                    Color.Black.copy(alpha = 0.08f),
                                    MiuixTheme.colorScheme.background.copy(alpha = 0.72f),
                                    MiuixTheme.colorScheme.background.copy(alpha = 0.38f)
                                ),
                                startY = 0f,
                                endY = 1200f
                            )
                        )
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleIconButton(onClick = onBack) {
                        VectorIcon(
                            Icons.Default.ArrowBack,
                            contentDescription = stringResource(R.string.library_action_back),
                            tint = Color.White
                        )
                    }
                    CircleIconButton(onClick = {}) {
                        Image(
                            painter = painterResource(R.drawable.ic_share),
                            contentDescription = stringResource(R.string.home_action_share),
                            colorFilter = ColorFilter.tint(Color.White),
                            modifier = Modifier.size(25.dp)
                        )
                    }
                }
                Column(
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .padding(horizontal = 22.dp, vertical = 24.dp)
                ) {
                    Text("每日20首", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        "每日更新，按照今天的日期从本地曲库精选20首。滑下去就是完整歌曲列表。",
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                        color = Color(0xFF2F3440),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillAction(
                            text = "收藏歌单",
                            icon = { VectorIcon(Icons.Default.Favorite, contentDescription = null, tint = Color.Black) },
                            modifier = Modifier.weight(1f)
                        )
                        PillAction(
                            text = "全部播放",
                            icon = { VectorIcon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black) },
                            modifier = Modifier.weight(1f),
                            onClick = { if (dailySongs.isNotEmpty()) onPlayQueue(dailySongs, 0) }
                        )
                    }
                }
            }
        }

        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${dailySongs.size}首歌曲", fontSize = 23.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onBackground)
                Text(todayKey, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }

        items(dailySongs) { song ->
            val position = dailySongs.indexOf(song)
            DailySongRow(
                position = position + 1,
                song = song,
                onClick = { onPlayQueue(dailySongs, position) }
            )
        }

        item {
            Spacer(modifier = Modifier.height(170.dp))
        }
    }
}

@Composable
private fun FeatureCard(
    title: String,
    subtitle: String,
    hint: String,
    song: AudioFile?,
    color: Color,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .width(250.dp)
            .height(188.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(color)
            .clickable { onClick() }
            .padding(18.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Text(title, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Spacer(Modifier.height(6.dp))
            Text(hint, fontSize = 13.sp, color = Color.White.copy(alpha = 0.82f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeatureCover(song = song)
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        subtitle,
                        fontSize = 16.sp,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        song?.artist?.takeIf { it.isNotBlank() } ?: "RawSMusic",
                        fontSize = 12.sp,
                        color = Color.White.copy(alpha = 0.78f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(Modifier.width(10.dp))
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    VectorIcon(Icons.Default.PlayArrow, contentDescription = null, tint = color, modifier = Modifier.size(32.dp))
                }
            }
        }
    }
}

@Composable
private fun FeatureCover(song: AudioFile?) {
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = 0.28f))
    ) {
        AnimatedContent(
            targetState = song?.id,
            transitionSpec = {
                slideInHorizontally(
                    animationSpec = tween(260),
                    initialOffsetX = { it }
                ) togetherWith slideOutHorizontally(
                    animationSpec = tween(260),
                    targetOffsetX = { -it }
                ) using SizeTransform(clip = true)
            },
            label = "feature-cover"
        ) { _ ->
            if (song?.coverKey?.isNotBlank() == true) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 256,
                    targetHeight = 256
                )
            }
        }
    }
}

@Composable
private fun LibraryTile(
    scene: NavScene,
    song: AudioFile?,
    modifier: Modifier,
    onClick: () -> Unit
) {
    Column(modifier = modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(13.dp))
                .background(pastelColorFor(scene.tag))
        ) {
            if (song?.coverKey?.isNotBlank() == true) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 360,
                    targetHeight = 360
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.36f))))
                )
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(42.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.92f)),
                contentAlignment = Alignment.Center
            ) {
                VectorIcon(Icons.Default.PlayArrow, contentDescription = null, tint = Color.Black, modifier = Modifier.size(28.dp))
            }
        }
        Spacer(Modifier.height(9.dp))
        Text(
            scene.label,
            fontSize = 17.sp,
            fontWeight = FontWeight.Medium,
            color = MiuixTheme.colorScheme.onBackground,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun MostPlayedRow(
    song: AudioFile,
    playCount: Int,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(74.dp)
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(pastelColorFor(song.title))
        ) {
            if (song.coverKey.isNotBlank()) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 160,
                    targetHeight = 160
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.displayName, fontSize = 18.sp, color = MiuixTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "未知艺术家" }, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text("${playCount}次", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(10.dp))
        VectorIcon(Icons.Default.PlayArrow, contentDescription = null, tint = MiuixTheme.colorScheme.onBackground, modifier = Modifier.size(30.dp))
    }
}

@Composable
private fun DailySongRow(
    position: Int,
    song: AudioFile,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(76.dp)
            .clickable { onClick() }
            .padding(horizontal = 22.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(position.toString(), fontSize = 22.sp, color = MiuixTheme.colorScheme.onBackground, modifier = Modifier.width(34.dp))
        Box(
            modifier = Modifier
                .size(52.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(pastelColorFor(song.title))
        ) {
            if (song.coverKey.isNotBlank()) {
                BitmapImage(
                    key = song.coverKey,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    targetWidth = 160,
                    targetHeight = 160
                )
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(song.displayName, fontSize = 19.sp, color = MiuixTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist.ifBlank { "未知艺术家" }, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        VectorIcon(Icons.Default.PlayArrow, contentDescription = null, tint = MiuixTheme.colorScheme.onSurfaceVariantSummary, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        fontSize = 25.sp,
        fontWeight = FontWeight.Bold,
        color = MiuixTheme.colorScheme.onBackground,
        modifier = Modifier.padding(top = 6.dp)
    )
}

@Composable
private fun CircleIconButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
private fun PillAction(
    text: String,
    icon: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = modifier
            .height(58.dp)
            .clip(RoundedCornerShape(29.dp))
            .background(Color.White)
            .clickable { onClick() }
            .padding(horizontal = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        icon()
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 18.sp, color = Color.Black, fontWeight = FontWeight.Medium)
    }
}

private data class LibraryHomeCard(
    val scene: NavScene,
    val song: AudioFile?
)

private fun homeLibraryCards(songs: List<AudioFile>): List<LibraryHomeCard> {
    val scenes = listOf(
        NavScene.SONGS,
        NavScene.FOLDERS,
        NavScene.ALBUMS,
        NavScene.ARTISTS,
        NavScene.PLAYLISTS,
        NavScene.QUEUE,
        NavScene.RECENTLY_ADDED,
        NavScene.GENRE,
        NavScene.YEAR,
        NavScene.COMPOSER
    )
    val covers = songs.filter { it.coverKey.isNotBlank() }.ifEmpty { songs }
    return scenes.mapIndexed { index, scene ->
        LibraryHomeCard(scene, covers.getOrNull(index % covers.size.coerceAtLeast(1)))
    }
}

private fun homeToolCards(songs: List<AudioFile>): List<LibraryHomeCard> {
    val scenes = listOf(
        NavScene.ANALYTICS,
        NavScene.SONG_STATS,
        NavScene.WEBDAV,
        NavScene.LOG_VIEWER
    )
    val covers = songs.filter { it.coverKey.isNotBlank() }.ifEmpty { songs }
    return scenes.mapIndexed { index, scene ->
        LibraryHomeCard(scene, covers.getOrNull((index + 5) % covers.size.coerceAtLeast(1)))
    }
}

private fun daily20Songs(songs: List<AudioFile>, dateKey: String): List<AudioFile> {
    return songs.stableShuffled("daily-20-$dateKey").take(20)
}

private fun List<AudioFile>.stableShuffled(seedKey: String): List<AudioFile> {
    if (isEmpty()) return emptyList()
    val seed = seedKey.hashCode().toLong() * 31L + size
    return shuffled(Random(seed))
}

private fun pastelColorFor(key: String): Color {
    val palette = listOf(
        Color(0xFFFF7F7C),
        Color(0xFF8FA2F0),
        Color(0xFF6EC8B7),
        Color(0xFFE5B46E),
        Color(0xFFA5C778),
        Color(0xFFD88DB5),
        Color(0xFF80B7D8),
        Color(0xFFC3A4E8)
    )
    return palette[key.hashCode().absoluteValue % palette.size]
}

private fun todayKey(): String {
    return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
}

private fun millisUntilNextDay(): Long {
    val now = Calendar.getInstance()
    val next = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_YEAR, 1)
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
    }
    return (next.timeInMillis - now.timeInMillis).coerceAtLeast(1_000L)
}

@Composable
private fun VectorIcon(
    imageVector: ImageVector,
    contentDescription: String?,
    tint: Color,
    modifier: Modifier = Modifier.size(24.dp)
) {
    Image(
        painter = rememberVectorPainter(imageVector),
        contentDescription = contentDescription,
        modifier = modifier,
        colorFilter = ColorFilter.tint(tint)
    )
}
