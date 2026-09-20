package com.rawsmusic.core.ui.scene.pages

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.drawscope.clipRect
import com.rawsmusic.core.ui.scene.LocalReferenceRetainedLibraryPopulation
import com.rawsmusic.core.ui.scene.LocalSceneChromeAlpha
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryFullScenePreflight
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryTopChromeOverlayOnly
import com.rawsmusic.core.ui.scene.LocalExternalRetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.SceneChromeAlpha
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import com.rawsmusic.core.ui.scene.LocalBottomChromeEnvironment
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.LocalAppHazeState
import com.rawsmusic.core.ui.scene.LocalBottomChromeScrollState
import com.rawsmusic.core.ui.widget.flow.usesReferenceStaticForeground
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListPresentationTopClipInset
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListScrollProgressObserver
import com.rawsmusic.module.data.prefs.PersonalizationPreferences
import com.rawsmusic.module.data.prefs.TopChromeStyle
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsMode
import com.rawsmusic.module.data.prefs.LibraryBottomButtonsSurface
import com.rawsmusic.module.data.prefs.GlobalLiquidGlassSettings
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.highlight.Highlight
import com.kyant.backdrop.shadow.InnerShadow
import com.kyant.backdrop.shadow.Shadow
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeInputScale
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import dev.chrisbanes.haze.blur.HazeColorEffect
import dev.chrisbanes.haze.blur.blurEffect
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

internal enum class LibraryTopChromeSceneRole {
    IDLE,
    EXIT,
    ENTER,
}

internal fun SceneChromeAlpha.topChromeRole(sceneId: String): LibraryTopChromeSceneRole = when {
    topMenuExitProgressProvider == null -> LibraryTopChromeSceneRole.IDLE
    sceneId == topMenuExitSceneId -> LibraryTopChromeSceneRole.EXIT
    sceneId == topMenuEnterSceneId -> LibraryTopChromeSceneRole.ENTER
    else -> LibraryTopChromeSceneRole.IDLE
}

internal fun LibraryTopChromeSceneRole.visibilityAt(progress: Float): Float = when (this) {
    LibraryTopChromeSceneRole.EXIT -> 1f - progress
    LibraryTopChromeSceneRole.ENTER -> progress
    LibraryTopChromeSceneRole.IDLE -> 1f
}.coerceIn(0f, 1f)

internal fun LibraryTopChromeSceneRole.scaleAt(progress: Float): Float = when (this) {
    LibraryTopChromeSceneRole.EXIT -> 1f - 0.25f * progress
    LibraryTopChromeSceneRole.ENTER -> 0.75f + 0.25f * progress
    LibraryTopChromeSceneRole.IDLE -> 1f
}

internal fun LibraryTopChromeSceneRole.translationFractionAt(progress: Float): Float = when (this) {
    LibraryTopChromeSceneRole.EXIT -> progress
    LibraryTopChromeSceneRole.ENTER -> 1f - progress
    LibraryTopChromeSceneRole.IDLE -> 0f
}
internal val LIBRARY_CONTENT_MIN_INSET = 8.dp

internal const val TOP_CHROME_COLLAPSE_DISTANCE_DP = 72f
internal const val FLOATING_ACTION_MIN_SCALE = 0.85f

internal fun floatingActionVisibilityProgress(scrollProgress: Float): Float =
    scrollProgress.coerceIn(0f, 1f)

/**
 * Transparent top chrome is an occlusion lane, not a different page layout. At rest it hides list
 * pixels under the fixed top menu while leaving the app backdrop visible. During a forward scene
 * handoff the outgoing occlusion releases progressively with the same scene clock instead of
 * switching from clipped -> unclipped in one frame.
 */
internal fun Modifier.progressiveTransparentTopChromeMask(
    topInset: Dp,
    sceneExitProgressProvider: (() -> Float)?,
): Modifier = drawWithCache {
    val topPx = topInset.toPx().coerceIn(0f, size.height)
    val revealPaint = android.graphics.Paint().apply { isAntiAlias = false }
    onDrawWithContent {
        if (topPx <= 0f) {
            drawContent()
            return@onDrawWithContent
        }

        clipRect(top = topPx) {
            this@onDrawWithContent.drawContent()
        }

        val reveal = sceneExitProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
        if (reveal <= 0f) return@onDrawWithContent

        revealPaint.alpha = (reveal * 255f).roundToInt().coerceIn(0, 255)
        val nativeCanvas = drawContext.canvas.nativeCanvas
        val checkpoint = nativeCanvas.saveLayer(0f, 0f, size.width, topPx, revealPaint)
        clipRect(bottom = topPx) {
            this@onDrawWithContent.drawContent()
        }
        nativeCanvas.restoreToCount(checkpoint)
    }
}

/**
 * Status-bar safe inset that is not affected by Compose inset consumption in parent layout nodes.
 *
 * The persistent VirtualList presentation host lives at the scene root while page chrome may already
 * have consumed the local status-bar inset. Reading only that consumed inset can therefore return
 * zero for the floating top-chrome style and let the first retained row render underneath the real
 * system status bar.  Use the root WindowInsetsCompat value as the authoritative floor and keep the
 * Compose value as a fallback for the first unattached composition.
 */
@Composable
internal fun unconsumedStatusBarTopPadding(): Dp {
    val density = LocalDensity.current
    val view = LocalView.current
    val composeTop = rawStableStatusBarTopPadding()
    val rootTopPx = ViewCompat.getRootWindowInsets(view)
        ?.getInsetsIgnoringVisibility(WindowInsetsCompat.Type.statusBars())
        ?.top
        ?: 0
    if (rootTopPx <= 0) return composeTop
    val rootTop = with(density) { rootTopPx.toDp() }
    return if (rootTop > composeTop) rootTop else composeTop
}

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
    statisticsText: String = "",
    onBack: () -> Unit,
    virtualListState: ComposeVirtualListState? = null,
    onSelect: (() -> Unit)? = null,
    onPlay: (() -> Unit)? = null,
    onShuffle: (() -> Unit)? = null,
    onCreatePlaylist: (() -> Unit)? = null,
    onImportPlaylist: (() -> Unit)? = null,
    currentSortOrder: SortOrder? = null,
    onSortSelected: ((SortOrder) -> Unit)? = null,
    sortOptions: List<Pair<String, SortOrder>>? = null,
    contentOverlap: Dp = LIBRARY_CONTENT_OVERLAP,
    modifier: Modifier = Modifier,
    allowContentBehindTopChrome: Boolean = false,
    onHeaderSearch: (() -> Unit)? = null,
    headerSearchActive: Boolean = false,
    headerSearchQuery: String = "",
    onHeaderSearchQueryChange: (String) -> Unit = {},
    onHeaderSearchCancel: () -> Unit = {},
    showHeaderMore: Boolean = true,
    showHeaderSearch: Boolean = true,
    showHeaderShuffle: Boolean = true,
    headerTrailingContent: (@Composable () -> Unit)? = null,
    content: @Composable BoxScope.(contentTopPadding: Dp, backdropSource: Modifier) -> Unit
) {
    val statusBarTop = unconsumedStatusBarTopPadding()
    val toolbarHeight = statusBarTop + LIBRARY_TOOLBAR_CONTENT_HEIGHT
    val topChromeStyle by PersonalizationPreferences.topChromeStyle.collectAsState()
    val bottomButtonsMode by PersonalizationPreferences.libraryBottomButtonsMode.collectAsState()
    val floatingStyleSelected = topChromeStyle == TopChromeStyle.FLOATING ||
        topChromeStyle == TopChromeStyle.FLOATING_VERTICAL
    val floatingChromeSelected = floatingStyleSelected &&
        bottomButtonsMode != LibraryBottomButtonsMode.DISABLED
    val headerButtonsEnabled by PersonalizationPreferences.libraryHeaderButtonsEnabled.collectAsState()
    // Whenever the floating owner is absent, the fixed top bar becomes the only action owner.
    // Restore its actions even if the old header-buttons preference was left off.
    val effectiveHeaderButtonsEnabled = headerButtonsEnabled || !floatingChromeSelected
    val bottomButtonsSurface by PersonalizationPreferences.libraryBottomButtonsSurface.collectAsState()
    val transparentTopChrome = topChromeStyle == TopChromeStyle.TRANSPARENT
    val presentationTopClipInset = if (transparentTopChrome) {
        toolbarHeight + LIBRARY_CONTENT_TOP_GUARD
    } else {
        0.dp
    }
    val contentTopPadding = if (floatingChromeSelected) {
        statusBarTop + LIBRARY_CONTENT_MIN_INSET
    } else {
        (toolbarHeight + LIBRARY_CONTENT_TOP_GUARD - contentOverlap)
            .coerceAtLeast(statusBarTop + LIBRARY_CONTENT_MIN_INSET)
    }
    if (LocalReferenceLibraryTopChromeOverlayOnly.current) {
        val chromeInfo = LocalLibraryChromeInfo.current
        LibraryTopChromeTransitionOverlay(
            title = title,
            sceneId = sceneId,
            statisticsText = statisticsText,
            virtualListState = virtualListState,
            onBack = onBack,
            onSelect = onSelect,
            onPlay = onPlay,
            onSearch = chromeInfo.onSearch,
            onShuffle = onShuffle,
            onMore = {},
            onCreatePlaylist = onCreatePlaylist,
            onImportPlaylist = onImportPlaylist,
            onHeaderSearch = onHeaderSearch,
            headerSearchActive = headerSearchActive,
            headerSearchQuery = headerSearchQuery,
            onHeaderSearchQueryChange = onHeaderSearchQueryChange,
            onHeaderSearchCancel = onHeaderSearchCancel,
            showHeaderMore = showHeaderMore,
            showHeaderSearch = showHeaderSearch,
            showHeaderShuffle = showHeaderShuffle,
            headerTrailingContent = headerTrailingContent,
            nowPlayingTitle = chromeInfo.nowPlayingTitle,
            nextSongTitle = chromeInfo.nextSongTitle,
            showNextQueueHint = chromeInfo.showNextQueueHint,
            metadataMatchProgressText = chromeInfo.metadataMatchProgressText,
        )
        return
    }
    if (LocalReferenceLibraryFullScenePreflight.current) {
        // The hidden destination preflight only needs provider metadata. Keep its cost bounded and
        // never create a second toolbar/Haze owner. The visible persistent host below is a separate
        // fixed page slot and intentionally takes the normal scaffold path.
        Box(modifier = Modifier.fillMaxSize()) {
            CompositionLocalProvider(
                LocalVirtualListPresentationTopClipInset provides presentationTopClipInset,
            ) {
                content(contentTopPadding, Modifier)
            }
        }
        return
    }
    // Provider publication is hosted by the fixed ReferenceLibraryVirtualListHost. The page still
    // needs to publish its provider here, but its chrome must remain in the same visible page slot
    // during HOME/category motion. ComposeVirtualList returns after publication-only, so executing
    // the normal scaffold does not create a second physical list; it only keeps the toolbar/Haze/
    // action owners mounted like retained-view implementation's single retained scene layout.
    val localHazeState = rememberHazeState()
    val hazeState = LocalAppHazeState.current ?: localHazeState
    val density = LocalDensity.current
    val retainedLibraryPopulation = LocalReferenceRetainedLibraryPopulation.current
    // HOME/category PivotTransition must not change the visual renderer branch at gesture start.
    // Keep the already-attached Haze path active and let the retained VirtualList transform beneath it;
    // switching blur -> fallback for one frame is visible as a full-page flash on translucent themes.
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !transparentTopChrome
    val blurActive = blurSupported
    val staticForeground = usesReferenceStaticForeground()
    val isLight = !staticForeground &&
        top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background.luminance() > 0.5f
    // retained-view implementation ties the bottom-toolbar handoff to the real header geometry rather than an arbitrary
    // early/late threshold. Raw's action header is one toolbar tall, so its exact scroll extent is 72dp.
    val topChromeCollapseDistancePx = with(density) { LIBRARY_TOOLBAR_CONTENT_HEIGHT.toPx() }
    val chromeInfo = LocalLibraryChromeInfo.current
    val sceneChrome = LocalSceneChromeAlpha.current
    val externalPlayerTransform = LocalExternalRetainedSceneItemTransform.current
    val topChromeSceneRole = sceneChrome.topChromeRole(sceneId)
    val sceneTopMenuAlpha = sceneChrome.topMenu
    val sceneTopMenuAlphaProvider = sceneChrome.topMenuProvider
    var showSortSheet by remember { mutableStateOf(false) }
    var showMoreDialog by remember { mutableStateOf(false) }
    // Keep the Haze source node attached across HOME/category motion. Detaching it at gesture
    // start rebuilds the complete content RenderNode tree even if the effect itself is hidden.
    val needsFloatingHaze = floatingChromeSelected &&
        bottomButtonsSurface == LibraryBottomButtonsSurface.FROSTED &&
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val backdropSource = if (blurSupported || needsFloatingHaze) Modifier.hazeSource(hazeState) else Modifier
    val contentInset = contentTopPadding

    PublishLibraryFloatingToolbar(
        sceneId = sceneId,
        onSelect = onSelect,
        onPlay = onPlay,
        onSearch = chromeInfo.onSearch,
        onShuffle = onShuffle,
        onMore = { showMoreDialog = true },
        onCreatePlaylist = onCreatePlaylist,
        onImportPlaylist = onImportPlaylist,
    )

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
                        Modifier.progressiveTransparentTopChromeMask(
                            topInset = toolbarHeight + LIBRARY_CONTENT_TOP_GUARD,
                            sceneExitProgressProvider = sceneChrome.topMenuExitProgressProvider,
                        )
                    } else {
                        Modifier
                    }
                )
        ) {
            CompositionLocalProvider(
                LocalVirtualListPresentationTopClipInset provides presentationTopClipInset,
            ) {
                content(contentInset, backdropSource)
            }
        }

        if (!retainedLibraryPopulation && !floatingChromeSelected) {
            // Step 3B2/3C: exact list motion is observed only by the chrome subtree. The page/body
            // composition no longer reads VirtualList exact scroll on every drag/fling pixel.
            VirtualListScrollProgressObserver(
                owner = virtualListState?.viewportScrollOwner,
                distancePx = topChromeCollapseDistancePx,
            ) { overlayProgress ->
                val overlayProgressState = rememberUpdatedState(overlayProgress)
                val topMenuVisibility = 1f
                val headerActionVisibility = if (effectiveHeaderButtonsEnabled) 1f else 0f
                val topMenuExitProgressProvider = sceneChrome.topMenuExitProgressProvider
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        // Keep the chrome host itself geometrically stable. Haze samples its source
                        // in this coordinate space; scaling/translating this parent creates an
                        // extra offscreen layer and breaks the progressive blur chain.
                        .zIndex(40f)
                ) {
                    TopGradientGlassTail(
                        hazeState = hazeState,
                        blurSupported = blurSupported,
                        blurActive = blurActive,
                        isLight = isLight,
                        // Content-behind changes only body geometry. A HAZE detail header still
                        // needs the fixed glass effect over the hero/list pixels underneath it.
                        transparentStyle = transparentTopChrome,
                        overlayProgress = overlayProgressState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(toolbarHeight + 8.dp)
                            .graphicsLayer {
                                val sceneAlpha = sceneTopMenuAlphaProvider?.invoke() ?: sceneTopMenuAlpha
                                val progress = topMenuExitProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
                                val externalAlpha = externalPlayerTransform
                                    ?.alphaProvider
                                    ?.invoke()
                                    ?.coerceIn(0f, 1f)
                                    ?: 1f
                                // Background/glass follows retained-view implementation's separate scene child: fade
                                // only. Never scale or translate the Haze effect itself.
                                alpha = sceneAlpha * topMenuVisibility *
                                    topChromeSceneRole.visibilityAt(progress) * externalAlpha
                            }
                    )

                    SongsTopMenuBar(
                        title = title,
                        sceneId = sceneId,
                        statisticsText = statisticsText,
                        nowPlayingTitle = chromeInfo.nowPlayingTitle,
                        nextSongTitle = chromeInfo.nextSongTitle,
                        showNextQueueHint = chromeInfo.showNextQueueHint,
                        metadataMatchProgressText = chromeInfo.metadataMatchProgressText,
                         searchQuery = headerSearchQuery,
                        onSearchQueryChange = onHeaderSearchQueryChange,
                        onToggleSearch = onHeaderSearch ?: chromeInfo.onSearch,
                        onCancelSearch = onHeaderSearchCancel,
                        isSearchActive = headerSearchActive,
                        selectionMode = false,
                        selectedCount = 0,
                        onCancelSelection = {},
                        onSelectAll = {},
                        onBack = onBack,
                        onMoreClick = { showMoreDialog = true },
                        onShuffleAll = { onShuffle?.invoke() },
                        headerButtonsEnabled = effectiveHeaderButtonsEnabled,
                        headerActionsAlpha = headerActionVisibility,
                        onCreatePlaylist = onCreatePlaylist,
                        onImportPlaylist = onImportPlaylist,
                        showHeaderMore = showHeaderMore,
                        showHeaderSearch = showHeaderSearch,
                        showHeaderShuffle = showHeaderShuffle,
                        headerTrailingContent = headerTrailingContent,
                        isLight = isLight,
                        overlayProgress = overlayProgress,
                        backdropBlurEnabled = blurSupported,
                        transparentStyle = transparentTopChrome,
                        sceneExitProgressProvider = topMenuExitProgressProvider,
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val sceneAlpha = sceneTopMenuAlphaProvider?.invoke() ?: sceneTopMenuAlpha
                                val externalAlpha = externalPlayerTransform
                                    ?.alphaProvider
                                    ?.invoke()
                                    ?.coerceIn(0f, 1f)
                                    ?: 1f
                                val externalScale = externalPlayerTransform
                                    ?.scaleProvider
                                    ?.invoke()
                                    ?.coerceIn(0.5f, 1.5f)
                                    ?: 1f
                                // Keep the fixed TopNav owner itself stationary. SongsTopMenuBar
                                // applies the scene transform to its logical text/icon children,
                                // matching retained-view implementation's retained scene layout child-property animation.
                                alpha = sceneAlpha * topMenuVisibility * externalAlpha
                                scaleX = externalScale
                                scaleY = externalScale
                            }
                    )
                }

            }
        }

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

        virtualListState?.let { state ->
            SongsSortLayoutSheet(
                visible = showSortSheet,
                currentSortOrder = currentSortOrder ?: chromeInfo.currentSortOrder,
                virtualListState = state,
                onSortSelected = { order ->
                    (onSortSelected ?: chromeInfo.onSortSelected)(order)
                },
                sortOptions = sortOptions,
                onDismiss = { showSortSheet = false }
            )
        }
    }
}

@Composable
internal fun LibraryTopChromeTransitionOverlay(
    title: String,
    sceneId: String,
    statisticsText: String,
    virtualListState: ComposeVirtualListState?,
    onBack: () -> Unit,
    onSelect: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onSearch: () -> Unit,
    onShuffle: (() -> Unit)?,
    onMore: () -> Unit,
    onCreatePlaylist: (() -> Unit)?,
    onImportPlaylist: (() -> Unit)?,
    nowPlayingTitle: String,
    nextSongTitle: String,
    showNextQueueHint: Boolean,
    metadataMatchProgressText: String,
    showNowPlayingLocator: Boolean = false,
    onLocateNowPlaying: () -> Unit = {},
    onHeaderSearch: (() -> Unit)? = null,
    headerSearchActive: Boolean = false,
    headerSearchQuery: String = "",
    onHeaderSearchQueryChange: (String) -> Unit = {},
    onHeaderSearchCancel: () -> Unit = {},
    showHeaderMore: Boolean = true,
    showHeaderSearch: Boolean = true,
    showHeaderShuffle: Boolean = true,
    headerTrailingContent: (@Composable () -> Unit)? = null,
) {
    val sceneChrome = LocalSceneChromeAlpha.current
    val sceneRole = sceneChrome.topChromeRole(sceneId)
    if (sceneRole != LibraryTopChromeSceneRole.ENTER) return

    val statusBarTop = unconsumedStatusBarTopPadding()
    val toolbarHeight = statusBarTop + LIBRARY_TOOLBAR_CONTENT_HEIGHT
    val topChromeStyle by PersonalizationPreferences.topChromeStyle.collectAsState()
    val bottomButtonsMode by PersonalizationPreferences.libraryBottomButtonsMode.collectAsState()
    val floatingStyleSelected = topChromeStyle == TopChromeStyle.FLOATING ||
        topChromeStyle == TopChromeStyle.FLOATING_VERTICAL
    val floatingChromeSelected = floatingStyleSelected &&
        bottomButtonsMode != LibraryBottomButtonsMode.DISABLED
    if (floatingChromeSelected) return
    val headerButtonsEnabled by PersonalizationPreferences.libraryHeaderButtonsEnabled.collectAsState()
    val effectiveHeaderButtonsEnabled = headerButtonsEnabled || !floatingChromeSelected
    val transparentTopChrome = topChromeStyle == TopChromeStyle.TRANSPARENT
    val blurSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !transparentTopChrome
    val localHazeState = rememberHazeState()
    val hazeState = LocalAppHazeState.current ?: localHazeState
    val staticForeground = usesReferenceStaticForeground()
    val isLight = !staticForeground && MiuixTheme.colorScheme.background.luminance() > 0.5f
    val density = LocalDensity.current
    val collapseDistancePx = with(density) { LIBRARY_TOOLBAR_CONTENT_HEIGHT.toPx() }
    val sceneAlpha = sceneChrome.topMenu
    val sceneAlphaProvider = sceneChrome.topMenuProvider
    val sceneProgressProvider = sceneChrome.topMenuExitProgressProvider

    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { clip = false },
    ) {
        VirtualListScrollProgressObserver(
            owner = virtualListState?.viewportScrollOwner,
            distancePx = collapseDistancePx,
        ) { overlayProgress ->
            val overlayProgressState = rememberUpdatedState(overlayProgress)
            val topMenuVisibility = 1f
            val headerActionVisibility = if (effectiveHeaderButtonsEnabled) 1f else 0f

            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .zIndex(40f),
            ) {
                TopGradientGlassTail(
                    hazeState = hazeState,
                    blurSupported = blurSupported,
                    blurActive = blurSupported,
                    isLight = isLight,
                    transparentStyle = transparentTopChrome,
                    overlayProgress = overlayProgressState,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(toolbarHeight + 8.dp)
                        .graphicsLayer {
                            val baseAlpha = sceneAlphaProvider?.invoke() ?: sceneAlpha
                            val progress = sceneProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
                            // The destination glass/background stays geometrically fixed and only
                            // fades in. Text/icon scale and translation belong to TopNav children.
                            alpha = baseAlpha * topMenuVisibility * sceneRole.visibilityAt(progress)
                        },
                )

                SongsTopMenuBar(
                    title = title,
                    sceneId = sceneId,
                    statisticsText = statisticsText,
                    nowPlayingTitle = nowPlayingTitle,
                    nextSongTitle = nextSongTitle,
                    showNextQueueHint = showNextQueueHint,
                    metadataMatchProgressText = metadataMatchProgressText,
                    isSearchActive = headerSearchActive,
                    searchQuery = headerSearchQuery,
                    onSearchQueryChange = onHeaderSearchQueryChange,
                    onToggleSearch = onHeaderSearch ?: onSearch,
                    onCancelSearch = onHeaderSearchCancel,
                    selectionMode = false,
                    selectedCount = 0,
                    onCancelSelection = {},
                    onSelectAll = {},
                    onBack = onBack,
                    onMoreClick = onMore,
                    onShuffleAll = { onShuffle?.invoke() },
                    headerButtonsEnabled = effectiveHeaderButtonsEnabled,
                    headerActionsAlpha = headerActionVisibility,
                    showNowPlayingLocator = showNowPlayingLocator,
                    onLocateNowPlaying = onLocateNowPlaying,
                    onCreatePlaylist = onCreatePlaylist,
                    onImportPlaylist = onImportPlaylist,
                    showHeaderMore = showHeaderMore,
                    showHeaderSearch = showHeaderSearch,
                    showHeaderShuffle = showHeaderShuffle,
                    headerTrailingContent = headerTrailingContent,
                    isLight = isLight,
                    overlayProgress = overlayProgress,
                    backdropBlurEnabled = blurSupported,
                    transparentStyle = transparentTopChrome,
                    sceneExitProgressProvider = sceneProgressProvider,
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            val baseAlpha = sceneAlphaProvider?.invoke() ?: sceneAlpha
                            alpha = baseAlpha * topMenuVisibility
                        },
                )
            }

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
    onPlay: (() -> Unit)?,
    onSearch: () -> Unit,
    onShuffle: (() -> Unit)?,
    onMore: () -> Unit,
    onCreatePlaylist: (() -> Unit)?,
    onImportPlaylist: (() -> Unit)?,
    vertical: Boolean = false,
    visibilityProgress: Float = 1f,
    maxAlpha: Float = 1f,
    surface: LibraryBottomButtonsSurface = LibraryBottomButtonsSurface.SOLID,
    hazeState: HazeState? = null,
    backdrop: Backdrop? = null,
    transitionAlpha: Float = 1f,
    transitionAlphaProvider: (() -> Float)? = null,
    sceneExitProgressProvider: (() -> Float)? = null,
    sceneRole: LibraryTopChromeSceneRole = LibraryTopChromeSceneRole.IDLE,
    modifier: Modifier = Modifier,
) {
    val scheme = MiuixTheme.colorScheme
    val iconTint = if (usesReferenceStaticForeground()) Color.White else scheme.onSurface
    val appBackdrop = backdrop
    val glassSettings by PersonalizationPreferences.globalLiquidGlassSettings.collectAsState()
    val bottomChromeEnvironment = LocalBottomChromeEnvironment.current
    val bottomChromeScrollState = LocalBottomChromeScrollState.current
    val density = LocalDensity.current
    val expandedChromeTopDistance =
        bottomChromeEnvironment.systemBottom +
            (if (bottomChromeEnvironment.hasNavigation) bottomChromeEnvironment.navigationHeight else 0.dp) +
            (if (bottomChromeEnvironment.hasMiniPlayer && bottomChromeEnvironment.hasNavigation) {
                bottomChromeEnvironment.interSurfaceGap
            } else {
                0.dp
            }) +
            (if (bottomChromeEnvironment.hasMiniPlayer) bottomChromeEnvironment.accessoryHeight else 0.dp)
    val minimizedChromeTopDistance =
        bottomChromeEnvironment.systemBottom + when {
            bottomChromeEnvironment.hasMiniPlayer -> bottomChromeEnvironment.compactAccessoryHeight
            bottomChromeEnvironment.hasNavigation -> bottomChromeEnvironment.compactAccessoryHeight
            else -> 0.dp
        }
    val expandedBottomPadding = (expandedChromeTopDistance + 10.dp).coerceAtLeast(72.dp)
    val minimizedBottomPadding = (minimizedChromeTopDistance + 10.dp).coerceAtLeast(72.dp)
    val floatingTravelPx = with(density) {
        (expandedBottomPadding - minimizedBottomPadding).toPx().coerceAtLeast(0f)
    }
    val easedProgress = smoothStep(visibilityProgress)
    val effectiveSceneExitProgressProvider = when (sceneRole) {
        LibraryTopChromeSceneRole.ENTER -> sceneExitProgressProvider?.let { provider ->
            { 1f - provider().coerceIn(0f, 1f) }
        }
        else -> sceneExitProgressProvider
    }
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
            onPlay = onPlay,
            onSearch = onSearch,
            onShuffle = onShuffle,
            onMore = onMore,
            onCreatePlaylist = onCreatePlaylist,
            onImportPlaylist = onImportPlaylist,
            iconTint = iconTint,
            vertical = vertical,
            visibilityProgress = easedProgress,
            maxAlpha = maxAlpha,
            surface = surface,
            hazeState = hazeState,
            appBackdrop = appBackdrop,
            glassSettings = glassSettings,
            transitionAlpha = transitionAlpha,
            transitionAlphaProvider = transitionAlphaProvider,
            sceneExitProgressProvider = effectiveSceneExitProgressProvider,
            collapsed = collapsed,
            onToggleCollapsed = { collapsed = !collapsed },
        )
    }
    Box(
        modifier = modifier
            .padding(bottom = expandedBottomPadding)
            .offset {
                val rawProgress = if (
                    bottomChromeEnvironment.layoutClass ==
                    com.rawsmusic.core.ui.scene.BottomChromeLayoutClass.Compact
                ) {
                    bottomChromeScrollState?.renderVisibilityProgress?.coerceIn(0f, 1f) ?: 0f
                } else {
                    0f
                }
                val progress = smoothStep(rawProgress)
                IntOffset(0, (floatingTravelPx * progress).roundToInt().coerceAtLeast(0))
            }
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

private fun Modifier.libraryBottomButtonSurface(
    surface: LibraryBottomButtonsSurface,
    hazeState: HazeState?,
    appBackdrop: Backdrop?,
    glassSettings: GlobalLiquidGlassSettings,
    containerColor: Color,
    isLight: Boolean,
): Modifier {
    val shape = RoundedCornerShape(24.dp)
    return when (surface) {
        LibraryBottomButtonsSurface.SOLID ->
            shadow(3.dp, shape, clip = false)
                .background(containerColor, shape)
                .clip(shape)

        LibraryBottomButtonsSurface.FROSTED -> {
            if (hazeState != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                shadow(3.dp, shape, clip = false)
                    .clip(shape)
                    .hazeEffect(state = hazeState) {
                        inputScale = HazeInputScale.Fixed(0.72f)
                        clipToAreasBounds = false
                        expandLayerBounds = true
                        drawContentBehind = true
                        blurEffect {
                            backgroundColor = Color.Transparent
                            colorEffects = listOf(
                                HazeColorEffect.tint(containerColor.copy(alpha = if (isLight) 0.58f else 0.52f))
                            )
                            blurRadius = (glassSettings.blurRadiusDp * 1.35f).dp
                            noiseFactor = 0f
                        }
                    }
            } else {
                shadow(3.dp, shape, clip = false)
                    .background(containerColor.copy(alpha = 0.88f), shape)
                    .clip(shape)
            }
        }

        LibraryBottomButtonsSurface.LIQUID_GLASS -> {
            if (appBackdrop != null) {
                drawBackdrop(
                    backdrop = appBackdrop,
                    shape = { shape },
                    effects = {
                        val minDimension = size.minDimension
                        val liquidBlurDp = (glassSettings.blurRadiusDp * 0.25f).coerceAtMost(8f)
                        vibrancy(glassSettings.vibrancyStrength)
                        blur(liquidBlurDp.dp.toPx())
                        lens(
                            refractionHeight = glassSettings.refractionHeightFraction * minDimension * 0.35f,
                            refractionAmount = glassSettings.refractionAmountFraction * minDimension * 1.35f,
                            depthEffect = true,
                            chromaticAberration = glassSettings.chromaticAberration > 0.001f,
                        )
                    },
                    highlight = {
                        Highlight.Default.copy(
                            alpha = (0.34f * glassSettings.highlightStrength).coerceIn(0f, 1f)
                        )
                    },
                    shadow = {
                        Shadow(
                            radius = 6.dp,
                            color = Color.Black.copy(
                                alpha = (0.08f * glassSettings.shadowStrength).coerceIn(0f, 1f)
                            ),
                        )
                    },
                    innerShadow = {
                        InnerShadow(
                            radius = 5.dp,
                            alpha = (0.20f * glassSettings.shadowStrength).coerceIn(0f, 1f),
                        )
                    },
                    // Keep the source visibly refracted. The old 40% opaque fill made the
                    // sampled list look almost identical to the solid surface.
                    onDrawSurface = { drawRect(containerColor.copy(alpha = 0.08f)) },
                ).clip(shape)
            } else {
                shadow(3.dp, shape, clip = false)
                    .background(containerColor.copy(alpha = 0.88f), shape)
                    .clip(shape)
            }
        }
    }
}

@Composable
private fun FloatingLibraryActionBarContent(
    onSelect: (() -> Unit)?,
    onPlay: (() -> Unit)?,
    onSearch: () -> Unit,
    onShuffle: (() -> Unit)?,
    onMore: () -> Unit,
    onCreatePlaylist: (() -> Unit)?,
    onImportPlaylist: (() -> Unit)?,
    iconTint: androidx.compose.ui.graphics.Color,
    vertical: Boolean,
    visibilityProgress: Float,
    maxAlpha: Float,
    surface: LibraryBottomButtonsSurface,
    hazeState: HazeState?,
    appBackdrop: Backdrop?,
    glassSettings: GlobalLiquidGlassSettings,
    transitionAlpha: Float,
    transitionAlphaProvider: (() -> Float)?,
    sceneExitProgressProvider: (() -> Float)?,
    collapsed: Boolean,
    onToggleCollapsed: () -> Unit,
) {
    // Keep the collapse control in a fixed first slot. When the group is
    // collapsed it must not jump to the old last-action position.
    FloatingLibraryIcon(
        iconTint = iconTint,
        icon = { tint ->
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
                    tint = tint,
                    modifier = Modifier.size(22.dp),
                )
            }
        },
        onClick = onToggleCollapsed,
        visibilityProgress = visibilityProgress,
        maxAlpha = maxAlpha,
        surface = surface,
        hazeState = hazeState,
        appBackdrop = appBackdrop,
        glassSettings = glassSettings,
        transitionAlpha = transitionAlpha,
        transitionAlphaProvider = transitionAlphaProvider,
        sceneExitProgressProvider = sceneExitProgressProvider,
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
            // retained-view implementation scene_bottom_toolbar order: Shuffle -> Play -> Search -> Select -> Menu.
            // Raw-specific create/import actions stay between Select and Menu; the collapse control
            // remains the fixed leading enhancement outside this action lane.
            onShuffle?.let {
                FloatingLibraryIcon(
                    iconTint = iconTint,
                    icon = { tint ->
                        Icon(
                            painter = painterResource(R.drawable.ic_shuffle_custom),
                            contentDescription = stringResource(R.string.songs_shuffle_all),
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = it,
                    visibilityProgress = visibilityProgress,
                    maxAlpha = maxAlpha,
                    surface = surface,
                    hazeState = hazeState,
                    appBackdrop = appBackdrop,
                    glassSettings = glassSettings,
                    transitionAlpha = transitionAlpha,
                    transitionAlphaProvider = transitionAlphaProvider,
                    sceneExitProgressProvider = sceneExitProgressProvider,
                )
            }
            onPlay?.let {
                FloatingLibraryIcon(
                    iconTint = iconTint,
                    icon = { tint ->
                        Icon(
                            painter = painterResource(R.drawable.ic_play),
                            contentDescription = stringResource(R.string.library_action_play),
                            tint = tint,
                            modifier = Modifier.size(24.dp),
                        )
                    },
                    onClick = it,
                    visibilityProgress = visibilityProgress,
                    maxAlpha = maxAlpha,
                    surface = surface,
                    hazeState = hazeState,
                    appBackdrop = appBackdrop,
                    glassSettings = glassSettings,
                    transitionAlpha = transitionAlpha,
                    transitionAlphaProvider = transitionAlphaProvider,
                    sceneExitProgressProvider = sceneExitProgressProvider,
                )
            }
            FloatingLibraryIcon(
                iconTint = iconTint,
                icon = { tint -> Icon(imageVector = MiuixIcons.Basic.Search, contentDescription = stringResource(R.string.library_action_search), tint = tint, modifier = Modifier.size(24.dp)) },
                onClick = onSearch,
                visibilityProgress = visibilityProgress,
                maxAlpha = maxAlpha,
                surface = surface,
                hazeState = hazeState,
                appBackdrop = appBackdrop,
                glassSettings = glassSettings,
                transitionAlpha = transitionAlpha,
                transitionAlphaProvider = transitionAlphaProvider,
                sceneExitProgressProvider = sceneExitProgressProvider,
            )
            onSelect?.let {
                FloatingLibraryTextAction(
                    text = if (vertical) stringResource(R.string.library_action_select_vertical)
                    else stringResource(R.string.library_action_select),
                    vertical = vertical,
                    onClick = it,
                    visibilityProgress = visibilityProgress,
                    maxAlpha = maxAlpha,
                    surface = surface,
                    hazeState = hazeState,
                    appBackdrop = appBackdrop,
                    glassSettings = glassSettings,
                    transitionAlpha = transitionAlpha,
                    transitionAlphaProvider = transitionAlphaProvider,
                    sceneExitProgressProvider = sceneExitProgressProvider,
                )
            }
            onCreatePlaylist?.let {
                FloatingLibraryIcon(
                    iconTint = iconTint,
                    icon = { tint -> Icon(painter = painterResource(R.drawable.ic_add_outline), contentDescription = stringResource(R.string.playlist_action_create), tint = tint, modifier = Modifier.size(24.dp)) },
                    onClick = it,
                    visibilityProgress = visibilityProgress,
                    maxAlpha = maxAlpha,
                    surface = surface,
                    hazeState = hazeState,
                    appBackdrop = appBackdrop,
                    glassSettings = glassSettings,
                    transitionAlpha = transitionAlpha,
                    transitionAlphaProvider = transitionAlphaProvider,
                    sceneExitProgressProvider = sceneExitProgressProvider,
                )
            }
            onImportPlaylist?.let {
                FloatingLibraryIcon(
                    iconTint = iconTint,
                    icon = { tint -> Icon(painter = painterResource(R.drawable.ic_playlist_import), contentDescription = stringResource(R.string.playlist_action_import), tint = tint, modifier = Modifier.size(24.dp)) },
                    onClick = it,
                    visibilityProgress = visibilityProgress,
                    maxAlpha = maxAlpha,
                    surface = surface,
                    hazeState = hazeState,
                    appBackdrop = appBackdrop,
                    glassSettings = glassSettings,
                    transitionAlpha = transitionAlpha,
                    transitionAlphaProvider = transitionAlphaProvider,
                    sceneExitProgressProvider = sceneExitProgressProvider,
                )
            }
            FloatingLibraryIcon(
                iconTint = iconTint,
                icon = { tint -> Icon(painter = painterResource(R.drawable.ic_library_more), contentDescription = stringResource(R.string.common_more_operations), tint = tint, modifier = Modifier.size(24.dp)) },
                onClick = onMore,
                visibilityProgress = visibilityProgress,
                maxAlpha = maxAlpha,
                surface = surface,
                hazeState = hazeState,
                appBackdrop = appBackdrop,
                glassSettings = glassSettings,
                transitionAlpha = transitionAlpha,
                transitionAlphaProvider = transitionAlphaProvider,
                sceneExitProgressProvider = sceneExitProgressProvider,
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
    maxAlpha: Float = 1f,
    surface: LibraryBottomButtonsSurface = LibraryBottomButtonsSurface.SOLID,
    hazeState: HazeState? = null,
    appBackdrop: Backdrop? = null,
    glassSettings: GlobalLiquidGlassSettings = GlobalLiquidGlassSettings.Default,
    transitionAlpha: Float,
    transitionAlphaProvider: (() -> Float)?,
    sceneExitProgressProvider: (() -> Float)?,
) {
    val scheme = MiuixTheme.colorScheme
    val staticForeground = usesReferenceStaticForeground()
    val isLight = !staticForeground && scheme.background.luminance() > 0.5f
    val containerColor = if (isLight) Color(0xFFF8F8F8) else Color(0xFF181818)
    val baseVisibilityProgress = visibilityProgress.coerceIn(0f, 1f)
    Box(
        modifier = Modifier
            .height(36.dp)
            .widthIn(min = if (vertical) 40.dp else 52.dp)
            .graphicsLayer {
                val sceneProgress = (transitionAlphaProvider?.invoke() ?: transitionAlpha)
                    .coerceIn(0f, 1f)
                val visualProgress = (baseVisibilityProgress * sceneProgress).coerceIn(0f, 1f)
                val exit = sceneExitProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
                val sceneVisibility = 1f - exit
                alpha = visualProgress * maxAlpha.coerceIn(0f, 1f) * sceneVisibility
                val visibilityScale = FLOATING_ACTION_MIN_SCALE +
                    (1f - FLOATING_ACTION_MIN_SCALE) * visualProgress
                val sceneVisibilityScale = FLOATING_ACTION_MIN_SCALE +
                    (1f - FLOATING_ACTION_MIN_SCALE) * sceneVisibility
                scaleX = visibilityScale * sceneVisibilityScale
                scaleY = visibilityScale * sceneVisibilityScale
                translationY = 0f
                clip = false
            }
            .libraryBottomButtonSurface(
                surface = surface,
                hazeState = hazeState,
                appBackdrop = appBackdrop,
                glassSettings = glassSettings,
                containerColor = containerColor,
                isLight = isLight,
            )
            .clickable(
                enabled = baseVisibilityProgress > 0.001f,
                onClick = onClick,
            )
            .padding(horizontal = 10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            color = if (staticForeground) Color.White else scheme.onSurface,
            lineHeight = if (vertical) 16.sp else 20.sp,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}

@Composable
private fun FloatingLibraryIcon(
    iconTint: androidx.compose.ui.graphics.Color,
    icon: @Composable (androidx.compose.ui.graphics.Color) -> Unit,
    onClick: () -> Unit,
    visibilityProgress: Float,
    maxAlpha: Float = 1f,
    surface: LibraryBottomButtonsSurface = LibraryBottomButtonsSurface.SOLID,
    hazeState: HazeState? = null,
    appBackdrop: Backdrop? = null,
    glassSettings: GlobalLiquidGlassSettings = GlobalLiquidGlassSettings.Default,
    transitionAlpha: Float,
    transitionAlphaProvider: (() -> Float)?,
    sceneExitProgressProvider: (() -> Float)?,
) {
    val baseVisibilityProgress = visibilityProgress.coerceIn(0f, 1f)
    val enabled = baseVisibilityProgress > 0.001f
    val scheme = MiuixTheme.colorScheme
    val staticForeground = usesReferenceStaticForeground()
    val isLight = !staticForeground && scheme.background.luminance() > 0.5f
    val containerColor = if (isLight) Color(0xFFF8F8F8) else Color(0xFF181818)
    Box(
        modifier = Modifier
            .size(36.dp)
            .graphicsLayer {
                val sceneProgress = (transitionAlphaProvider?.invoke() ?: transitionAlpha)
                    .coerceIn(0f, 1f)
                val visualProgress = (baseVisibilityProgress * sceneProgress).coerceIn(0f, 1f)
                val exit = sceneExitProgressProvider?.invoke()?.coerceIn(0f, 1f) ?: 0f
                val sceneVisibility = 1f - exit
                alpha = visualProgress * maxAlpha.coerceIn(0f, 1f) * sceneVisibility
                val visibilityScale = FLOATING_ACTION_MIN_SCALE +
                    (1f - FLOATING_ACTION_MIN_SCALE) * visualProgress
                val sceneVisibilityScale = FLOATING_ACTION_MIN_SCALE +
                    (1f - FLOATING_ACTION_MIN_SCALE) * sceneVisibility
                scaleX = visibilityScale * sceneVisibilityScale
                scaleY = visibilityScale * sceneVisibilityScale
                translationY = 0f
                clip = false
            }
            .libraryBottomButtonSurface(
                surface = surface,
                hazeState = hazeState,
                appBackdrop = appBackdrop,
                glassSettings = glassSettings,
                containerColor = containerColor,
                isLight = isLight,
            )
            .clickable(
                enabled = enabled,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        icon(iconTint)
    }
}
