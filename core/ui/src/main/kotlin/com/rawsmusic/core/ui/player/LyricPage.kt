package com.rawsmusic.core.ui.widget.player

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkAnchor
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkSource
import com.rawsmusic.core.ui.widget.PlayerLyricsArtworkVisibility
import com.rawsmusic.core.ui.widget.PlayerLyricsRect
import com.rawsmusic.core.ui.widget.PlayerLyricsScrollDirection
import com.rawsmusic.core.ui.widget.PlayerLyricsTransitionCoordinator
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.prefs.LyricLayoutPreferences
import com.rawsmusic.module.data.prefs.LyricTopLayoutStyle
import com.rawsmusic.core.ui.theme.ThemeManager
import io.github.proify.lyricon.lyric.model.Song
import top.yukonga.miuix.kmp.basic.DropdownEntry
import top.yukonga.miuix.kmp.basic.DropdownItem
import top.yukonga.miuix.kmp.preference.WindowDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

@Composable
fun LyricPage(
    currentSong: AudioFile? = null,
    coverPath: String? = null,
    song: Song? = null,
    positionMs: Long = 0L,
    isPlaying: Boolean = false,
    displayTranslation: Boolean = false,
    displayRoma: Boolean = false,
    @DrawableRes moreIconRes: Int = R.drawable.ic_more_vert,
    onSeek: (Long) -> Unit = {},
    onTranslationToggle: () -> Unit = {},
    onRomaToggle: () -> Unit = {},
    onSearchLyrico: () -> Unit = {},
    onOpenInLyrico: () -> Unit = {},
    onModifyAlbumArt: () -> Unit = {},
    onBack: () -> Unit = {},
    interactiveHorizontalSwipe: Boolean = false,
    onModalVisibleChange: (Boolean) -> Unit = {},
    onModalDismissActionChange: ((() -> Unit)?) -> Unit = {},
    onCoverSwipeDownStart: () -> Unit = {},
    onCoverSwipeDownProgress: (Float) -> Unit = {},
    onCoverSwipeDownEnd: (Boolean, Float) -> Unit = { _, _ -> },
    showHeaderCover: Boolean = true,
    onHeaderCoverVisibilityChange: (Boolean) -> Unit = {},
    onArtworkAnchorChanged: (PlayerLyricsArtworkAnchor?) -> Unit = {},
    renderBackdrop: Boolean = true,
    contentAlpha: Float = 1f,
    modifier: Modifier = Modifier
) {
    val dark = ThemeManager.isDarkMode(LocalContext.current)
    val lyricTextColor = if (dark) Color.White else Color(0xFF1B1B20)
    val lyricSecondaryColor = lyricTextColor.copy(alpha = if (dark) 0.86f else 0.70f)
    val lyricTertiaryColor = lyricTextColor.copy(alpha = if (dark) 0.50f else 0.48f)
    val accent = rememberCoverAccentColor(coverPath)
    var showLyricMore by remember { mutableStateOf(false) }
    var lyricFontSizeSp by remember { mutableStateOf(AppPreferences.UI.lyricFontSizeSp) }
    val lyricBottomPaddingDp by LyricLayoutPreferences.bottomPaddingDp.collectAsState()
    val lyricTopLayoutStyle by LyricLayoutPreferences.topLayoutStyle.collectAsState()
    var lyricBlurEnabled by remember { mutableStateOf(AppPreferences.UI.lyricBlurEnabled) }
    var lyricHighlightAllEnabled by remember {
        mutableStateOf(AppPreferences.UI.lyricHighlightAllEnabled)
    }
    val karaokeGlowEnabled = AppPreferences.UI.lyricKaraokeGlowEnabled
    val karaokeLiftEnabled = AppPreferences.UI.lyricKaraokeLiftEnabled
    var lyricTextPosition by remember {
        mutableStateOf(LyricTextPosition.from(AppPreferences.UI.lyricTextPosition))
    }
    val actionScope = rememberCoroutineScope()
    var headerHeightPx by remember { mutableStateOf(1f) }
    var fixedTopRegionHeightPx by remember(lyricTopLayoutStyle) { mutableStateOf(0f) }
    var fixedHeaderCoverRect by remember { mutableStateOf<PlayerLyricsRect?>(null) }
    var scrollingHeaderCoverRect by remember { mutableStateOf<PlayerLyricsRect?>(null) }
    var scrollingHeaderViewport by remember {
        mutableStateOf(
            LyricScrollingHeaderViewportState(
                visibility = PlayerLyricsArtworkVisibility.Missing,
                visibleFraction = 0f,
                normalizedViewportPosition = null,
                offscreenDistancePx = 0f,
                scrollDirection = PlayerLyricsScrollDirection.Still,
                isScrollInProgress = false,
                isAtTop = false
            )
        )
    }
    val latestArtworkAnchorChanged by rememberUpdatedState(onArtworkAnchorChanged)

    val artworkSnapshotGeneration = remember { AtomicLong(0L) }

    fun buildCurrentArtworkAnchor(
        nowMs: Long,
        snapshotGeneration: Long
    ): PlayerLyricsArtworkAnchor {
        return when (lyricTopLayoutStyle) {
            LyricTopLayoutStyle.Current -> {
                val rect = fixedHeaderCoverRect
                val visible = rect?.isUsable == true
                PlayerLyricsArtworkAnchor(
                    source = PlayerLyricsArtworkSource.LyricFixed,
                    artworkKey = coverPath,
                    rect = if (visible) rect else null,
                    visibility = if (visible) {
                        PlayerLyricsArtworkVisibility.Visible
                    } else {
                        PlayerLyricsArtworkVisibility.Missing
                    },
                    visibleFraction = if (visible) 1f else 0f,
                    offscreenDistancePx = 0f,
                    scrollDirection = PlayerLyricsScrollDirection.Still,
                    isScrollInProgress = false,
                    stable = visible,
                    cornerRadiusDp = LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP,
                    updatedAtUptimeMs = nowMs,
                    normalizedViewportPosition = if (visible) 0f else null,
                    snapshotGeneration = snapshotGeneration
                )
            }
            LyricTopLayoutStyle.TitleOnly -> PlayerLyricsArtworkAnchor(
                source = PlayerLyricsArtworkSource.LyricTitleOnly,
                artworkKey = coverPath,
                rect = null,
                visibility = PlayerLyricsArtworkVisibility.Missing,
                visibleFraction = 0f,
                offscreenDistancePx = 0f,
                scrollDirection = PlayerLyricsScrollDirection.Still,
                isScrollInProgress = false,
                stable = false,
                cornerRadiusDp = LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP,
                updatedAtUptimeMs = nowMs,
                normalizedViewportPosition = null,
                snapshotGeneration = snapshotGeneration
            )
            LyricTopLayoutStyle.ScrollWithLyrics -> {
                val normalizedPosition = scrollingHeaderViewport.normalizedViewportPosition
                val insideSharedArtworkCandidateWindow = normalizedPosition != null &&
                    normalizedPosition >
                    -PlayerLyricsTransitionCoordinator.SHARED_ARTWORK_POSITION_LIMIT &&
                    normalizedPosition <
                    PlayerLyricsTransitionCoordinator.SHARED_ARTWORK_POSITION_LIMIT
                val realVisibleCandidate =
                    scrollingHeaderViewport.visibility == PlayerLyricsArtworkVisibility.Visible &&
                        !scrollingHeaderViewport.isScrollInProgress &&
                        scrollingHeaderCoverRect?.isUsable == true &&
                        insideSharedArtworkCandidateWindow
                PlayerLyricsArtworkAnchor(
                    source = PlayerLyricsArtworkSource.LyricScrolling,
                    artworkKey = coverPath,
                    rect = if (realVisibleCandidate) scrollingHeaderCoverRect else null,
                    visibility = scrollingHeaderViewport.visibility,
                    visibleFraction = scrollingHeaderViewport.visibleFraction,
                    offscreenDistancePx = scrollingHeaderViewport.offscreenDistancePx,
                    scrollDirection = scrollingHeaderViewport.scrollDirection,
                    isScrollInProgress = scrollingHeaderViewport.isScrollInProgress,
                    stable = realVisibleCandidate,
                    cornerRadiusDp = LYRIC_HEADER_ARTWORK_CORNER_RADIUS_DP,
                    updatedAtUptimeMs = nowMs,
                    normalizedViewportPosition = normalizedPosition,
                    snapshotGeneration = snapshotGeneration
                )
            }
        }
    }

    val currentArtworkAnchor = buildCurrentArtworkAnchor(
        nowMs = android.os.SystemClock.uptimeMillis(),
        snapshotGeneration = artworkSnapshotGeneration.get()
    )
    val latestCurrentArtworkAnchor by rememberUpdatedState(currentArtworkAnchor)
    val publishArtworkSnapshot = {
        latestArtworkAnchorChanged(
            latestCurrentArtworkAnchor.copy(
                updatedAtUptimeMs = android.os.SystemClock.uptimeMillis(),
                snapshotGeneration = artworkSnapshotGeneration.incrementAndGet()
            )
        )
    }
    val latestPublishArtworkSnapshot by rememberUpdatedState(publishArtworkSnapshot)

    // Publish every committed composition, and publish once more synchronously before a lyric
    // gesture starts. The coordinator therefore consumes the current list/layout state instead of
    // a time-based cached visible candidate.
    SideEffect { latestPublishArtworkSnapshot() }
    DisposableEffect(Unit) {
        onDispose { latestArtworkAnchorChanged(null) }
    }

    val densityValue = LocalDensity.current.density
    val latestSwipeDownStart by rememberUpdatedState(onCoverSwipeDownStart)
    val latestSwipeDownProgress by rememberUpdatedState(onCoverSwipeDownProgress)
    val latestSwipeDownEnd by rememberUpdatedState(onCoverSwipeDownEnd)
    val headerSwipeModifier = Modifier
        .onSizeChanged { headerHeightPx = it.height.toFloat().coerceAtLeast(1f) }
        .pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Main)
                val pointerId = down.id
                val start = down.position
                val velocityTracker = VelocityTracker().apply {
                    addPosition(down.uptimeMillis, down.position)
                }
                var accepted = false
                var rejected = false
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Main)
                    val change = event.changes.firstOrNull { it.id == pointerId }
                        ?: event.changes.firstOrNull()
                        ?: break
                    velocityTracker.addPosition(change.uptimeMillis, change.position)
                    val dx = change.position.x - start.x
                    val dy = change.position.y - start.y
                    if (!accepted && !rejected &&
                        (abs(dx) > viewConfiguration.touchSlop || abs(dy) > viewConfiguration.touchSlop)
                    ) {
                        if (dy > 0f && abs(dy) > abs(dx) * 1.20f) {
                            accepted = true
                            latestPublishArtworkSnapshot()
                            latestSwipeDownStart()
                        } else {
                            rejected = true
                        }
                    }
                    if (accepted) {
                        val targetDistance = maxOf(headerHeightPx * 2.8f, 320.dp.toPx())
                        latestSwipeDownProgress((dy / targetDistance).coerceIn(0f, 1f))
                        change.consume()
                    }
                    if (!change.pressed) {
                        if (accepted) {
                            val targetDistance = maxOf(headerHeightPx * 2.8f, 320.dp.toPx())
                            val progress = (dy / targetDistance).coerceIn(0f, 1f)
                            val velocityY = velocityTracker.calculateVelocity().y
                            val commit = PlayerLyricsTransitionCoordinator.shouldCommit(
                                progress = progress,
                                velocityPxPerSecond = velocityY,
                                density = densityValue,
                                expectedVelocitySign = 1
                            )
                            val settleVelocity =
                                PlayerLyricsTransitionCoordinator.settleRatioVelocity(
                                    progress = progress,
                                    commit = commit,
                                    velocityPxPerSecond = velocityY,
                                    travelDistancePx = targetDistance,
                                    expectedVelocitySign = 1
                                )
                            latestSwipeDownEnd(commit, settleVelocity)
                        }
                        break
                    }
                }
            }
        }

    fun closeLyricMore() {
        showLyricMore = false
        onModalDismissActionChange(null)
        onModalVisibleChange(false)
    }

    fun openLyricMore() {
        showLyricMore = true
        onModalDismissActionChange(::closeLyricMore)
        onModalVisibleChange(true)
    }

    DisposableEffect(Unit) {
        onDispose {
            onModalDismissActionChange(null)
            onModalVisibleChange(false)
        }
    }

    LaunchedEffect(coverPath) {
        if (!coverPath.isNullOrBlank()) {
            BitmapProvider.warmPlaybackArt(coverPath)
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            // Keep every lyric-page touch in this scene. Without a root pointer node, empty
            // lyric areas can hit the still-composed player artwork underneath.
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    var pointerPressed = true
                    while (pointerPressed) {
                        val event = awaitPointerEvent(PointerEventPass.Final)
                        pointerPressed = event.changes.any { it.pressed }
                    }
                }
            }
    ) {
        if (renderBackdrop) {
            StandardPlayerBackdrop(coverPath = coverPath, accent = accent)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .graphicsLayer { alpha = contentAlpha.coerceIn(0f, 1f) }
        ) {
            val fixedTopPadding = when (lyricTopLayoutStyle) {
                LyricTopLayoutStyle.Current -> 132.dp
                LyricTopLayoutStyle.TitleOnly -> 82.dp
                LyricTopLayoutStyle.ScrollWithLyrics -> 8.dp
            }
            // The fixed header and lyric viewport share this parent, so the measured header height
            // is already in the lyric viewport's local pixel coordinates. Fall back to the layout
            // padding only during the first unmeasured frame.
            val fixedTopMaskStartPx = fixedTopRegionHeightPx
                .takeIf { it > 1f }
                ?: (fixedTopPadding.value * densityValue)
            val scrollingHeaderContent: (@Composable () -> Unit)? =
                if (lyricTopLayoutStyle == LyricTopLayoutStyle.ScrollWithLyrics) {
                    {
                        LyricHeader(
                            currentSong = currentSong,
                            coverPath = coverPath,
                            primaryColor = lyricTextColor,
                            secondaryColor = lyricSecondaryColor,
                            tertiaryColor = lyricTertiaryColor,
                            moreIconRes = moreIconRes,
                            onMore = ::openLyricMore,
                            showCover = showHeaderCover,
                            coverGestureModifier = headerSwipeModifier,
                            onCoverBoundsChanged = { scrollingHeaderCoverRect = it },
                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp)
                        )
                    }
                } else {
                    null
                }

            ComposeLyricView(
                song = song,
                positionMs = positionMs,
                isPlaying = isPlaying,
                displayTranslation = displayTranslation,
                displayRoma = displayRoma,
                textColor = lyricTextColor,
                dimColor = lyricTextColor.copy(alpha = 0.42f),
                secondaryColor = lyricTextColor.copy(alpha = 0.68f),
                fontSizeSp = lyricFontSizeSp,
                textPosition = lyricTextPosition,
                blurEnabled = lyricBlurEnabled,
                highlightAll = lyricHighlightAllEnabled,
                karaokeGlowEnabled = karaokeGlowEnabled,
                karaokeLiftEnabled = karaokeLiftEnabled,
                topPadding = fixedTopPadding,
                bottomPadding = lyricBottomPaddingDp.dp,
                scrollingHeader = scrollingHeaderContent,
                scrollingHeaderSpacing = 18.dp,
                onScrollingHeaderVisibilityChanged = { visible ->
                    if (lyricTopLayoutStyle == LyricTopLayoutStyle.ScrollWithLyrics) {
                        onHeaderCoverVisibilityChange(visible)
                    }
                },
                onScrollingHeaderViewportChanged = { state ->
                    if (lyricTopLayoutStyle == LyricTopLayoutStyle.ScrollWithLyrics) {
                        scrollingHeaderViewport = state
                    }
                },
                onLineClick = onSeek,
                onSwipeRight = onBack,
                onSwipeRightStart = if (interactiveHorizontalSwipe) {
                    {
                        latestPublishArtworkSnapshot()
                        latestSwipeDownStart()
                    }
                } else null,
                onSwipeRightProgress = if (interactiveHorizontalSwipe) {
                    { ratio -> latestSwipeDownProgress(ratio) }
                } else null,
                onSwipeRightEnd = if (interactiveHorizontalSwipe) {
                    { commit, velocity -> latestSwipeDownEnd(commit, velocity) }
                } else null,
                modifier = Modifier
                    .fillMaxSize()
                    .then(
                        if (lyricTopLayoutStyle == LyricTopLayoutStyle.ScrollWithLyrics) {
                            Modifier
                        } else {
                            Modifier.lyricFixedTopEdgeMask(
                                headerBottomPx = fixedTopMaskStartPx,
                                holdBelowHeader = 8.dp,
                                fadeHeight = 72.dp
                            )
                        }
                    )
            )

            when (lyricTopLayoutStyle) {
                LyricTopLayoutStyle.Current -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { fixedTopRegionHeightPx = it.height.toFloat() }
                    ) {
                        LyricHeader(
                            currentSong = currentSong,
                            coverPath = coverPath,
                            primaryColor = lyricTextColor,
                            secondaryColor = lyricSecondaryColor,
                            tertiaryColor = lyricTertiaryColor,
                            moreIconRes = moreIconRes,
                            onMore = ::openLyricMore,
                            showCover = showHeaderCover,
                            coverGestureModifier = headerSwipeModifier,
                            onCoverBoundsChanged = { fixedHeaderCoverRect = it },
                            modifier = Modifier.padding(start = 18.dp, top = 14.dp, end = 18.dp)
                        )
                    }
                }
                LyricTopLayoutStyle.TitleOnly -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .onSizeChanged { fixedTopRegionHeightPx = it.height.toFloat() }
                    ) {
                        LyricTitleOnlyHeader(
                            currentSong = currentSong,
                            primaryColor = lyricTextColor,
                            secondaryColor = lyricSecondaryColor,
                            moreIconRes = moreIconRes,
                            onMore = ::openLyricMore,
                            modifier = headerSwipeModifier
                                .padding(start = 18.dp, top = 4.dp, end = 18.dp)
                        )
                    }
                }
                LyricTopLayoutStyle.ScrollWithLyrics -> Unit
            }
        }

        LyricMoreOverlayDialog(
            show = showLyricMore,
            onDismissRequest = ::closeLyricMore,
            currentSong = currentSong,
            coverPath = coverPath,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            blurEnabled = lyricBlurEnabled,
            highlightAllEnabled = lyricHighlightAllEnabled,
            fontSizeSp = lyricFontSizeSp,
            textPosition = lyricTextPosition,
            onTranslationToggle = onTranslationToggle,
            onRomaToggle = onRomaToggle,
            onBlurEnabledChange = { enabled ->
                lyricBlurEnabled = enabled
                AppPreferences.UI.lyricBlurEnabled = enabled
            },
            onHighlightAllEnabledChange = { enabled ->
                lyricHighlightAllEnabled = enabled
                AppPreferences.UI.lyricHighlightAllEnabled = enabled
            },
            onFontSizeChange = { size ->
                lyricFontSizeSp = size
                AppPreferences.UI.lyricFontSizeSp = size
            },
            onTextPositionChange = { position ->
                lyricTextPosition = position
                AppPreferences.UI.lyricTextPosition = position.value
            },
            onModifyAlbumArt = {
                closeLyricMore()
                onModifyAlbumArt()
            },
            onSearchLyrico = {
                closeLyricMore()
                actionScope.launch {
                    delay(160L)
                    onSearchLyrico()
                }
            },
            onOpenInLyrico = {
                closeLyricMore()
                actionScope.launch {
                    delay(160L)
                    onOpenInLyrico()
                }
            }
        )
    }
}

@Composable
fun LyricMoreOverlayDialog(
    show: Boolean,
    onDismissRequest: () -> Unit,
    currentSong: AudioFile?,
    coverPath: String?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    blurEnabled: Boolean,
    highlightAllEnabled: Boolean,
    fontSizeSp: Int,
    textPosition: LyricTextPosition,
    onTranslationToggle: () -> Unit,
    onRomaToggle: () -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
    onHighlightAllEnabledChange: (Boolean) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onTextPositionChange: (LyricTextPosition) -> Unit,
    onModifyAlbumArt: (() -> Unit)? = null,
    onSearchLyrico: (() -> Unit)? = null,
    onOpenInLyrico: (() -> Unit)? = null
) {
    RawMiuixOverlayDialog(
        show = show,
        onDismissRequest = onDismissRequest,
        renderInRootScaffold = true
    ) {
        LyricMoreSheet(
            currentSong = currentSong,
            coverPath = coverPath,
            displayTranslation = displayTranslation,
            displayRoma = displayRoma,
            blurEnabled = blurEnabled,
            highlightAllEnabled = highlightAllEnabled,
            fontSizeSp = fontSizeSp,
            textPosition = textPosition,
            onTranslationToggle = onTranslationToggle,
            onRomaToggle = onRomaToggle,
            onBlurEnabledChange = onBlurEnabledChange,
            onHighlightAllEnabledChange = onHighlightAllEnabledChange,
            onFontSizeChange = onFontSizeChange,
            onTextPositionChange = onTextPositionChange,
            onModifyAlbumArt = onModifyAlbumArt,
            onSearchLyrico = onSearchLyrico,
            onOpenInLyrico = onOpenInLyrico
        )
    }
}

@Composable
private fun LyricMoreSheet(
    currentSong: AudioFile?,
    coverPath: String?,
    displayTranslation: Boolean,
    displayRoma: Boolean,
    blurEnabled: Boolean,
    highlightAllEnabled: Boolean,
    fontSizeSp: Int,
    textPosition: LyricTextPosition,
    onTranslationToggle: () -> Unit,
    onRomaToggle: () -> Unit,
    onBlurEnabledChange: (Boolean) -> Unit,
    onHighlightAllEnabledChange: (Boolean) -> Unit,
    onFontSizeChange: (Int) -> Unit,
    onTextPositionChange: (LyricTextPosition) -> Unit,
    onModifyAlbumArt: (() -> Unit)?,
    onSearchLyrico: (() -> Unit)?,
    onOpenInLyrico: (() -> Unit)?
) {
    val scheme = MiuixTheme.colorScheme
    val isDark = scheme.background.luminance() < 0.5f
    val sheetColor = if (isDark) scheme.background else scheme.surface
    val cardColor = if (isDark) {
        scheme.surfaceContainerHigh.copy(alpha = 0.76f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    val fontEntry = DropdownEntry(
        items = listOf(24, 28, 32, 36, 40).map { size ->
            DropdownItem(
                text = when (size) {
                    24 -> "小"
                    28 -> "标准"
                    32 -> "大"
                    36 -> "特大"
                    else -> "超大"
                },
                summary = "${size} sp",
                selected = size == fontSizeSp,
                onClick = { onFontSizeChange(size) }
            )
        }
    )
    val positionEntry = DropdownEntry(
        items = LyricTextPosition.entries.map { position ->
            DropdownItem(
                text = when (position) {
                    LyricTextPosition.Left -> "靠左"
                    LyricTextPosition.Center -> "居中"
                    LyricTextPosition.Right -> "靠右"
                },
                selected = position == textPosition,
                onClick = { onTextPositionChange(position) }
            )
        }
    )
    val enabledEffects = buildList {
        if (displayTranslation) add(stringResource(R.string.lyric_more_effect_translation))
        if (displayRoma) add(stringResource(R.string.lyric_more_effect_romanization))
        if (highlightAllEnabled) add(stringResource(R.string.lyric_more_effect_highlight))
        if (blurEnabled) add(stringResource(R.string.lyric_more_effect_blur))
    }
    val effectsEntry = DropdownEntry(
        items = listOf(
            DropdownItem(
                text = stringResource(R.string.lyric_more_effect_translation),
                summary = stringResource(
                    if (displayTranslation) R.string.lyric_more_translation_on
                    else R.string.lyric_more_translation_off
                ),
                selected = displayTranslation,
                onClick = onTranslationToggle
            ),
            DropdownItem(
                text = stringResource(R.string.lyric_more_effect_romanization),
                summary = stringResource(
                    if (displayRoma) R.string.lyric_more_romanization_on
                    else R.string.lyric_more_romanization_off
                ),
                selected = displayRoma,
                onClick = onRomaToggle
            ),
            DropdownItem(
                text = stringResource(R.string.lyric_more_effect_highlight),
                summary = stringResource(
                    if (highlightAllEnabled) R.string.lyric_more_highlight_all_on
                    else R.string.lyric_more_highlight_all_off
                ),
                selected = highlightAllEnabled,
                onClick = { onHighlightAllEnabledChange(!highlightAllEnabled) }
            ),
            DropdownItem(
                text = stringResource(R.string.lyric_more_effect_blur),
                summary = stringResource(
                    if (blurEnabled) R.string.lyric_more_blur_on
                    else R.string.lyric_more_blur_off
                ),
                selected = blurEnabled,
                onClick = { onBlurEnabledChange(!blurEnabled) }
            )
        )
    )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(cardColor)
                ) {
                    if (!coverPath.isNullOrBlank()) {
                        com.rawsmusic.core.ui.widget.bitmaps.BitmapImage(
                            key = coverPath,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            targetWidth = 220,
                            targetHeight = 220,
                            surface = com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface.Playback
                        )
                    }
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = currentSong?.displayName ?: stringResource(R.string.player_no_song),
                        color = scheme.onSurface,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = currentSong?.artist?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.player_unknown_artist),
                        color = scheme.onSurfaceVariantSummary,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            onModifyAlbumArt?.let { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardColor)
                        .clickable(onClick = action)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(scheme.primary),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text("修改此曲专辑图", color = scheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                        Text("选择新的图片并写入当前歌曲", color = scheme.onSurfaceVariantSummary, fontSize = 13.sp)
                    }
                }
            }

            onSearchLyrico?.let { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardColor)
                        .clickable(enabled = currentSong != null, onClick = action)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(scheme.primary),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.lyric_more_lyrico_search_title),
                            color = scheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.lyric_more_lyrico_search_summary),
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            onOpenInLyrico?.let { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(cardColor)
                        .clickable(enabled = currentSong != null, onClick = action)
                        .padding(horizontal = 18.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(scheme.primary),
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(
                            text = stringResource(R.string.lyric_more_lyrico_title),
                            color = scheme.onSurface,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = stringResource(R.string.lyric_more_lyrico_summary),
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
            ) {
                WindowDropdownPreference(
                    entry = effectsEntry,
                    title = stringResource(R.string.lyric_more_effects_title),
                    summary = if (enabledEffects.isEmpty()) {
                        stringResource(R.string.lyric_more_effects_summary_none)
                    } else {
                        enabledEffects.joinToString(separator = "、")
                    },
                    showValue = true,
                    maxHeight = 420.dp,
                    collapseOnSelection = false
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
            ) {
                WindowDropdownPreference(
                    entry = fontEntry,
                    title = "歌词字号",
                    summary = "${fontSizeSp} sp",
                    showValue = true,
                    maxHeight = 360.dp,
                    collapseOnSelection = true
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(cardColor)
            ) {
                WindowDropdownPreference(
                    entry = positionEntry,
                    title = "歌词位置",
                    summary = when (textPosition) {
                        LyricTextPosition.Left -> "靠左"
                        LyricTextPosition.Center -> "居中"
                        LyricTextPosition.Right -> "靠右"
                    },
                    showValue = true,
                    maxHeight = 300.dp,
                    collapseOnSelection = true
                )
            }
        }
}

@Composable
private fun LyricHeader(
    currentSong: AudioFile?,
    coverPath: String?,
    primaryColor: Color,
    secondaryColor: Color,
    tertiaryColor: Color,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    showCover: Boolean,
    coverGestureModifier: Modifier = Modifier,
    onCoverBoundsChanged: (PlayerLyricsRect?) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val latestOnCoverBoundsChanged by rememberUpdatedState(onCoverBoundsChanged)
    DisposableEffect(Unit) {
        onDispose { latestOnCoverBoundsChanged(null) }
    }
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(
            coverPath = coverPath,
            title = currentSong?.displayName.orEmpty(),
            modifier = coverGestureModifier
                .size(104.dp)
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    latestOnCoverBoundsChanged(
                        PlayerLyricsRect(
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom
                        )
                    )
                }
                .graphicsLayer { alpha = if (showCover) 1f else 0f }
        )
        Spacer(Modifier.width(18.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentSong?.displayName ?: stringResource(R.string.player_no_song),
                color = primaryColor,
                fontSize = 25.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistLine(currentSong),
                color = secondaryColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = lyricAudioMeta(currentSong),
                color = tertiaryColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(58.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.10f))
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(moreIconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(secondaryColor),
                modifier = Modifier.size(28.dp)
            )
        }
    }
}

@Composable
private fun LyricTitleOnlyHeader(
    currentSong: AudioFile?,
    primaryColor: Color,
    secondaryColor: Color,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(70.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = currentSong?.displayName ?: stringResource(R.string.player_no_song),
                color = primaryColor,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistLine(currentSong),
                color = secondaryColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Spacer(Modifier.width(12.dp))
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(primaryColor.copy(alpha = 0.10f))
                .clickable(onClick = onMore),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(moreIconRes),
                contentDescription = null,
                colorFilter = ColorFilter.tint(secondaryColor),
                modifier = Modifier.size(25.dp)
            )
        }
    }
}

private fun Modifier.lyricFixedTopEdgeMask(
    headerBottomPx: Float,
    holdBelowHeader: Dp = 8.dp,
    fadeHeight: Dp = 72.dp
): Modifier = graphicsLayer {
    compositingStrategy = CompositingStrategy.Offscreen
}.drawWithCache {
    // Salt-style local alpha edge: the fixed header stays transparent and the lyric layer alone
    // is clipped beneath it. The mask becomes fully opaque after a short local band, so it never
    // behaves like a full-screen colored veil.
    val hiddenEndPx = (headerBottomPx + holdBelowHeader.toPx())
        .coerceIn(0f, size.height)
    val fadeEndPx = (hiddenEndPx + fadeHeight.toPx())
        .coerceIn(hiddenEndPx, size.height)
    val hiddenStop = if (size.height > 0f) hiddenEndPx / size.height else 0f
    val fadeStop = if (size.height > 0f) fadeEndPx / size.height else 1f
    val fadeSpan = (fadeStop - hiddenStop).coerceAtLeast(0f)
    val mask = Brush.verticalGradient(
        colorStops = arrayOf(
            0f to Color.Transparent,
            hiddenStop to Color.Transparent,
            (hiddenStop + fadeSpan * 0.22f) to Color.Black.copy(alpha = 0.06f),
            (hiddenStop + fadeSpan * 0.48f) to Color.Black.copy(alpha = 0.28f),
            (hiddenStop + fadeSpan * 0.74f) to Color.Black.copy(alpha = 0.68f),
            fadeStop to Color.Black,
            1f to Color.Black
        ),
        startY = 0f,
        endY = size.height
    )

    onDrawWithContent {
        drawContent()
        drawRect(brush = mask, blendMode = BlendMode.DstIn)
    }
}

@Composable
private fun LyricMiniNowPlaying(
    currentSong: AudioFile?,
    coverPath: String?,
    @DrawableRes moreIconRes: Int,
    onMore: () -> Unit,
    showCover: Boolean,
    primaryColor: Color = Color.White,
    secondaryColor: Color = Color.White.copy(alpha = 0.82f)
) {
    Row(
        modifier = Modifier
            .height(72.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(primaryColor.copy(alpha = 0.10f))
            .clickable(onClick = onMore)
            .padding(start = 10.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CoverThumb(
            coverPath = coverPath,
            title = currentSong?.displayName.orEmpty(),
            modifier = Modifier
                .size(48.dp)
                .graphicsLayer { alpha = if (showCover) 1f else 0f },
            radius = 14
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.width(190.dp)) {
            Text(
                text = currentSong?.displayName ?: stringResource(R.string.player_no_song),
                color = primaryColor,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = artistLine(currentSong),
                color = secondaryColor,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        Image(
            painter = painterResource(moreIconRes),
            contentDescription = null,
            colorFilter = ColorFilter.tint(secondaryColor),
            modifier = Modifier.size(26.dp)
        )
    }
}

@Composable
private fun CoverThumb(
    coverPath: String?,
    title: String,
    modifier: Modifier = Modifier,
    radius: Int = 24
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(radius.dp))
            .background(Color.White.copy(alpha = 0.10f)),
        contentAlignment = Alignment.Center
    ) {
        if (!coverPath.isNullOrBlank()) {
            CrossfadeAlbumArt(
                key = coverPath,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                lowResSize = 192,
                hiResSize = 512,
                fadeMillis = 120,
                holdPreviousOnKeyChange = true,
                priority = com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest.Priority.LOADING_WIDGET
            )
        } else {
            Text("*", color = Color.White.copy(alpha = 0.5f), fontSize = 22.sp)
        }
    }
}

private fun artistLine(song: AudioFile?): String {
    val artist = song?.artist?.takeIf { it.isNotBlank() } ?: return ""
    val album = song.album.takeIf { it.isNotBlank() }
    return listOfNotNull(artist, album).joinToString(" - ")
}

private fun lyricAudioMeta(song: AudioFile?): String {
    val audio = song
    val duration = audio?.duration?.takeIf { it > 0 }?.let {
        val seconds = it / 1000L
        "${seconds / 60}:${(seconds % 60).toString().padStart(2, '0')}"
    }
    val format = audio?.format?.takeIf { it.isNotBlank() }?.lowercase()
    val sample = audio?.let { current ->
        current.sampleRate.takeIf { it > 0 }?.let {
            com.rawsmusic.core.common.utils.SampleRateNormalizer.formatKhz(
                sampleRate = it,
                codecName = current.encodingFormat,
                formatName = current.format,
                filePath = current.path
            )
        }
    }?.takeIf { it.isNotBlank() }
    val bits = audio?.bitsPerSample?.takeIf { it > 0 }?.let { "$it bit" }
    return listOfNotNull(duration, format, sample, bits).joinToString(" | ")
}
