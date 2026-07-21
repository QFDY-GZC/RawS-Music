package com.rawsmusic.core.ui.scene.pages

import android.os.Build
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.common.model.SortOrder
import com.rawsmusic.core.ui.scene.LocalAppHazeState
import com.rawsmusic.core.ui.widget.powerlist.ComposePowerListState
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState

internal val LIBRARY_TOOLBAR_CONTENT_HEIGHT = 72.dp
internal val LIBRARY_SEARCH_TOOLBAR_CONTENT_HEIGHT = 120.dp
// Keep the toolbar spacer inside the scroll geometry. At index 0 the first real row must start
// below the glass controls; the backdrop source still fills the window, so blur does not need a
// row to be deliberately hidden behind the toolbar.
internal val LIBRARY_CONTENT_OVERLAP = 0.dp
internal val LIBRARY_CONTENT_MIN_INSET = 8.dp

@Immutable
data class LibraryChromeInfo(
    val nowPlayingTitle: String = "",
    val nextSongTitle: String = "",
    val showNextQueueHint: Boolean = false,
    val onSearch: () -> Unit = {},
    val onOpenFolderPicker: () -> Unit = {},
    val currentSortOrder: SortOrder = SortOrder.TITLE_ASC,
    val onSortSelected: (SortOrder) -> Unit = {}
)

val LocalLibraryChromeInfo = staticCompositionLocalOf { LibraryChromeInfo() }

/** Shared glass header for every root library collection. */
@Composable
fun LibraryListScaffold(
    title: String,
    sceneId: String,
    onBack: () -> Unit,
    powerListState: ComposePowerListState? = null,
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
    val contentTopPadding = (toolbarHeight - contentOverlap)
        .coerceAtLeast(statusBarTop + LIBRARY_CONTENT_MIN_INSET)
    val localHazeState = rememberHazeState()
    val hazeState = LocalAppHazeState.current ?: localHazeState
    val blurEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    val isLight = top.yukonga.miuix.kmp.theme.MiuixTheme.colorScheme.background.luminance() > 0.5f
    val overlayProgress = ((powerListState?.viewportScrollYPx ?: 0f) / 80f).coerceIn(0f, 1f)
    val overlayProgressState = rememberUpdatedState(overlayProgress)
    val chromeInfo = LocalLibraryChromeInfo.current
    var showSortSheet by remember { mutableStateOf(false) }
    val backdropSource = if (blurEnabled) Modifier.hazeSource(hazeState) else Modifier

    Box(modifier = modifier.fillMaxSize()) {
        Box(modifier = Modifier.fillMaxSize()) {
            content(contentTopPadding, backdropSource)
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
                overlayProgress = overlayProgressState,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(toolbarHeight + 8.dp)
            )

            SongsTopMenuBar(
                title = title,
                sceneId = sceneId,
                nowPlayingTitle = chromeInfo.nowPlayingTitle,
                nextSongTitle = chromeInfo.nextSongTitle,
                showNextQueueHint = chromeInfo.showNextQueueHint,
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
                onOpenFolderPicker = chromeInfo.onOpenFolderPicker,
                onSortClick = { showSortSheet = true },
                onShuffleAll = { onShuffle?.invoke() },
                onCreatePlaylist = onCreatePlaylist,
                onImportPlaylist = onImportPlaylist,
                isLight = isLight,
                overlayProgress = overlayProgress,
                backdropBlurEnabled = blurEnabled,
                modifier = Modifier.fillMaxWidth()
            )
        }

        powerListState?.let { state ->
            SongsSortLayoutSheet(
                visible = showSortSheet,
                currentSortOrder = currentSortOrder ?: chromeInfo.currentSortOrder,
                powerListState = state,
                onSortSelected = { order ->
                    (onSortSelected ?: chromeInfo.onSortSelected)(order)
                    showSortSheet = false
                },
                sortOptions = sortOptions,
                onDismiss = { showSortSheet = false }
            )
        }
    }
}
