package com.rawsmusic.core.ui.scene.pages

import android.content.Context
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
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
import com.rawsmusic.core.ui.scene.HomeFullCoverSourceAnchor
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowMode
import com.rawsmusic.core.ui.widget.flow.LocalRawFlowModeSetter
import com.rawsmusic.core.ui.widget.flow.RawFlowModeDialog
import io.github.proify.lyricon.lyric.model.Song
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

private const val HOME_HEADER_PREFS = "home_header_preferences"
private const val HOME_WEATHER_VISIBLE_KEY = "weather_visible"
private const val HOME_CAROUSEL_STYLE_KEY = "carousel_style"
private const val HOME_CAROUSEL_LYRIC_VISIBLE_KEY = "carousel_lyric_visible"

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePage(
    songs: List<AudioFile>,
    currentSong: AudioFile?,
    queueSongs: List<AudioFile>,
    queueCurrentIndex: Int,
    currentLyric: String,
    currentLyricTranslation: String,
    lyricSong: Song?,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    playCounts: Map<Long, Int>,
    listState: LazyListState = rememberLazyListState(),
    carouselState: HomeArtworkCarouselState,
    renderBackdrop: Boolean = true,
    onNavigate: (NavScene) -> Unit,
    onSearchClick: () -> Unit,
    showSettingsShortcut: Boolean = false,
    onSettingsClick: () -> Unit = {},
    onCurrentPlayPause: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onQueueSongClick: (List<AudioFile>, AudioFile, Int) -> Unit,
    onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit = {},
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit = { _, _ -> },
    hideCenterForFullscreenTransition: Boolean = false,
    centerReflectionAlpha: Float = 1f,
    centerReflectionArtworkKey: String = "",
) {
    val context = LocalContext.current.applicationContext
    val headerPreferences = remember(context) {
        context.getSharedPreferences(HOME_HEADER_PREFS, Context.MODE_PRIVATE)
    }
    var showFlowModeDialog by remember { mutableStateOf(false) }
    var showHeaderMenuDialog by remember { mutableStateOf(false) }
    var weatherVisible by remember {
        mutableStateOf(headerPreferences.getBoolean(HOME_WEATHER_VISIBLE_KEY, true))
    }
    var carouselLyricVisible by remember {
        mutableStateOf(headerPreferences.getBoolean(HOME_CAROUSEL_LYRIC_VISIBLE_KEY, true))
    }
    var carouselStyle by remember {
        mutableStateOf(
            HomeArtworkCarouselStyle.from(
                headerPreferences.getInt(HOME_CAROUSEL_STYLE_KEY, HomeArtworkCarouselStyle.CurrentCarousel.value)
            )
        )
    }
    val rawFlowMode = LocalRawFlowMode.current
    val setRawFlowMode = LocalRawFlowModeSetter.current
    val carouselSongs = remember(queueSongs, currentSong) {
        queueSongs.ifEmpty { listOfNotNull(currentSong) }
    }
    Box(modifier = Modifier.fillMaxSize()) {
        if (renderBackdrop) {
            HomeArtworkCarouselBackdrop(
                songs = carouselSongs,
                currentSong = currentSong,
                state = carouselState,
                modifier = Modifier.fillMaxSize()
            )
        }
        HomePageContent(
            songs = songs,
            currentSong = currentSong,
            queueSongs = carouselSongs,
            carouselState = carouselState,
            carouselStyle = carouselStyle,
            carouselLyricVisible = carouselLyricVisible,
            currentLyric = currentLyric,
            currentLyricTranslation = currentLyricTranslation,
            lyricSong = lyricSong,
            playbackPositionMs = playbackPositionMs,
            isPlaying = isPlaying,
            playCounts = playCounts,
            listState = listState,
            onNavigate = onNavigate,
            onSearchClick = onSearchClick,
            weatherVisible = weatherVisible,
            onHeaderMenuClick = { showHeaderMenuDialog = true },
            onFlowBackgroundClick = { showFlowModeDialog = true },
            showSettingsShortcut = showSettingsShortcut,
            onSettingsClick = onSettingsClick,
            onCurrentPlayPause = onCurrentPlayPause,
            onSongClick = onSongClick,
            onQueueSongClick = onQueueSongClick,
            onCurrentArtworkLongPress = onCurrentArtworkLongPress,
            onCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
            hideCenterForFullscreenTransition = hideCenterForFullscreenTransition,
            centerReflectionAlpha = centerReflectionAlpha,
            centerReflectionArtworkKey = centerReflectionArtworkKey,
        )

        HomeHeaderMenuDialog(
            show = showHeaderMenuDialog,
            weatherVisible = weatherVisible,
            onWeatherVisibleChange = { visible ->
                weatherVisible = visible
                headerPreferences.edit().putBoolean(HOME_WEATHER_VISIBLE_KEY, visible).apply()
            },
            carouselLyricVisible = carouselLyricVisible,
            onCarouselLyricVisibleChange = { visible ->
                carouselLyricVisible = visible
                headerPreferences.edit()
                    .putBoolean(HOME_CAROUSEL_LYRIC_VISIBLE_KEY, visible)
                    .apply()
            },
            carouselStyle = carouselStyle,
            onCarouselStyleChange = { style ->
                carouselStyle = style
                headerPreferences.edit().putInt(HOME_CAROUSEL_STYLE_KEY, style.value).apply()
            },
            onDismissRequest = { showHeaderMenuDialog = false }
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
    queueSongs: List<AudioFile>,
    carouselState: HomeArtworkCarouselState,
    carouselStyle: HomeArtworkCarouselStyle,
    carouselLyricVisible: Boolean,
    currentLyric: String,
    currentLyricTranslation: String,
    lyricSong: Song?,
    playbackPositionMs: Long,
    isPlaying: Boolean,
    playCounts: Map<Long, Int>,
    listState: LazyListState = rememberLazyListState(),
    onNavigate: (NavScene) -> Unit,
    onSearchClick: () -> Unit,
    weatherVisible: Boolean,
    onHeaderMenuClick: () -> Unit,
    onFlowBackgroundClick: () -> Unit,
    showSettingsShortcut: Boolean,
    onSettingsClick: () -> Unit,
    onCurrentPlayPause: () -> Unit,
    onSongClick: (AudioFile, Int) -> Unit,
    onQueueSongClick: (List<AudioFile>, AudioFile, Int) -> Unit,
    onCurrentArtworkLongPress: (HomeFullCoverSourceAnchor) -> Unit,
    onCurrentArtworkBoundsChanged: (AudioFile, Rect) -> Unit,
    hideCenterForFullscreenTransition: Boolean,
    centerReflectionAlpha: Float,
    centerReflectionArtworkKey: String,
) {
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
                HomeTopHeader(
                    weatherVisible = weatherVisible,
                    showSettingsShortcut = showSettingsShortcut,
                    onMenuClick = onHeaderMenuClick,
                    onFlowBackgroundClick = onFlowBackgroundClick,
                    onSettingsClick = onSettingsClick
                )
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
            HomeArtworkCarousel(
                songs = queueSongs,
                currentSong = currentSong,
                state = carouselState,
                style = carouselStyle,
                showLyrics = carouselLyricVisible,
                currentLyric = currentLyric,
                currentLyricTranslation = currentLyricTranslation,
                lyricSong = lyricSong,
                playbackPositionMs = playbackPositionMs,
                isPlaying = isPlaying,
                onSelectSong = onQueueSongClick,
                onCurrentArtworkLongPress = onCurrentArtworkLongPress,
                onCurrentArtworkBoundsChanged = onCurrentArtworkBoundsChanged,
                hideCenterForFullscreenTransition = hideCenterForFullscreenTransition,
                centerReflectionAlpha = centerReflectionAlpha,
                centerReflectionArtworkKey = centerReflectionArtworkKey,
            )
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
            val isCurrentSong = currentSong?.let { current ->
                current.path == song.path &&
                    current.cueOffsetMs == song.cueOffsetMs &&
                    current.cueTrackIndex == song.cueTrackIndex
            } == true
            MostPlayedRow(
                song = song,
                playCount = playCounts[song.id] ?: 0,
                isCurrentSong = isCurrentSong,
                isPlaying = isCurrentSong && isPlaying,
                onClick = { onSongClick(song, index) },
                onPlayPauseClick = {
                    if (isCurrentSong) onCurrentPlayPause() else onSongClick(song, index)
                }
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
                    Text(stringResource(R.string.home_daily_songs_title), fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.Black)
                    Spacer(Modifier.height(14.dp))
                    Text(
                        stringResource(R.string.home_daily_songs_summary),
                        fontSize = 16.sp,
                        lineHeight = 25.sp,
                        color = Color(0xFF2F3440),
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(28.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        PillAction(
                            text = stringResource(R.string.home_favorite_playlist),
                            icon = { VectorIcon(Icons.Default.Favorite, contentDescription = null, tint = Color.Black) },
                            modifier = Modifier.weight(1f)
                        )
                        PillAction(
                            text = stringResource(R.string.home_play_all),
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
                Text(stringResource(R.string.home_song_count, dailySongs.size), fontSize = 23.sp, fontWeight = FontWeight.Medium, color = MiuixTheme.colorScheme.onBackground)
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
            if (scene == NavScene.SOURCE_IMPORT) {
                Image(
                    painter = painterResource(R.drawable.ic_cloud),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(Color.White.copy(alpha = 0.94f)),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(58.dp)
                )
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.White.copy(alpha = 0.05f),
                                    Color.Black.copy(alpha = 0.18f)
                                )
                            )
                        )
                )
            } else if (song?.coverKey?.isNotBlank() == true) {
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
    isCurrentSong: Boolean,
    isPlaying: Boolean,
    onClick: () -> Unit,
    onPlayPauseClick: () -> Unit
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
            Text(song.artist.ifBlank { stringResource(R.string.common_unknown_artist) }, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        Text(stringResource(R.string.home_play_count, playCount), fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        Spacer(Modifier.width(8.dp))
        Box(
            modifier = Modifier
                .size(42.dp)
                .clip(CircleShape)
                .clickable(onClick = onPlayPauseClick),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(if (isPlaying) R.drawable.ic_pause else R.drawable.ic_play),
                contentDescription = stringResource(if (isPlaying) R.string.common_pause else R.string.common_play),
                colorFilter = ColorFilter.tint(
                    if (isCurrentSong) MiuixTheme.colorScheme.primary
                    else MiuixTheme.colorScheme.onBackground
                ),
                modifier = Modifier.size(28.dp)
            )
        }
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
            Text(song.artist.ifBlank { stringResource(R.string.common_unknown_artist) }, fontSize = 14.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 1, overflow = TextOverflow.Ellipsis)
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

@Composable
private fun HomeTopHeader(
    weatherVisible: Boolean,
    showSettingsShortcut: Boolean,
    onMenuClick: () -> Unit,
    onFlowBackgroundClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentAlignment = Alignment.Center
        ) {
            IconButton(
                onClick = onMenuClick,
                modifier = Modifier.align(Alignment.CenterStart)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_home_hamburger),
                    contentDescription = stringResource(R.string.home_header_menu_action),
                    tint = MiuixTheme.colorScheme.onSurface,
                    modifier = Modifier.size(24.dp)
                )
            }

            Text(
                text = stringResource(R.string.bottom_nav_home),
                fontSize = 22.sp,
                fontWeight = FontWeight.SemiBold,
                color = MiuixTheme.colorScheme.onBackground,
                modifier = Modifier.align(Alignment.Center)
            )

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
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

        if (weatherVisible) {
            Spacer(Modifier.height(4.dp))
            HomeWeatherHeader(modifier = Modifier.fillMaxWidth())
        }
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
        NavScene.COMPOSER,
        NavScene.SOURCE_IMPORT
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
