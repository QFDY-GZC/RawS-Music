package com.rawsmusic.core.ui.scene.pages

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.rawsmusic.core.ui.systemui.rawNavigationBarsPadding
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.widget.RawMiuixOverlayDialog
import com.rawsmusic.core.ui.widget.flow.RawFlowBackground
import com.rawsmusic.core.ui.widget.flow.rememberCurrentRawFlowMode
import com.rawsmusic.module.data.source.InstalledLxSource
import com.rawsmusic.module.data.source.InstalledMusicSource
import com.rawsmusic.module.data.source.LxSourceInstallResult
import com.rawsmusic.module.data.source.LxSourcePluginStore
import com.rawsmusic.module.data.source.MusicSourceInstallChange
import com.rawsmusic.module.data.source.MusicSourceInstallResult
import com.rawsmusic.module.data.source.MusicSourcePluginStore
import com.rawsmusic.module.data.source.playback.MusicSourceArtworkRepository
import com.rawsmusic.module.data.source.playback.MusicSourceDownloadController
import com.rawsmusic.module.data.source.playback.MusicSourceLyricController
import com.rawsmusic.module.data.source.playback.MusicSourcePlaybackController
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

private enum class SourceImportProtocol {
    MusicFree,
    Lx,
}

private sealed interface PendingSourceDelete {
    data class MusicFree(val source: InstalledMusicSource) : PendingSourceDelete
    data class Lx(val source: InstalledLxSource) : PendingSourceDelete
}

/** Independent MusicFree/LX portal. Pages own state; this file only routes and provides chrome. */
@Composable
fun SourceImportPage(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MiuixTheme.colorScheme
    val sourceBackdrop = rememberLayerBackdrop()
    val flowMode = rememberCurrentRawFlowMode()
    val tabStateHolder = rememberSaveableStateHolder()
    val installedSources by MusicSourcePluginStore.sources.collectAsState()
    val installedLxSources by LxSourcePluginStore.sources.collectAsState()
    val playback by MusicSourcePlaybackController.snapshot.collectAsState()
    val lyric by MusicSourceLyricController.snapshot.collectAsState()
    val artworkPaths by MusicSourceArtworkRepository.paths.collectAsState()

    var navigation by rememberSaveable(stateSaver = SourcePortalNavigationStateSaver) {
        mutableStateOf(SourcePortalNavigationState())
    }
    val tabBackStack = navigation.tabStack
    val selectedTab = navigation.selectedTab
    val route = navigation.route
    var nextTransitionId by remember { mutableIntStateOf(0) }
    var urlImportProtocol by rememberSaveable { mutableStateOf<SourceImportProtocol?>(null) }
    var fileImportProtocol by rememberSaveable { mutableStateOf(SourceImportProtocol.MusicFree) }
    var pendingDelete by remember { mutableStateOf<PendingSourceDelete?>(null) }
    var importBusy by remember { mutableStateOf(false) }
    var statusMessage by rememberSaveable { mutableStateOf("") }

    val currentItem = playback.currentItem
    val currentArtworkPath = currentItem?.let { artworkPaths[it.stableIdentity] }

    LaunchedEffect(Unit) {
        MusicSourceDownloadController.initialize(context)
    }
    LaunchedEffect(currentItem?.stableIdentity, playback.currentIndex, playback.queue.size) {
        MusicSourceArtworkRepository.prefetch(context, currentItem)
        MusicSourceArtworkRepository.prefetch(context, playback.queue.getOrNull(playback.currentIndex - 1))
        MusicSourceArtworkRepository.prefetch(context, playback.queue.getOrNull(playback.currentIndex + 1))
    }
    LaunchedEffect(currentItem?.stableIdentity) {
        MusicSourceLyricController.load(context, currentItem)
    }

    fun reportMusicFree(result: MusicSourceInstallResult) {
        statusMessage = when (result) {
            is MusicSourceInstallResult.Success -> when (result.change) {
                MusicSourceInstallChange.Installed -> context.getString(R.string.source_imported_musicfree, result.source.name)
                MusicSourceInstallChange.Updated -> context.getString(R.string.source_updated_musicfree, result.source.name)
                MusicSourceInstallChange.Unchanged -> context.getString(R.string.source_installed_musicfree, result.source.name)
            }
            is MusicSourceInstallResult.Failure -> result.message
        }
    }

    fun reportLx(result: LxSourceInstallResult) {
        statusMessage = when (result) {
            is LxSourceInstallResult.Success -> when (result.change) {
                MusicSourceInstallChange.Installed -> context.getString(R.string.source_imported_lx, result.source.name)
                MusicSourceInstallChange.Updated -> context.getString(R.string.source_updated_lx, result.source.name)
                MusicSourceInstallChange.Unchanged -> context.getString(R.string.source_installed_lx, result.source.name)
            }
            is LxSourceInstallResult.Failure -> result.message
        }
    }

    val fileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importBusy = true
            when (fileImportProtocol) {
                SourceImportProtocol.MusicFree -> reportMusicFree(MusicSourcePluginStore.installFromUri(context, uri))
                SourceImportProtocol.Lx -> reportLx(LxSourcePluginStore.installFromUri(context, uri))
            }
            importBusy = false
        }
    }

    fun launchFileImport(protocol: SourceImportProtocol) {
        fileImportProtocol = protocol
        fileLauncher.launch(
            arrayOf(
                "application/javascript",
                "application/x-javascript",
                "text/javascript",
                "text/plain",
                "text/*",
                "application/octet-stream",
            )
        )
    }

    fun navigateToTab(tab: SourcePortalTab) {
        val latest = navigation.tabStack
        val current = latest.lastOrNull() ?: SourcePortalTab.Sources
        if (tab == current) return

        nextTransitionId++
        navigation = navigation.copy(
            tabStackNames = SourcePortalTabHistory.append(navigation.tabStackNames, tab),
            tabTransition = SourcePortalTabTransition(
                id = nextTransitionId,
                from = current,
                to = tab,
                direction = SourcePortalTabNavigationDirection.Forward,
            ),
        )
    }

    fun popTab(animate: Boolean = true) {
        val latest = navigation.tabStack
        if (latest.size <= 1) return
        val from = latest.last()
        val nextNames = SourcePortalTabHistory.pop(navigation.tabStackNames)
        val to = SourcePortalTabHistory.current(nextNames)
        nextTransitionId++
        navigation = navigation.copy(
            tabStackNames = nextNames,
            tabTransition = if (animate) {
                SourcePortalTabTransition(
                    id = nextTransitionId,
                    from = from,
                    to = to,
                    direction = SourcePortalTabNavigationDirection.Back,
                )
            } else null,
        )
    }

    fun navigateToRoute(
        target: SourcePortalRoute,
        direction: SourcePortalTabNavigationDirection,
        animate: Boolean = true,
    ) {
        val from = navigation.route
        if (from == target) return
        nextTransitionId++
        navigation = navigation.copy(
            route = target,
            routeTransition = if (animate) {
                SourcePortalRouteTransition(
                    id = nextTransitionId,
                    from = from,
                    to = target,
                    direction = direction,
                )
            } else null,
        )
    }

    fun handleInternalBack(wasPredictiveGesture: Boolean) {
        when (route) {
            SourcePortalRoute.Lyrics -> navigateToRoute(
                target = SourcePortalRoute.Player,
                direction = SourcePortalTabNavigationDirection.Back,
                animate = !wasPredictiveGesture,
            )
            SourcePortalRoute.Player -> navigateToRoute(
                target = SourcePortalRoute.Browse,
                direction = SourcePortalTabNavigationDirection.Back,
                animate = !wasPredictiveGesture,
            )
            SourcePortalRoute.Browse -> popTab(animate = !wasPredictiveGesture)
        }
    }

    val hasInternalBack = route != SourcePortalRoute.Browse || tabBackStack.size > 1
    val predictiveBackProgress = rememberSourcePortalPredictiveBackProgress(
        enabled = hasInternalBack,
        destinationKey = route to navigation.tabStackNames,
        onBackCompleted = { wasPredictiveGesture ->
            handleInternalBack(wasPredictiveGesture)
        },
    )

    val predictiveRouteTarget = when (route) {
        SourcePortalRoute.Lyrics -> SourcePortalRoute.Player
        SourcePortalRoute.Player -> SourcePortalRoute.Browse
        SourcePortalRoute.Browse -> null
    }

    @Composable
    fun BrowseContent() {
        RawFlowBackground(
            mode = flowMode,
            sourceCoverKey = currentArtworkPath,
            modifier = Modifier.fillMaxSize(),
        )
        Box(Modifier.fillMaxSize().background(scheme.background.copy(alpha = 0.48f)))
        SourcePortalTabTransitionHost(
            currentTab = selectedTab,
            transition = navigation.tabTransition,
            predictiveBackProgress = if (route == SourcePortalRoute.Browse) predictiveBackProgress else 0f,
            predictiveBackTarget = if (route == SourcePortalRoute.Browse) {
                tabBackStack.getOrNull(tabBackStack.lastIndex - 1)
            } else null,
            onTransitionFinished = { transitionId ->
                if (navigation.tabTransition?.id == transitionId) {
                    navigation = navigation.copy(tabTransition = null)
                }
            },
            modifier = Modifier.fillMaxSize(),
        ) { targetTab ->
            tabStateHolder.SaveableStateProvider(targetTab.name) {
                when (targetTab) {
                    SourcePortalTab.Sources -> SourceSearchPage(
                        installedSources = installedSources,
                        installedLxSources = installedLxSources,
                        artworkPaths = artworkPaths,
                        currentPlayingIdentity = currentItem?.stableIdentity,
                        onBack = if (tabBackStack.size > 1) { { popTab() } } else onBack,
                        onSelectItem = { item, queue ->
                            val index = queue.indexOfFirst { it.stableIdentity == item.stableIdentity }
                            MusicSourceArtworkRepository.prefetch(context, item)
                            MusicSourcePlaybackController.play(
                                context = context,
                                queue = queue.ifEmpty { listOf(item) },
                                index = index.takeIf { it >= 0 } ?: 0,
                            )
                        },
                        onDownload = { item, quality ->
                            MusicSourceDownloadController.enqueue(context, item, quality)
                        },
                    )

                    SourcePortalTab.Configuration -> SourceConfigurationPage(
                        installedMusicFreeSources = installedSources,
                        installedLxSources = installedLxSources,
                        importBusy = importBusy,
                        statusMessage = statusMessage,
                        onBack = { popTab() },
                        onImportMusicFreeFile = { launchFileImport(SourceImportProtocol.MusicFree) },
                        onImportMusicFreeUrl = { urlImportProtocol = SourceImportProtocol.MusicFree },
                        onImportLxFile = { launchFileImport(SourceImportProtocol.Lx) },
                        onImportLxUrl = { urlImportProtocol = SourceImportProtocol.Lx },
                        onToggleMusicFree = MusicSourcePluginStore::setEnabled,
                        onToggleLx = LxSourcePluginStore::setEnabled,
                        onDeleteMusicFree = { pendingDelete = PendingSourceDelete.MusicFree(it) },
                        onDeleteLx = { pendingDelete = PendingSourceDelete.Lx(it) },
                    )

                    SourcePortalTab.Downloads -> SourceDownloadsPage(
                        onBack = { popTab() },
                    )
                }
            }
        }
    }

    @Composable
    fun RouteContent(targetRoute: SourcePortalRoute) {
        Box(Modifier.fillMaxSize()) {
            when (targetRoute) {
                SourcePortalRoute.Browse -> BrowseContent()

                SourcePortalRoute.Player -> {
                    Box(Modifier.fillMaxSize().background(scheme.background))
                    SourceOnlinePlayerPage(
                        playback = playback,
                        lyric = lyric,
                        artworkPaths = artworkPaths,
                        onClose = {
                            navigateToRoute(
                                target = SourcePortalRoute.Browse,
                                direction = SourcePortalTabNavigationDirection.Back,
                            )
                        },
                        onOpenLyrics = {
                            MusicSourceLyricController.load(context, currentItem)
                            navigateToRoute(
                                target = SourcePortalRoute.Lyrics,
                                direction = SourcePortalTabNavigationDirection.Forward,
                            )
                        },
                        onPlayPause = { MusicSourcePlaybackController.playPause(context) },
                        onPrevious = MusicSourcePlaybackController::previous,
                        onNext = MusicSourcePlaybackController::next,
                        onSeek = MusicSourcePlaybackController::seekTo,
                        onSelectQuality = MusicSourcePlaybackController::setPreferredQuality,
                    )
                }

                SourcePortalRoute.Lyrics -> {
                    RawFlowBackground(
                        mode = flowMode,
                        sourceCoverKey = currentArtworkPath,
                        modifier = Modifier.fillMaxSize(),
                    )
                    Box(Modifier.fillMaxSize().background(scheme.background.copy(alpha = 0.48f)))
                    SourceOnlineLyricPage(
                        playback = playback,
                        lyric = lyric,
                        onBack = {
                            navigateToRoute(
                                target = SourcePortalRoute.Player,
                                direction = SourcePortalTabNavigationDirection.Back,
                            )
                        },
                        onRetry = { MusicSourceLyricController.retry(context) },
                        onSeek = MusicSourcePlaybackController::seekTo,
                    )
                }
            }
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .layerBackdrop(sourceBackdrop),
        ) {
            SourcePortalRouteTransitionHost(
                currentRoute = route,
                transition = navigation.routeTransition,
                predictiveBackProgress = if (route == SourcePortalRoute.Browse) 0f else predictiveBackProgress,
                predictiveBackTarget = predictiveRouteTarget,
                onTransitionFinished = { transitionId ->
                    if (navigation.routeTransition?.id == transitionId) {
                        navigation = navigation.copy(routeTransition = null)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                content = ::RouteContent,
            )
        }

        SourcePortalBrowseChromeTransitionHost(
            currentRoute = route,
            transition = navigation.routeTransition,
            predictiveBackProgress = if (route == SourcePortalRoute.Browse) 0f else predictiveBackProgress,
            predictiveBackTarget = predictiveRouteTarget,
            modifier = Modifier.fillMaxSize(),
        ) {
            Box(Modifier.fillMaxSize()) {
                SourceOnlineMiniPlayer(
                    backdrop = sourceBackdrop,
                    snapshot = playback,
                    lyric = lyric,
                    artworkPaths = artworkPaths,
                    onOpenPlayer = {
                        if (playback.currentItem != null) {
                            navigateToRoute(
                                target = SourcePortalRoute.Player,
                                direction = SourcePortalTabNavigationDirection.Forward,
                            )
                        }
                    },
                    onPlayPause = { MusicSourcePlaybackController.playPause(context) },
                    onPrevious = MusicSourcePlaybackController::previous,
                    onNext = MusicSourcePlaybackController::next,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .rawNavigationBarsPadding()
                        .padding(bottom = 76.dp),
                )
                SourcePortalBottomNavigation(
                    selectedTab = selectedTab,
                    onSelect = ::navigateToTab,
                    backdrop = sourceBackdrop,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp)
                        .rawNavigationBarsPadding()
                        .padding(bottom = 8.dp),
                )
            }
        }
    }

    urlImportProtocol?.let { protocol ->
        SourceUrlImportDialog(
            title = when (protocol) {
                SourceImportProtocol.MusicFree -> stringResource(R.string.source_import_musicfree_url)
                SourceImportProtocol.Lx -> stringResource(R.string.source_import_lx_url)
            },
            summary = when (protocol) {
                SourceImportProtocol.MusicFree -> stringResource(R.string.source_import_musicfree_url_summary)
                SourceImportProtocol.Lx -> stringResource(R.string.source_import_lx_url_summary)
            },
            busy = importBusy,
            onDismiss = { if (!importBusy) urlImportProtocol = null },
            onImport = { url ->
                scope.launch {
                    importBusy = true
                    when (protocol) {
                        SourceImportProtocol.MusicFree -> reportMusicFree(MusicSourcePluginStore.installFromUrl(context, url))
                        SourceImportProtocol.Lx -> reportLx(LxSourcePluginStore.installFromUrl(context, url))
                    }
                    importBusy = false
                    urlImportProtocol = null
                }
            },
        )
    }

    pendingDelete?.let { delete ->
        val sourceName = when (delete) {
            is PendingSourceDelete.MusicFree -> delete.source.name
            is PendingSourceDelete.Lx -> delete.source.name
        }
        RawMiuixOverlayDialog(
            show = true,
            title = stringResource(R.string.source_delete_title),
            summary = stringResource(R.string.source_delete_summary, sourceName),
            backgroundColor = scheme.surface,
            onDismissRequest = { pendingDelete = null },
            renderInRootScaffold = true,
        ) {
            androidx.compose.foundation.layout.Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    text = stringResource(R.string.common_cancel),
                    onClick = { pendingDelete = null },
                    modifier = Modifier.weight(1f),
                )
                TextButton(
                    text = stringResource(R.string.source_confirm_delete),
                    onClick = {
                        when (delete) {
                            is PendingSourceDelete.MusicFree -> MusicSourcePluginStore.remove(context, delete.source.id)
                            is PendingSourceDelete.Lx -> LxSourcePluginStore.remove(context, delete.source.id)
                        }
                        pendingDelete = null
                        statusMessage = context.getString(R.string.source_delete_status, sourceName)
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
