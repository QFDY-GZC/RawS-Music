package com.rawsmusic.core.ui.scene.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop
import com.rawsmusic.core.common.source.RawSourceQuality
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.ComposeMiniPlayer
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTab
import com.rawsmusic.core.ui.widget.bottombar.LiquidBottomTabs
import com.rawsmusic.module.data.source.playback.MusicSourceLyricSnapshot
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackSnapshot
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackStatus
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.IconButton
import top.yukonga.miuix.kmp.basic.SmallTopAppBar
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Music
import top.yukonga.miuix.kmp.icon.extended.Settings
import top.yukonga.miuix.kmp.theme.MiuixTheme

enum class SourcePortalTab(val labelRes: Int) {
    Sources(R.string.source_tab_sources),
    Configuration(R.string.source_tab_configuration),
    Downloads(R.string.source_tab_downloads),
}

@Composable
internal fun SourceSearchTopBar(
    query: String,
    sourceLabel: String,
    onQueryChange: (String) -> Unit,
    onSearch: () -> Unit,
    onBack: () -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val keyboard = LocalSoftwareKeyboardController.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
            Icon(painter = painterResource(R.drawable.ic_back), contentDescription = stringResource(R.string.common_back), tint = scheme.onBackground)
        }
        Spacer(Modifier.width(8.dp))
        Row(
            modifier = Modifier
                .weight(1f)
                .height(58.dp)
                .clip(RoundedCornerShape(29.dp))
                .background(scheme.surfaceContainer.copy(alpha = 0.84f)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .weight(0.20f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = sourceLabel,
                    color = scheme.primary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
            Box(
                Modifier
                    .width(1.dp)
                    .height(34.dp)
                    .background(scheme.onBackground.copy(alpha = 0.18f))
            )
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = TextStyle(color = scheme.onBackground, fontSize = 16.sp),
                cursorBrush = SolidColor(scheme.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    keyboard?.hide()
                    onSearch()
                }),
                modifier = Modifier
                    .weight(0.80f)
                    .padding(start = 14.dp),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isBlank()) {
                            Text(stringResource(R.string.source_search_hint), color = scheme.onSurfaceVariantSummary, fontSize = 14.sp)
                        }
                        inner()
                    }
                },
            )
            if (query.isNotBlank()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(painter = painterResource(R.drawable.ic_close), contentDescription = stringResource(R.string.common_clear_search), tint = scheme.onSurfaceVariantSummary)
                }
            }
            IconButton(onClick = {
                keyboard?.hide()
                onSearch()
            }) {
                Icon(painter = painterResource(R.drawable.ic_search), contentDescription = stringResource(R.string.common_search), tint = scheme.onSurfaceVariantSummary)
            }
            Spacer(Modifier.width(4.dp))
        }
    }
}

@Composable
internal fun SourceSectionTopBar(title: String, onBack: () -> Unit) {
    val scheme = MiuixTheme.colorScheme
    SmallTopAppBar(
        title = title,
        color = Color.Transparent,
        titleColor = scheme.onBackground,
        navigationIcon = {
            IconButton(onClick = onBack) {
                Icon(
                    painter = painterResource(R.drawable.ic_back),
                    contentDescription = stringResource(R.string.common_back),
                    tint = scheme.onBackground,
                )
            }
        },
    )
}

@Composable
internal fun SourceOnlineMiniPlayer(
    backdrop: Backdrop?,
    snapshot: MusicSourcePlaybackSnapshot,
    lyric: MusicSourceLyricSnapshot,
    artworkPaths: Map<String, String>,
    onOpenPlayer: () -> Unit,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val item = snapshot.currentItem
    val previous = snapshot.queue.getOrNull(snapshot.currentIndex - 1)
    val next = snapshot.queue.getOrNull(snapshot.currentIndex + 1)
    val title = item?.title ?: stringResource(R.string.source_no_playback)
    val lyricIndex = if (item != null && lyric.itemIdentity == item.stableIdentity) {
        lyric.currentLineIndex(snapshot.positionMs)
    } else -1
    val lyricLine = lyric.lines.getOrNull(lyricIndex)
    val artist = when (snapshot.status) {
        MusicSourcePlaybackStatus.Resolving -> stringResource(R.string.source_resolving)
        MusicSourcePlaybackStatus.Preparing -> stringResource(R.string.source_connecting)
        MusicSourcePlaybackStatus.Error -> snapshot.error.ifBlank { stringResource(R.string.source_playback_failed) }
        else -> item?.artists?.joinToString(" / ")?.ifBlank { stringResource(R.string.source_unknown_artist) }
            ?: stringResource(R.string.source_standalone_queue)
    }
    ComposeMiniPlayer(
        title = title,
        artist = artist,
        lyricText = lyricLine?.text.orEmpty(),
        lyricTranslation = lyricLine?.translation.orEmpty(),
        isPlaying = snapshot.isPlaying,
        progress = snapshot.progressFraction,
        coverPath = item?.let { artworkPaths[it.stableIdentity] },
        contentIdentity = item?.stableIdentity,
        previousTitle = previous?.title,
        previousArtist = previous?.artists?.joinToString(" / ").orEmpty(),
        previousCoverPath = previous?.let { artworkPaths[it.stableIdentity] },
        previousIdentity = previous?.stableIdentity,
        nextTitle = next?.title,
        nextArtist = next?.artists?.joinToString(" / ").orEmpty(),
        nextCoverPath = next?.let { artworkPaths[it.stableIdentity] },
        nextIdentity = next?.stableIdentity,
        queueCurrentIndex = snapshot.currentIndex,
        queueSize = snapshot.queue.size,
        backdrop = backdrop,
        animateArtwork = true,
        onClick = onOpenPlayer,
        onPlayPause = onPlayPause,
        onSkipPrevious = onPrevious,
        onSkipNext = onNext,
        modifier = modifier,
    )
}

@Composable
internal fun SourcePortalBottomNavigation(
    selectedTab: SourcePortalTab,
    onSelect: (SourcePortalTab) -> Unit,
    backdrop: Backdrop?,
    modifier: Modifier = Modifier,
) {
    val isLight = !isSystemInDarkTheme()
    val contentColor = if (isLight) Color.Black else Color.White
    val selectedIndex = SourcePortalTab.entries.indexOf(selectedTab).coerceAtLeast(0)
    if (backdrop != null) {
        LiquidBottomTabs(
            selectedTabIndex = selectedIndex,
            onTabSelected = { index -> SourcePortalTab.entries.getOrNull(index)?.let(onSelect) },
            backdrop = backdrop,
            tabsCount = SourcePortalTab.entries.size,
            modifier = modifier,
        ) {
            SourcePortalTab.entries.forEach { tab ->
                LiquidBottomTab(onClick = { onSelect(tab) }) {
                    SourcePortalTabIcon(tab, contentColor, Modifier.size(22.dp))
                    Text(stringResource(tab.labelRes), color = contentColor, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    } else {
        val scheme = MiuixTheme.colorScheme
        Row(
            modifier = modifier
                .height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(scheme.surfaceContainer.copy(alpha = 0.78f))
                .padding(4.dp),
        ) {
            SourcePortalTab.entries.forEach { tab ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(24.dp))
                        .background(if (selectedTab == tab) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelect(tab) },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    SourcePortalTabIcon(
                        tab = tab,
                        tint = if (selectedTab == tab) scheme.primary else scheme.onBackground,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.height(3.dp))
                    Text(stringResource(tab.labelRes), color = scheme.onBackground, fontSize = 10.sp)
                }
            }
        }
    }
}

@Composable
internal fun RawSourceQuality.sourceQualityLabel(): String = stringResource(when (this) {
    RawSourceQuality.Standard -> R.string.source_quality_standard
    RawSourceQuality.High -> R.string.source_quality_high
    RawSourceQuality.Super -> R.string.source_quality_super
    RawSourceQuality.Lossless -> R.string.source_quality_lossless
    RawSourceQuality.HiRes -> R.string.source_quality_hires
})

@Composable
private fun SourcePortalTabIcon(
    tab: SourcePortalTab,
    tint: Color,
    modifier: Modifier = Modifier,
) {
    when (tab) {
        SourcePortalTab.Sources -> Icon(
            imageVector = MiuixIcons.Regular.Music,
            contentDescription = stringResource(tab.labelRes),
            tint = tint,
            modifier = modifier,
        )
        SourcePortalTab.Configuration -> Icon(
            imageVector = MiuixIcons.Regular.Settings,
            contentDescription = stringResource(tab.labelRes),
            tint = tint,
            modifier = modifier,
        )
        SourcePortalTab.Downloads -> Icon(
            painter = painterResource(R.drawable.ic_source_download),
            contentDescription = stringResource(tab.labelRes),
            tint = tint,
            modifier = modifier,
        )
    }
}


@Composable
internal fun SourceQualitySelectionDialog(
    title: String,
    qualities: Set<RawSourceQuality>,
    selectedQuality: RawSourceQuality?,
    summary: String = "",
    onDismiss: () -> Unit,
    onSelect: (RawSourceQuality) -> Unit,
) {
    val scheme = MiuixTheme.colorScheme
    val available = sourceQualityOrder.filter { it in qualities.ifEmpty { setOf(RawSourceQuality.Standard) } }
    RawMiuixOverlayDialog(
        show = true,
        title = title,
        summary = summary.takeIf { it.isNotBlank() },
        backgroundColor = scheme.surface,
        onDismissRequest = onDismiss,
        renderInRootScaffold = true,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            available.forEach { quality ->
                val selected = quality == selectedQuality
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(if (selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
                        .clickable { onSelect(quality) }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(
                            if (selected) R.drawable.ic_select_checked else R.drawable.ic_select_unchecked
                        ),
                        contentDescription = null,
                        tint = if (selected) scheme.primary else scheme.onSurfaceVariantSummary,
                        modifier = Modifier.size(21.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = quality.sourceQualityLabel(),
                            color = scheme.onBackground,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = quality.sourceQualitySummary(),
                            color = scheme.onSurfaceVariantSummary,
                            fontSize = 11.sp,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            TextButton(
                text = stringResource(R.string.common_cancel),
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

private val sourceQualityOrder = listOf(
    RawSourceQuality.Standard,
    RawSourceQuality.High,
    RawSourceQuality.Super,
    RawSourceQuality.Lossless,
    RawSourceQuality.HiRes,
)

@Composable
private fun RawSourceQuality.sourceQualitySummary(): String = stringResource(when (this) {
    RawSourceQuality.Standard -> R.string.source_quality_standard_summary
    RawSourceQuality.High -> R.string.source_quality_high_summary
    RawSourceQuality.Super -> R.string.source_quality_super_summary
    RawSourceQuality.Lossless -> R.string.source_quality_lossless_summary
    RawSourceQuality.HiRes -> R.string.source_quality_hires_summary
})
