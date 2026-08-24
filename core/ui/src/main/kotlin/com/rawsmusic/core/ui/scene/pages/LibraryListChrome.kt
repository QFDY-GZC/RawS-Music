package com.rawsmusic.core.ui.scene.pages

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateContentSize
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import com.rawsmusic.core.ui.scene.LocalSceneChromeAlpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.zIndex
import com.rawsmusic.core.ui.scene.BottomChromeScrollState
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.LocalAppHazeState
import com.rawsmusic.core.ui.scene.LocalBottomChromeScrollState
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.BottomBarStyle
import com.rawsmusic.module.data.prefs.TopChromeStyle
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.Search
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.basic.Text
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

internal val LIBRARY_TOOLBAR_CONTENT_HEIGHT = 72.dp
internal val LIBRARY_SEARCH_TOOLBAR_CONTENT_HEIGHT = 120.dp
// The chrome tail is intentionally 8dp taller than the toolbar controls. Keep the same guard in
// the list geometry so the first item is not clipped by the tail during the initial layout.
internal val LIBRARY_CONTENT_TOP_GUARD = 8.dp
internal val LIBRARY_CONTENT_OVERLAP = 0.dp
internal val LIBRARY_CONTENT_MIN_INSET = 8.dp

internal const val TOP_CHROME_COLLAPSE_DISTANCE_DP = 80f
private const val FLOATING_ACTION_START_PROGRESS = 0.55f
private const val FLOATING_ACTION_END_PROGRESS = 0.85f
internal const val FLOATING_ACTION_MIN_SCALE = 0.82f

internal fun floatingActionVisibilityProgress(scrollProgress: Float): Float =
    ((scrollProgress - FLOATING_ACTION_START_PROGRESS) /
        (FLOATING_ACTION_END_PROGRESS - FLOATING_ACTION_START_PROGRESS))
        .coerceIn(0f, 1f)

@Immutable
data class LibraryChromeInfo(
    val nowPlayingTitle: String = "",
    val nextSongTitle: String = "",
    val showNextQueueHint: Boolean = false,
    val onSearch: () -> Unit = {},
    val onOpenFolderPicker: () -> Unit = {},
    val currentSortOrder: SortOrder = SortOrder.TITLE_ASC,
    val onSortSelected: (SortOrder) -> Unit = {},
    val metadataMatchSources: List<MetadataMatchSourceUi> = emptyList(),
    val metadataMatchProgressText: String = "",
    val onMoveMetadataSource: (String, Int) -> Unit = { _, _ -> },
    val onAutoMatchCurrent: () -> Unit = {},
    val onAutoRematchAll: () -> Unit = {},
)

val LocalLibraryChromeInfo = staticCompositionLocalOf { LibraryChromeInfo() }

/** Shared glass header for every root library collection. */
@Composable
fun LibraryListScaffold(
    title: String,
    sceneId: String,
    onBack: () -> Unit,
    powerListState: ComposePowerListState? = null,
    onSelect: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onCreatePlaylist: (() -> Unit)? = null,
    onImportPlaylist: (() -> Unit)? = null,
    currentSortOrder: SortOrder? = null,
    onSortSelected: ((SortOrder) -> Unit)? = null,
    sortOptions: List<Pair<String, SortOrder>>? = null,
    contentOverlap: Dp = LIBRARY_CONTENT_OVERLAP,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.(contentTopPadding: Dp, backdropSource: Modifier) -> Unit
) {
    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val toolbarHeight = statusBarTop + LIBRARY_TOOLBAR_CONTENT_HEIGHT
    val contentTopPadding = (toolbarHeight + LIBRARY_CONTENT_TOP_GUARD - contentOverlap)
        .coerceAtLeast(statusBarTop + LIBRARY_CONTENT_MIN_INSET)
    val localHazeState = rememberHazeState()
    val hazeState = LocalAppHazeState.current ?: localHazeState
    val density = LocalDensity.current
    val bottomChromeScrollState = LocalBottomChromeScrollState.current
    val windowInfo = LocalWindowInfo.current
    val topChromeStyle by PersonalizationPreferences.topChromeStyle.collectAsState()
    val bottomBarStyle by PersonalizationPreferences.bottomBarStyle.collectAsState()
    val floatingTopChrome = topChromeStyle == TopChromeStyle.FLOATING ||
        topChromeStyle == TopChromeStyle.FLOATING_VERTICAL
    val floatingTopChromeVertical = topChromeStyle == TopChromeStyle.FLOATING_VERTICAL
    val transparentTopChrome = topChromeStyle == TopChromeStyle.TRANSPARENT
    val blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
        !transparentTopChrome && !floatingTopChrome
    val isLight = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background.luminance() > 0.5f
    val topChromeCollapseDistancePx = with(density) {
        TOP_CHROME_COLLAPSE_DISTANCE_DP.dp.toPx()
    }
    val overlayProgress = (
        (powerListState?.viewportScrollYPx ?: 0f) /
            topChromeCollapseDistancePx
    ).coerceIn(0f, 1f)
    val overlayProgressState = rememberUpdatedState(overlayProgress)
    val floatingActionProgress = if (floatingTopChrome) {
        floatingActionVisibilityProgress(overlayProgress)
    } else 0f
    val floatingActionBottomPadding = bottomChromeScrollState
        ?.restingTopInRootPx
        ?.takeIf { it > 0f }
        ?.let { topInRootPx ->
            with(density) {
                ((windowInfo.containerSize.height - topInRootPx).toDp() +
                    10.dp)
                    .coerceAtLeast(72.dp)
            }
        }
        ?: 126.dp
    val chromeInfo = LocalLibraryChromeInfo.current
    val sceneTopMenuAlpha = LocalSceneChromeAlpha.current.topMenu
    var showSortSheet by remember { mutableStateOf(false) }
    var showMoreDialog by remember { mutableStateOf(false) }
    val backdropSource = if (blurEnabled) Modifier.hazeSource(hazeState) else Modifier
    // The floating style is a replacement for the top controls, not a request to move the
    // first row underneath them. Keep the same inset as every other chrome style so the first
    // item never appears below an empty toolbar area.
    val contentInset = contentTopPadding

    Box(
        modifier = modifier
            .fillMaxSize()
            .graphicsLayer { clip = false },
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (transparentTopChrome) {
                        Modifier.drawWithContent {
                            // Keep list artwork below the controls while exposing the exact same
                            // persistent background above it. No independent toolbar color layer.
                            clipRect(top = (toolbarHeight + LIBRARY_CONTENT_TOP_GUARD).toPx()) {
                                this@drawWithContent.drawContent()
                            }
                        }
                    } else {
                        Modifier
                    }
                )
        ) {
            content(contentInset, backdropSource)
        }

        run {
            val topMenuVisibility = if (floatingTopChrome) {
                1f - overlayProgress
            } else {
                1f
            }
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(40f)
            ) {
                TopGradientGlassTail(
                    hazeState = hazeState,
                    blurEnabled = blurEnabled,
                    isLight = isLight,
                    transparentStyle = transparentTopChrome || floatingTopChrome,
                    overlayProgress = overlayProgressState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(toolbarHeight + 8.dp)
                        .graphicsLayer { alpha = sceneTopMenuAlpha * topMenuVisibility }
                )

                SongsTopMenuBar(
                title = title,
                sceneId = sceneId,
                nowPlayingTitle = chromeInfo.nowPlayingTitle,
                nextSongTitle = chromeInfo.nextSongTitle,
                showNextQueueHint = chromeInfo.showNextQueueHint,
                metadataMatchProgressText = chromeInfo.metadataMatchProgressText,
                isSearchActive = false,
                searchQuery = "",
                onSearchQueryChange = {},
                onToggleSearch = chromeInfo.onSearch,
                onCancelSearch = {},
                selectionMode = false,
                selectedCount = 0,
                onCancelSelection = {},
                onSelectAll = {},
                onBack = onBack,
                onMoreClick = { showMoreDialog = true },
                onShuffleAll = { onShuffle?.invoke() },
                onCreatePlaylist = onCreatePlaylist,
                onImportPlaylist = onImportPlaylist,
                isLight = isLight,
                overlayProgress = overlayProgress,
                backdropBlurEnabled = blurEnabled,
                transparentStyle = transparentTopChrome || floatingTopChrome,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer { alpha = sceneTopMenuAlpha * topMenuVisibility }
                )
            }
        }

        FloatingLibraryActionBar(
            onSelect = onSelect,
            onSearch = chromeInfo.onSearch,
            onShuffle = onShuffle,
            onMore = { showMoreDialog = true },
            onCreatePlaylist = onCreatePlaylist,
            onImportPlaylist = onImportPlaylist,
            vertical = floatingTopChromeVertical,
            visibilityProgress = floatingActionProgress,
            transitionAlpha = sceneTopMenuAlpha,
            bottomPadding = floatingActionBottomPadding,
            bottomOffsetPx = {
                bottomChromeScrollState?.renderFollowerOffsetPx(
                    normalStyle = bottomBarStyle == BottomBarStyle.NORMAL,
                ) ?: 0f
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = if (floatingTopChromeVertical) 40.dp else 56.dp)
                .zIndex(45f),
        )

        LibraryMoreDialog(
            visible = showMoreDialog,
            sourceOrder = chromeInfo.metadataMatchSources,
            onDismiss = { showMoreDialog = false },
            onChooseFolder = chromeInfo.onOpenFolderPicker,
            onOpenSort = { showSortSheet = true },
            onMoveSource = chromeInfo.onMoveMetadataSource,
            onAutoMatchCurrent = chromeInfo.onAutoMatchCurrent,
            onRematchAll = chromeInfo.onAutoRematchAll,
        )

        powerListState?.let { state ->
            SongsSortLayoutSheet(
                visible = showSortSheet,
                currentSortOrder = currentSortOrder ?: chromeInfo.currentSortOrder,
                powerListState = state,
                onSortSelected = { order ->
                    (onSortSelected ?: chromeInfo.onSortSelected)(order)
                },
                sortOptions = sortOptions,
                onDismiss = { showSortSheet = false }
            )
        }
    }
}

/**
 * Stable action host for the floating top-menu style.
 *
 * Navigation and action slots stay mounted while list content changes. This host deliberately
 * owns no list state and uses fixed button slots, so scrolling cannot recreate or move actions.
 */
@Composable
internal fun FloatingLibraryActionBar(
    onSelect: (() -> Unit)?,
    onSearch: () -> Unit,
    onShuffle: (() -> Unit)?,
    onMore: () -> Unit,
    onCreatePlaylist: (() -> Unit)?,
    onImportPlaylist: (() -> Unit)?,
    vertical: Boolean = false,
    visibilityProgress: Float = 1f,
    transitionAlpha: Float = 1f,
    bottomPadding: Dp = 126.dp,
    bottomOffsetPx: () -> Float = { 0f },
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val iconTint = scheme.onSurface
    val easedProgress = smoothStep(visibilityProgress)
    var collapsed by remember { mutableStateOf(false) }
    val actionContentModifier = Modifier
        .graphicsLayer { clip = false }
        .animateContentSize(
            animationSpec = spring(
                dampingRatio = Spring.DampingRatioNoBouncy,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    val actionContent: @Composable () -> Unit = {
        FloatingLibraryActionBarContent(
            onSelect = onSelect,
            onSearch = onSearch,
            onShuffle = onShuffle,
            onMore = onMore,
            onCreatePlaylist = onCreatePlaylist,
            onImportPlaylist = onImportPlaylist,
            iconTint = iconTint,
            vertical = vertical,
            visibilityProgress = easedProgress,
            transitionAlpha = transitionAlpha,
            collapsed = collapsed,
            onToggleCollapsed = { collapsed = !collapsed },
        )
    }
    Box(
        modifier = modifier
            .padding(bottom = bottomPadding)
            .offset { IntOffset(0, bottomOffsetPx().roundToInt().coerceAtLeast(0)) }
            .graphicsLayer { clip = false },
    ) {
        if (vertical) {
            Column(
                modifier = actionContentModifier,
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                actionContent()
            }
        } else {
            Row(
                modifier = actionContentModifier,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actionContent()
            }
        }
    }
}

private fun smoothStep(value: Float): Float {
    val t = value.coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

@Composable
private fun FloatingLibraryActionBarContent(
    onSelect: (() -> Unit)?,
    onSearch: () -> Unit,
    onShuffle: (() -> Unit)?,
    onMore: () -> Unit,
    onCreatePlaylist: (() -> Unit)?,
    onImportPlaylist: (() -> Unit)?,
    iconTint: androidx.compose.ui.graphics.Color,
    vertical: Boolean,
    visibilityProgress: Float,
    transitionAlpha: Float,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    // Keep the collapse control in a fixed first slot. When the group is
    // collapsed it must not jump to the old last-action position.
    FloatingLibraryIcon(
        icon = {
            AnimatedContent(
                targetState = collapsed,
                transitionSpec = {
                    (fadeIn(tween(180)) + scaleIn(initialScale = 0.72f, animationSpec = tween(220))) togetherWith
                        (fadeOut(tween(140)) + scaleOut(targetScale = 0.72f, animationSpec = tween(180)))
                },
                label = "floating-actions-collapse-icon",
            ) { isCollapsed ->
                Icon(
                    painter = painterResource(
                        if (isCollapsed) R.drawable.ic_floating_actions_expand
                        else R.drawable.ic_floating_actions_collapse
                    ),
                    contentDescription = stringResource(R.string.library_action_collapse),
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        onClick = onToggleCollapsed,
        visibilityProgress = visibilityProgress,
        transitionAlpha = transitionAlpha,
    )

    AnimatedVisibility(
        visible = !collapsed,
        modifier = Modifier.graphicsLayer { clip = false },
        enter = if (vertical) {
            expandVertically(expandFrom = Alignment.Top, clip = false, animationSpec = tween(260)) +
                fadeIn(animationSpec = tween(220)) +
                scaleIn(initialScale = 0.88f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f), animationSpec = tween(240))
        } else {
            expandHorizontally(expandFrom = Alignment.Start, clip = false, animationSpec = tween(260)) +
                fadeIn(animationSpec = tween(220)) +
                scaleIn(initialScale = 0.88f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f), animationSpec = tween(240))
        },
        exit = if (vertical) {
            shrinkVertically(shrinkTowards = Alignment.Top, clip = false, animationSpec = tween(220)) +
                fadeOut(animationSpec = tween(160)) +
                scaleOut(targetScale = 0.88f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f), animationSpec = tween(200))
        } else {
            shrinkHorizontally(shrinkTowards = Alignment.Start, clip = false, animationSpec = tween(220)) +
                fadeOut(animationSpec = tween(160)) +
                scaleOut(targetScale = 0.88f, transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0f, 0.5f), animationSpec = tween(200))
        },
    ) {
        val actions: @Composable () -> Unit = {
        onSelect?.let {
            FloatingLibraryTextAction(
                text = if (vertical) {
                    stringResource(R.string.library_action_select_vertical)
                } else {
                    stringResource(R.string.library_action_select)
                },
                vertical = vertical,
                onClick = it,
                visibilityProgress = visibilityProgress,
                transitionAlpha = transitionAlpha,
            )
        }
        FloatingLibraryIcon(
            icon = { Icon(imageVector = MiuixIcons.Basic.Search, contentDescription = stringResource(R.string.library_action_search), tint = iconTint) },
            onClick = onSearch,
            visibilityProgress = visibilityProgress,
            transitionAlpha = transitionAlpha,
        )
        onShuffle?.let {
            FloatingLibraryIcon(
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_shuffle_custom),
                        contentDescription = stringResource(R.string.songs_shuffle_all),
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = it,
                visibilityProgress = visibilityProgress,
                transitionAlpha = transitionAlpha,
            )
        }
        onCreatePlaylist?.let {
            FloatingLibraryIcon(
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_add_outline),
                        contentDescription = stringResource(R.string.playlist_action_create),
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = it,
                visibilityProgress = visibilityProgress,
                transitionAlpha = transitionAlpha,
            )
        }
        onImportPlaylist?.let {
            FloatingLibraryIcon(
                icon = {
                    Icon(
                        painter = painterResource(R.drawable.ic_playlist_import),
                        contentDescription = stringResource(R.string.playlist_action_import),
                        tint = iconTint,
                        modifier = Modifier.size(22.dp),
                    )
                },
                onClick = it,
                visibilityProgress = visibilityProgress,
                transitionAlpha = transitionAlpha,
            )
        }
        FloatingLibraryIcon(
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_library_more),
                    contentDescription = stringResource(R.string.common_more_operations),
                    tint = iconTint,
                    modifier = Modifier.size(22.dp),
                )
            },
            onClick = onMore,
            visibilityProgress = visibilityProgress,
            transitionAlpha = transitionAlpha,
        )
        }
        if (vertical) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { actions() }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                actions()
            }
        }
    }
}

@Composable
private fun FloatingLibraryTextAction(
    text: String,
    vertical: Boolean,
    onClick: () -> Unit,
    visibilityProgress: Float,
    transitionAlpha: Float,
) {
    val density = LocalDensity.current
    val scheme = MiuixTheme.colorScheme
    val visualProgress = (visibilityProgress * transitionAlpha.coerceIn(0f, 1f))
        .coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .height(if (vertical) 52.dp else 44.dp)
            .widthIn(min = if (vertical) 44.dp else 52.dp)
            .graphicsLayer {
                alpha = visualProgress
                scaleX = FLOATING_ACTION_MIN_SCALE +
                    (1f - FLOATING_ACTION_MIN_SCALE) * visualProgress
                scaleY = scaleX
                translationY = with(density) { (1f - visualProgress) * 8.dp.toPx() }
            }
            .shadow(8.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(scheme.surfaceContainer.copy(alpha = 0.88f))
            .clickable(enabled = visualProgress > 0.001f, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = scheme.onSurface,
            lineHeight = if (vertical) 18.sp else 22.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FloatingLibraryIcon(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    visibilityProgress: Float,
    transitionAlpha: Float,
) {
    val density = LocalDensity.current
    val scheme = MiuixTheme.colorScheme
    val sceneProgress = transitionAlpha.coerceIn(0f, 1f)
    val visualProgress = (visibilityProgress * sceneProgress).coerceIn(0f, 1f)
    val enabled = visualProgress > 0.001f
    Box(
        modifier = Modifier
            .size(44.dp)
            .graphicsLayer {
                alpha = visualProgress
                scaleX = FLOATING_ACTION_MIN_SCALE +
                    (1f - FLOATING_ACTION_MIN_SCALE) * visualProgress
                scaleY = scaleX
                translationY = with(density) { (1f - visualProgress) * 8.dp.toPx() }
            }
            .shadow(8.dp, CircleShape, clip = false)
            .clip(CircleShape)
            .background(scheme.surfaceContainer.copy(alpha = 0.88f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        icon()
    }
}
