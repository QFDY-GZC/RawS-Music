package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.FolderHierarchyNode
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryTopChromeOverlayOnly
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.scene.COLLECTION_HEADER_RADIUS_DP
import com.rawsmusic.core.ui.scene.RetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.scene.SharedTransitionSpec
import com.rawsmusic.core.ui.scene.SharedCoverOverlay
import com.rawsmusic.core.ui.scene.awaitSharedActorReady
import com.rawsmusic.core.ui.scene.awaitCollectionSharedPair
import com.rawsmusic.core.ui.scene.collectionReleaseDuration
import com.rawsmusic.core.ui.scene.collectionReleaseMotion
import com.rawsmusic.core.ui.scene.collectionBackPivotX
import com.rawsmusic.core.ui.scene.LibraryContentBackGesture
import com.rawsmusic.core.ui.scene.providerOwnsBack
import com.rawsmusic.core.ui.scene.VirtualListAccelerateDecelerate
import com.rawsmusic.core.ui.scene.VIRTUAL_LIST_COMMIT_MS
import com.rawsmusic.core.ui.widget.virtuallist.RenderedPopulationReadiness
import com.rawsmusic.core.ui.widget.bitmaps.CrossfadeAlbumArt
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.virtuallist.ComposeGenericVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.FolderVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListProviderNavigationFrame
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListProviderNavigationFrame
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListVisualItem
import com.rawsmusic.core.ui.widget.virtuallist.RegisteredVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.SongVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.copyForRetainedProvider
import com.rawsmusic.core.ui.widget.virtuallist.retainedMappedList
import com.rawsmusic.core.ui.widget.virtuallist.toVirtualListAudioFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt

private const val REFERENCE_FOLDER_HIERARCHY_NAV_MS = VIRTUAL_LIST_COMMIT_MS
private const val ROOT_HIERARCHY_KEY = "__folder_hierarchy_root__"
private const val FOLDER_HIERARCHY_SHARED_PREFIX = "folder-hierarchy-shared"

/** Shared by preparation, visible controller and return navigation; no duplicate path owners. */
@Stable
class FolderHierarchyPageState(initialPath: String? = null) {
    internal val contentBackGesture = LibraryContentBackGesture()
    internal val pathState = mutableStateOf(initialPath)
    internal val scrollByPath = HashMap<String, Float>()
    internal val drawnLayouts = RenderedPopulationReadiness<Pair<Int, String?>>()
    internal val transitionState = mutableStateOf<FolderHierarchyTransition?>(null)
    internal val progressState = mutableFloatStateOf(1f)
    internal val serialState = mutableIntStateOf(0)
    internal val heroEndpointState = mutableStateOf<Pair<String, FolderHierarchyCoverEndpoint>?>(null)
    internal val rowEndpoints = mutableStateMapOf<String, FolderHierarchyCoverEndpoint>()
    private var indexedSongs: List<AudioFile>? = null
    private var indexedHierarchy: List<FolderHierarchyNode>? = null
    private var cachedIndex: FolderHierarchyPageIndex? = null

    internal fun indexFor(songs: List<AudioFile>, hierarchy: List<FolderHierarchyNode>): FolderHierarchyPageIndex {
        val cached = cachedIndex
        if (cached != null && indexedSongs === songs && indexedHierarchy === hierarchy) return cached
        return FolderHierarchyPageIndex(
            nodesById = hierarchy.associateBy { it.id },
            nodesByPath = hierarchy.associateBy { it.path },
            childrenByParent = hierarchy.groupBy { it.parentId }
                .mapValues { (_, children) -> children.sortedBy { it.name.lowercase() } },
            directSongsByFolder = songs.groupBy { it.path.substringBeforeLast('/', "").trimEnd('/') },
        ).also {
            indexedSongs = songs
            indexedHierarchy = hierarchy
            cachedIndex = it
        }
    }
}

internal data class FolderHierarchyPageIndex(
    val nodesById: Map<Long, FolderHierarchyNode>,
    val nodesByPath: Map<String, FolderHierarchyNode>,
    val childrenByParent: Map<Long, List<FolderHierarchyNode>>,
    val directSongsByFolder: Map<String, List<AudioFile>>,
)
// retained-view implementation header artwork uses the window-width square itself; it is not the player scene_aa inset geometry.

private val ReferenceAccelerateDecelerateEasing = VirtualListAccelerateDecelerate

internal data class FolderHierarchyCoverEndpoint(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val radiusDp: Float,
    val coverKey: String,
    val folderId: Long,
    val promotedBitmap: android.graphics.Bitmap? = null,
) {
    val width: Float get() = (right - left).coerceAtLeast(1f)
    val height: Float get() = (bottom - top).coerceAtLeast(1f)

    fun unscaledAround(scale: Float, pivotX: Float, pivotY: Float): FolderHierarchyCoverEndpoint {
        val safeScale = scale.coerceIn(0.5f, 1.5f)
        if (kotlin.math.abs(safeScale - 1f) < 0.0001f) return this
        return copy(
            left = pivotX + (left - pivotX) / safeScale,
            top = pivotY + (top - pivotY) / safeScale,
            right = pivotX + (right - pivotX) / safeScale,
            bottom = pivotY + (bottom - pivotY) / safeScale,
        )
    }
}

internal data class FolderHierarchyTransition(
    val serial: Int,
    val direction: FolderHierarchyDirection,
    val retainedProvider: RegisteredVirtualList,
    val sourcePath: String?,
    val targetPath: String?,
    val interactive: Boolean,
    val targetPublished: Boolean = false,
    val sharedFolderPath: String? = null,
    val sharedElementId: String = "",
    val sourceCover: FolderHierarchyCoverEndpoint? = null,
    val sourceHeroCover: FolderHierarchyCoverEndpoint? = null,
    // null = target preparation still open; true/false is frozen before motion begins.
    // This prevents a late layout callback from creating a shared actor halfway through PivotTransition.
    val sharedEligible: Boolean? = null,
    val targetCover: FolderHierarchyCoverEndpoint? = null,
    val readyForMotion: Boolean = false,
    val gestureDirection: Float = 1f,
    val contentGesture: Boolean = false,
    val gestureReleased: Boolean = false,
) {
    fun acceptsGestureProgress(fromContent: Boolean): Boolean =
        interactive && !gestureReleased && contentGesture == fromContent
}

private fun CoverTransitionTarget.toFolderHierarchyEndpoint(folderId: Long): FolderHierarchyCoverEndpoint =
    FolderHierarchyCoverEndpoint(
        left = bounds.left,
        top = bounds.top,
        right = bounds.right,
        bottom = bounds.bottom,
        radiusDp = radiusDp,
        coverKey = coverKey,
        folderId = folderId,
    )

private fun FolderHierarchyCoverEndpoint.toSharedSnapshot(elementId: String): SharedCoverSnapshot =
    SharedCoverSnapshot(
        sceneId = NavScene.FOLDER_HIERARCHY.name,
        elementId = elementId,
        boundsInWindow = Rect(left, top, right, bottom),
        coverKey = coverKey,
        radiusDp = radiusDp,
        promotedBitmap = promotedBitmap,
    )

/**
 * retained-layout Folders Hierarchy.
 *
 * One ComposeVirtualList remains mounted for the whole hierarchy session. Entering a child first
 * snapshots the visible provider/layout into RETAINED, publishes the child into CURRENT, then runs
 * mode-4-style PivotTransition over those two LayoutRes roles. Folder artwork is a local shared item:
 * row cover -> child hero on forward, child hero -> remembered parent row on back.
 */
@Composable
fun FolderHierarchyPage(
    songs: List<AudioFile>,
    hierarchy: List<FolderHierarchyNode>,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    onShuffle: (List<AudioFile>) -> Unit,
    virtualListState: ComposeVirtualListState,
    initialPath: String? = null,
    pageState: FolderHierarchyPageState = remember { FolderHierarchyPageState(initialPath) },
    predictiveBackEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    var currentPath by pageState.pathState
    val publicationOnly = LocalReferenceLibraryProviderPublicationOnly.current ||
        LocalReferenceLibraryTopChromeOverlayOnly.current
    val ownsHierarchyInput = predictiveBackEnabled && !publicationOnly
    val index = remember(pageState, songs, hierarchy) { pageState.indexFor(songs, hierarchy) }
    val nodesById = index.nodesById
    val nodesByPath = index.nodesByPath
    val childrenByParent = index.childrenByParent
    val directSongsByFolder = index.directSongsByFolder
    val scrollByPath = pageState.scrollByPath
    val liveFolderRowEndpoints = pageState.rowEndpoints
    // This is layout publication metadata, not UI state. Reading it in a click/back callback avoids
    // one recomposition just to mirror LibraryListScaffold's stable top inset.
    val currentContentTopPadding = remember { arrayOf(0.dp) }
    val currentHeroOuterHeight = remember { arrayOf(0.dp) }
    val lastClickedCoverTarget = remember { arrayOfNulls<CoverTransitionTarget>(1) }
    var currentHeroEndpoint by pageState.heroEndpointState
    var pageOriginLeft by remember { mutableFloatStateOf(0f) }
    var pageOriginTop by remember { mutableFloatStateOf(0f) }
    var transitionProgress by pageState.progressState
    var pendingInteractiveProgress by remember { mutableFloatStateOf(0f) }
    var transition by pageState.transitionState
    var transitionSerial by pageState.serialState
    val scope = rememberCoroutineScope()
    var settleJob by remember { mutableStateOf<Job?>(null) }
    val coverRegistry = LocalSharedCoverRegistry.current
    val outerSharedSpec = LocalSharedTransitionSpec.current

    val currentNode = currentPath?.let(nodesByPath::get)
    val currentParentId = currentNode?.id ?: 0L
    val currentChildren = childrenByParent[currentParentId].orEmpty()
    val directSongs = remember(currentPath, directSongsByFolder) {
        if (currentPath == null) emptyList()
        else directSongsByFolder[currentPath].orEmpty().sortedBy { it.displayName.lowercase() }
    }
    val currentRows: List<VirtualListVisualItem> = remember(currentChildren, directSongs) {
        buildList(currentChildren.size + directSongs.size) {
            currentChildren.forEach { node ->
                add(
                    FolderVirtualListItem(
                        path = node.path,
                        name = node.name,
                        parentName = node.parentLabel,
                        cover = node.coverKey,
                        songCount = node.hierarchicalSongCount,
                        totalDurationMs = node.hierarchicalDurationMs,
                        folderId = node.id,
                        parentFolderId = node.parentId,
                        childFolderCount = node.childFolderCount,
                        showFolderGlyph = true,
                    )
                )
            }
            directSongs.forEach { add(SongVirtualListItem(it)) }
        }
    }
    val subtreeSongs = remember(currentPath, songs) {
        val path = currentPath
        if (path == null) songs else songs.filter { song ->
            val dir = song.path.substringBeforeLast('/', "").trimEnd('/')
            dir == path || dir.startsWith("$path/")
        }
    }
    val title = currentNode?.name ?: stringResource(com.rawsmusic.core.ui.R.string.library_title_folder_hierarchy)
    val alphabetIndexData = rememberAdaptiveAlphabetIndexData(currentRows) { it.title }

    LaunchedEffect(hierarchy, currentPath, publicationOnly) {
        val path = currentPath
        if (!publicationOnly && hierarchy.isNotEmpty() && transition == null && path != null && path !in nodesByPath) {
            currentPath = null
            virtualListState.viewportScrollY = scrollByPath[ROOT_HIERARCHY_KEY] ?: 0f
        }
    }

    fun currentLevelKey(path: String?): String = path ?: ROOT_HIERARCHY_KEY

    fun retainedProvider(
        rows: List<VirtualListVisualItem>,
        sourceNode: FolderHierarchyNode?,
    ): RegisteredVirtualList {
        val retainedRows = rows.toList()
        return RegisteredVirtualList(NavScene.FOLDER_HIERARCHY).apply {
            this.songs = retainedMappedList(retainedRows) { it.toVirtualListAudioFile() }
            state = virtualListState.copyForRetainedProvider()
            // Critical: Reference retains the complete layout record. Losing this inset made Raw's
            // retained hierarchy population jump upward on frame zero before PivotTransition began.
            contentTopPadding = currentContentTopPadding[0]
            persistentHeaderHeight = if (sourceNode != null) currentHeroOuterHeight[0] else 0.dp
            persistentHeaderVisibilityHeight = persistentHeaderHeight
            persistentHeaderSceneItemVisibilityHeight = persistentHeaderHeight
            persistentHeaderSceneItemId = sourceNode?.let { "cover:folder:${it.id}" }.orEmpty()
            virtualListEdgeEnabled = sourceNode != null
            sharedCoverSceneId = NavScene.FOLDER_HIERARCHY.name
            sharedCoverElementIdProvider = { _, index ->
                retainedRows.getOrNull(index)?.sharedCoverElementId.orEmpty()
            }
            pinchEnabled = true
        }
    }

    fun navigateHierarchy(
        targetPath: String?,
        direction: FolderHierarchyDirection,
        interactive: Boolean = false,
        clickedCover: FolderHierarchyCoverEndpoint? = null,
        gestureDirection: Float = 1f,
        contentGesture: Boolean = false,
    ) {
        if (transition != null || targetPath == currentPath) return
        val sourceNode = currentNode
        scrollByPath[currentLevelKey(currentPath)] = virtualListState.viewportScrollY
        val oldProvider = retainedProvider(currentRows, sourceNode)

        val sharedFolderPath = when (direction) {
            FolderHierarchyDirection.FORWARD -> targetPath
            FolderHierarchyDirection.BACK -> currentPath
        }
        val sharedFolderNode = sharedFolderPath?.let(nodesByPath::get)
        val sharedElementId = sharedFolderNode?.let { "cover:folder:${it.id}" }.orEmpty()
        val sourceHeroCover = currentPath?.let { path ->
            currentHeroEndpoint?.takeIf { it.first == path }?.second
        }
        val sourceCover = when (direction) {
            FolderHierarchyDirection.FORWARD -> clickedCover
            FolderHierarchyDirection.BACK -> sourceHeroCover
        }
        // Target hero geometry must always come from the freshly published destination provider.
        // Keeping a previous visit's endpoint can incorrectly re-enable shared motion after that
        // destination hero has been restored to an offscreen scroll position.
        currentHeroEndpoint = null
        transitionProgress = 0f
        pendingInteractiveProgress = 0f
        transitionSerial += 1
        pageState.drawnLayouts.invalidate()
        transition = FolderHierarchyTransition(
            serial = transitionSerial,
            direction = direction,
            retainedProvider = oldProvider,
            sourcePath = currentPath,
            targetPath = targetPath,
            interactive = interactive,
            gestureDirection = gestureDirection,
            contentGesture = contentGesture,
            sharedFolderPath = sharedFolderPath,
            sharedElementId = sharedElementId,
            sourceCover = sourceCover,
            sourceHeroCover = sourceHeroCover,
            sharedEligible = if (sourceCover == null || sharedElementId.isBlank()) false else null,
        )
        // Do not mutate currentPath/scroll in this transaction. Reference first publishes RETAINED,
        // then prepares CURRENT on the next layout turn. Coalescing both writes was the frame-zero
        // whole-layout jump reported on hierarchy Back.
    }

    val handleHierarchyBack: () -> Unit = {
        val node = currentNode
        if (node == null) {
            onBack()
        } else {
            val parentPath = node.parentId.takeIf { it != 0L }?.let(nodesById::get)?.path
            navigateHierarchy(parentPath, FolderHierarchyDirection.BACK)
        }
    }

    LaunchedEffect(transition?.serial, publicationOnly) {
        if (publicationOnly) return@LaunchedEffect
        val initial = transition ?: return@LaunchedEffect
        // Frame A: source is still CURRENT and an identical RETAINED LayoutRes has been published.
        while (!pageState.drawnLayouts.isReady(initial.serial to initial.sourcePath)) {
            androidx.compose.runtime.withFrameNanos { }
            if (transition?.serial != initial.serial) return@LaunchedEffect
        }
        if (transition?.serial != initial.serial) return@LaunchedEffect

        // Frame B preparation: publish target provider/scroll while source remains RETAINED at p=0.
        // Drop old row geometry before Back so only the freshly laid-out parent provider can supply
        // the shared target. Reference resolves the target from the prepared destination LayoutRes.
        if (initial.direction == FolderHierarchyDirection.BACK) liveFolderRowEndpoints.clear()
        currentPath = initial.targetPath
        virtualListState.viewportScrollY = scrollByPath[currentLevelKey(initial.targetPath)] ?: 0f
        transition = transition?.copy(targetPublished = true)
        while (!pageState.drawnLayouts.isReady(initial.serial to initial.targetPath)) {
            androidx.compose.runtime.withFrameNanos { }
            if (transition?.serial != initial.serial) return@LaunchedEffect
        }
        if (transition?.serial != initial.serial) return@LaunchedEffect

        // Keep p=0 until the concrete shared target exists. Forward waits for the visible child artwork;
        // Back waits for the freshly prepared parent folder-row holder in the current LIST/GRID mode.
        // If either endpoint never appears (offscreen/no artwork), navigation still proceeds as pure
        // PivotTransition instead of fabricating a stale shared element.
        val sourceSnapshot = initial.sourceCover?.toSharedSnapshot(initial.sharedElementId)
        val sharedKey = "$FOLDER_HIERARCHY_SHARED_PREFIX:${initial.serial}"
        val pair = if (sourceSnapshot != null && initial.sharedElementId.isNotBlank()) {
            awaitCollectionSharedPair(candidate = {
                if (transition?.serial != initial.serial) null else {
                    val target = when (initial.direction) {
                        FolderHierarchyDirection.FORWARD -> currentHeroEndpoint
                            ?.takeIf { it.first == initial.targetPath }?.second
                        FolderHierarchyDirection.BACK -> initial.sharedFolderPath?.let(liveFolderRowEndpoints::get)
                    }
                    target?.let { sourceSnapshot to it.toSharedSnapshot(initial.sharedElementId) }
                }
            })
        } else null
        if (transition?.serial != initial.serial) return@LaunchedEffect
        if (pair != null) {
            coverRegistry.prepareEphemeralTransition(sharedKey, pair.first, pair.second)
        }
        val targetCover = pair?.second?.let {
            FolderHierarchyCoverEndpoint(
                it.boundsInWindow.left, it.boundsInWindow.top,
                it.boundsInWindow.right, it.boundsInWindow.bottom,
                it.radiusDp, it.coverKey, initial.sourceCover?.folderId ?: 0L, it.promotedBitmap,
            )
        }
        transition = transition?.copy(sharedEligible = pair != null, targetCover = targetCover)
        if (pair != null) {
            // Match the ordinary collection route: mount the prepared actor, then wait for its
            // readiness handshake. Geometry is not discarded just because the bitmap was cold.
            androidx.compose.runtime.withFrameNanos { }
            awaitSharedActorReady(coverRegistry, sharedKey)
            if (transition?.serial != initial.serial) return@LaunchedEffect
        }

        transition = transition?.copy(readyForMotion = true)

        if (initial.interactive) {
            transitionProgress = pendingInteractiveProgress.coerceIn(0f, 1f)
            return@LaunchedEffect
        }

        animate(
            initialValue = 0f,
            targetValue = 1f,
            animationSpec = tween(
                durationMillis = REFERENCE_FOLDER_HIERARCHY_NAV_MS,
                easing = ReferenceAccelerateDecelerateEasing,
            ),
        ) { value, _ ->
            transitionProgress = value
        }
        transitionProgress = 1f
        androidx.compose.runtime.withFrameNanos { }
        if (transition?.serial == initial.serial) transition = null
    }

    fun settleInteractiveBack(commit: Boolean, normalizedVelocity: Float = 0f) {
        val active = transition?.takeIf { it.interactive && it.direction == FolderHierarchyDirection.BACK } ?: return
        if (active.gestureReleased) return
        transition = active.copy(gestureReleased = true)
        settleJob?.cancel()
        if (!commit && !active.targetPublished) {
            transitionProgress = 1f
            pendingInteractiveProgress = 0f
            transition = null
            return
        }
        settleJob = scope.launch {
            while (transition?.serial == active.serial && transition?.readyForMotion != true) {
                androidx.compose.runtime.withFrameNanos { }
            }
            if (transition?.serial != active.serial) return@launch
            val start = transitionProgress.coerceIn(0f, 1f)
            val target = if (commit) 1f else 0f
            val releaseMotion = collectionReleaseMotion(start, commit, normalizedVelocity)
            animate(
                initialValue = start,
                targetValue = target,
                animationSpec = tween(
                    durationMillis = releaseMotion.durationMillis,
                    easing = if (releaseMotion.carryVelocity) LinearEasing else ReferenceAccelerateDecelerateEasing,
                ),
            ) { value, _ -> transitionProgress = value }

            if (transition?.serial != active.serial) return@launch
            if (commit) {
                transitionProgress = 1f
                androidx.compose.runtime.withFrameNanos { }
                if (transition?.serial == active.serial) transition = null
            } else {
                // At p=0 RETAINED is the exact pre-gesture child: scale=1/alpha=1. Republish that
                // source into CURRENT, but deliberately keep RETAINED + shared artwork alive until
                // CURRENT has completed a layout turn. Releasing both in the same snapshot write
                // creates the same one-frame provider/header jump that this round removes.
                pageState.drawnLayouts.invalidate()
                currentPath = active.sourcePath
                virtualListState.viewportScrollY = scrollByPath[currentLevelKey(active.sourcePath)] ?: 0f
                transitionProgress = 0f

                // Keep RETAINED until the restored source population has actually been published;
                // a frame delay alone does not guarantee that CURRENT has replaced the parent.
                while (!pageState.drawnLayouts.isReady(active.serial to active.sourcePath)) {
                    if (transition?.serial != active.serial) return@launch
                    androidx.compose.runtime.withFrameNanos { }
                }
                if (transition?.serial != active.serial) return@launch
                androidx.compose.runtime.withFrameNanos { }
                if (transition?.serial != active.serial) return@launch

                transitionProgress = 1f
                transition = null
            }
        }
    }

    val navigationEventState = rememberNavigationEventState(currentInfo = NavigationEventInfo.None)
    if (!publicationOnly) {
        SideEffect {
            pageState.contentBackGesture.apply {
                available = ownsHierarchyInput && providerOwnsBack(currentNode != null, transition != null)
                begin = { direction ->
                    val node = currentNode
                    if (transition != null || node == null) false else {
                        val parent = node.parentId.takeIf { it != 0L }?.let(nodesById::get)?.path
                        navigateHierarchy(parent, FolderHierarchyDirection.BACK, interactive = true, gestureDirection = direction, contentGesture = true)
                        true
                    }
                }
                update = { progress ->
                    if (transition?.acceptsGestureProgress(fromContent = true) == true) {
                        pendingInteractiveProgress = progress
                        if (transition?.readyForMotion == true) transitionProgress = progress
                    }
                }
                release = { commit, velocity -> if (transition?.contentGesture == true) settleInteractiveBack(commit, velocity) }
                cancel = { if (transition?.contentGesture == true) settleInteractiveBack(false) }
            }
        }
        DisposableEffect(pageState) {
            onDispose {
                pageState.contentBackGesture.apply {
                    available = false
                    begin = { false }
                    update = {}
                    release = { _, _ -> }
                    cancel = {}
                }
            }
        }
    }
    val latestCurrentNode by rememberUpdatedState(currentNode)
    NavigationBackHandler(
        state = navigationEventState,
        isBackEnabled = ownsHierarchyInput && (currentNode != null || transition?.interactive == true),
        onBackCancelled = { if (transition?.contentGesture == false) settleInteractiveBack(commit = false) },
        onBackCompleted = {
            val active = transition
            if (active?.contentGesture == true) {
                // The common content recognizer owns this pointer transaction, not system back.
            } else if (active?.interactive == true) {
                settleInteractiveBack(commit = true)
            } else {
                val node = latestCurrentNode ?: return@NavigationBackHandler
                val parentPath = node.parentId.takeIf { it != 0L }?.let(nodesById::get)?.path
                navigateHierarchy(parentPath, FolderHierarchyDirection.BACK)
            }
        },
    )

    LaunchedEffect(navigationEventState, currentPath, hierarchy, ownsHierarchyInput) {
        snapshotFlow { navigationEventState.transitionState }.collect { transitionState ->
            if (
                ownsHierarchyInput &&
                transitionState is NavigationEventTransitionState.InProgress &&
                transitionState.direction == NavigationEventTransitionState.TRANSITIONING_BACK
            ) {
                if (transition == null) {
                    val node = currentNode ?: return@collect
                    val parentPath = node.parentId.takeIf { it != 0L }?.let(nodesById::get)?.path
                    navigateHierarchy(
                        parentPath, FolderHierarchyDirection.BACK, interactive = true,
                        gestureDirection = if (transitionState.latestEvent.swipeEdge == NavigationEvent.EDGE_RIGHT) -1f else 1f,
                    )
                }
                if (transition?.acceptsGestureProgress(fromContent = false) == true) {
                    settleJob?.cancel()
                    val progress = transitionState.latestEvent.progress.coerceIn(0f, 1f)
                    pendingInteractiveProgress = progress
                    if (transition?.readyForMotion == true) transitionProgress = progress
                }
            }
        }
    }


    LaunchedEffect(ownsHierarchyInput, publicationOnly) {
        if (!publicationOnly && !ownsHierarchyInput && transition?.interactive == true) {
            settleInteractiveBack(commit = false)
        }
    }
    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                val bounds = coordinates.boundsInRoot()
                if (pageOriginLeft != bounds.left) pageOriginLeft = bounds.left
                if (pageOriginTop != bounds.top) pageOriginTop = bounds.top
            },
    ) {
        val density = LocalDensity.current
        // Freeze BoxWithConstraintsScope.maxWidth before entering LibraryListScaffold's
        // receiver lambda. Compose DSL markers intentionally block the outer implicit receiver.
        val hierarchyHeroOuterSide = maxWidth
        val pivotX = collectionBackPivotX(
            with(density) { hierarchyHeroOuterSide.toPx() },
            transition?.interactive == true,
            transition?.gestureDirection ?: 1f,
        )
        val viewportCenterPivotY = with(density) { maxHeight.toPx() } * 0.5f
        val activeTransition = transition
        val publishedLayoutKey = transitionSerial to currentPath
        val pProvider = { transitionProgress.coerceIn(0f, 1f) }
        val transitionKey = activeTransition?.let { "$FOLDER_HIERARCHY_SHARED_PREFIX:${it.serial}" }.orEmpty()
        val sharedTarget = activeTransition?.targetCover
        val sharedSource = activeTransition?.sourceCover
        val sharedPairReady = activeTransition != null &&
            activeTransition.sharedEligible == true &&
            activeTransition.sharedElementId.isNotBlank() && sharedSource != null && sharedTarget != null
        val sharedOwnsPixels = sharedPairReady && coverRegistry.isOverlayReady(transitionKey)

        // Reference item<->header transition does not pivot both layouts around the viewport centre.
        // The item-side layout pivots around the concrete item's bottom edge; the header-side layout
        // uses the viewport centre. Our custom hierarchy keeps RETAINED=source and CURRENT=target for
        // both directions, so the item-side role changes with direction.
        val retainedPivotY = when {
            !sharedPairReady -> viewportCenterPivotY
            activeTransition?.direction == FolderHierarchyDirection.FORWARD ->
                (sharedSource?.bottom ?: pageOriginTop + viewportCenterPivotY) - pageOriginTop
            else -> viewportCenterPivotY
        }
        val currentPivotY = when {
            !sharedPairReady -> viewportCenterPivotY
            activeTransition?.direction == FolderHierarchyDirection.BACK ->
                (sharedTarget?.bottom ?: pageOriginTop + viewportCenterPivotY) - pageOriginTop
            else -> viewportCenterPivotY
        }

        DisposableEffect(transitionKey, publicationOnly) {
            onDispose {
                if (!publicationOnly && transitionKey.isNotBlank()) coverRegistry.clearEphemeralTransition(transitionKey)
            }
        }

        val localSharedSpec = if (activeTransition != null) {
            SharedTransitionSpec(
                active = true,
                fromSceneId = NavScene.FOLDER_HIERARCHY.name,
                toSceneId = NavScene.FOLDER_HIERARCHY.name,
                activeSceneId = NavScene.FOLDER_HIERARCHY.name,
                progress = 0f,
                progressProvider = pProvider,
                transitionKey = transitionKey,
                ownedElementId = activeTransition.sharedElementId.takeIf { sharedOwnsPixels }.orEmpty(),
                sharedCoverOverlayOwnsAnchor = sharedOwnsPixels,
            )
        } else {
            outerSharedSpec
        }

        val retainedTransform = activeTransition?.let { trans ->
            RetainedSceneItemTransform(
                pivotX = pivotX,
                pivotY = retainedPivotY,
                scaleProvider = {
                    resolveFolderHierarchyRoleMotion(trans.direction, retainedRole = true, pProvider()).scale
                },
                alphaProvider = {
                    resolveFolderHierarchyRoleMotion(trans.direction, retainedRole = true, pProvider()).alpha
                },
            )
        }
        val currentTransform = activeTransition?.let { trans ->
            RetainedSceneItemTransform(
                pivotX = pivotX,
                pivotY = currentPivotY,
                scaleProvider = {
                    resolveFolderHierarchyRoleMotion(trans.direction, retainedRole = false, pProvider()).scale
                },
                alphaProvider = {
                    resolveFolderHierarchyRoleMotion(trans.direction, retainedRole = false, pProvider()).alpha
                },
            )
        }
        val providerFrame = if (activeTransition == null) {
            VirtualListProviderNavigationFrame()
        } else {
            VirtualListProviderNavigationFrame(
                active = true,
                retainedProvider = activeTransition.retainedProvider,
                retainedTransform = retainedTransform,
                currentTransform = currentTransform,
                onPopulationPublished = {
                    if (!publicationOnly) pageState.drawnLayouts.acknowledgeDraw(publishedLayoutKey)
                },
            )
        }

        androidx.compose.runtime.CompositionLocalProvider(
            LocalVirtualListProviderNavigationFrame provides providerFrame,
            LocalSharedTransitionSpec provides localSharedSpec,
        ) {
            val statisticsText = if (currentNode == null) {
                stringResource(
                    com.rawsmusic.core.ui.R.string.library_statistics_hierarchy_root,
                    currentChildren.size,
                    songs.size,
                )
            } else {
                stringResource(
                    com.rawsmusic.core.ui.R.string.library_statistics_hierarchy_level,
                    currentChildren.size,
                    directSongs.size,
                    subtreeSongs.size,
                )
            }
            LibraryListScaffold(
                title = title,
                sceneId = NavScene.FOLDER_HIERARCHY.name,
                statisticsText = statisticsText,
                onBack = handleHierarchyBack,
                virtualListState = virtualListState,
                onShuffle = {
                    onShuffle(
                        if (AppPreferences.Player.shuffleEntireFolderHierarchy) songs else subtreeSongs
                    )
                },
                allowContentBehindTopChrome = currentNode != null,
            ) { topPadding, backdropSource ->
                val heroNode = currentNode
                // Reference hierarchy/detail header starts at window y=0 and lets the top chrome draw
                // over the artwork. Root hierarchy has no hero and keeps the normal library inset.
                val effectiveTopPadding = if (heroNode != null) 0.dp else topPadding
                currentContentTopPadding[0] = effectiveTopPadding
                currentHeroOuterHeight[0] = if (heroNode != null) hierarchyHeroOuterSide else 0.dp
                val hideHeroCover = sharedOwnsPixels && activeTransition?.sharedFolderPath == heroNode?.path
                ComposeGenericVirtualList(
                    items = currentRows,
                    state = virtualListState,
                    contentTopPadding = effectiveTopPadding,
                    persistentHeaderHeight = if (heroNode != null) hierarchyHeroOuterSide else 0.dp,
                    persistentHeaderVisibilityHeight = if (heroNode != null) hierarchyHeroOuterSide else 0.dp,
                    persistentHeaderSceneItemVisibilityHeight = if (heroNode != null) hierarchyHeroOuterSide else 0.dp,
                    persistentHeaderSceneItemId = heroNode?.let { "cover:folder:${it.id}" }.orEmpty(),
                    virtualListEdgeEnabled = heroNode != null,
                    persistentHeaderContent = { _, coverVisible ->
                        if (heroNode != null) {
                            FolderHierarchyHeroHeader(
                                node = heroNode,
                                coverVisible = coverVisible,
                                hideForShared = hideHeroCover,
                                onCoverEndpointChanged = { measuredEndpoint ->
                                    // A fully offscreen header must stop advertising a shared source.
                                    // Reference retains a concrete View/holder slot; it cannot resurrect a logical
                                    // coverKey after the physical header has left the viewport.
                                    if (measuredEndpoint == null) {
                                        if (currentHeroEndpoint?.first == heroNode.path) currentHeroEndpoint = null
                                    } else if (activeTransition == null) {
                                        currentHeroEndpoint = heroNode.path to measuredEndpoint
                                    } else if (heroNode.path == activeTransition.targetPath) {
                                        // CURRENT is already carrying its p=0 item/header scale while
                                        // the destination is prepared. Invert that render transform so
                                        // the shared actor always receives the canonical destination
                                        // LayoutRes endpoint, never a 1.5x preparation rectangle.
                                        val targetMotion = resolveFolderHierarchyRoleMotion(
                                            activeTransition.direction,
                                            retainedRole = false,
                                            transitionProgress.coerceIn(0f, 1f),
                                        )
                                        currentHeroEndpoint = heroNode.path to measuredEndpoint.unscaledAround(
                                            scale = targetMotion.scale,
                                            pivotX = pageOriginLeft + pivotX,
                                            pivotY = pageOriginTop + currentPivotY,
                                        )
                                    }
                                },
                            )
                        }
                    },
                    sharedCoverSceneId = NavScene.FOLDER_HIERARCHY.name,
                    pinchEnabled = activeTransition == null,
                    modifier = Modifier.fillMaxSize().then(backdropSource),
                    onPlayingCoverTargetChanged = { lastClickedCoverTarget[0] = it },
                    onItemCoverTargetChanged = { item, _, target ->
                        if (item is FolderVirtualListItem) {
                            if (target == null) liveFolderRowEndpoints.remove(item.path)
                            else liveFolderRowEndpoints[item.path] = target.toFolderHierarchyEndpoint(item.stableId)
                        }
                    },
                    onItemClick = { item, _, target ->
                        when (item) {
                            is FolderVirtualListItem -> {
                                val frozen = coverRegistry.frozenSourceSnapshot(
                                    NavScene.FOLDER_HIERARCHY.name, item.sharedCoverElementId,
                                )
                                val clicked = frozen?.let {
                                    FolderHierarchyCoverEndpoint(
                                        it.boundsInWindow.left, it.boundsInWindow.top,
                                        it.boundsInWindow.right, it.boundsInWindow.bottom,
                                        it.radiusDp, it.coverKey, item.stableId, it.promotedBitmap,
                                    )
                                } ?: target?.toFolderHierarchyEndpoint(item.stableId)
                                    ?: liveFolderRowEndpoints[item.path]
                                lastClickedCoverTarget[0] = null
                                navigateHierarchy(
                                    targetPath = item.path,
                                    direction = FolderHierarchyDirection.FORWARD,
                                    clickedCover = clicked,
                                )
                            }
                            is SongVirtualListItem -> {
                                val index = directSongs.indexOf(item.song)
                                if (index >= 0) onPlayQueue(directSongs, index)
                            }
                        }
                    },
                )

                run {
                    RawAlphabetIndex(
                        data = alphabetIndexData,
                        scrollActiveProvider = { virtualListState.isListScrollInProgress },
                        modifier = Modifier
                            .align(Alignment.CenterEnd)
                            .padding(top = 92.dp, bottom = LocalBottomChromeInsets.current.contentBottom)
                            .graphicsLayer { alpha = if (activeTransition == null) 1f else pProvider() }
                            .then(backdropSource),
                        onTopSelect = { if (activeTransition == null) virtualListState.requestScrollToIndex(0) },
                        onSelect = { _, index -> if (activeTransition == null) virtualListState.requestScrollToIndex(index) },
                    )
                }
            }
        }

        val retainedHero = activeTransition?.sourceHeroCover
        val sharedOverlayOwnsRetainedHero =
            sharedOwnsPixels && activeTransition?.direction == FolderHierarchyDirection.BACK
        if (activeTransition != null && retainedHero != null && !sharedOverlayOwnsRetainedHero) {
            FolderHierarchyRetainedHeroCover(
                endpoint = retainedHero,
                motionProvider = {
                    resolveFolderHierarchyRoleMotion(activeTransition.direction, retainedRole = true, pProvider())
                },
                pivotX = pivotX,
                pivotY = retainedPivotY,
                originLeft = pageOriginLeft,
                originTop = pageOriginTop,
            )
        }

        if (!publicationOnly && sharedPairReady) {
            SharedCoverOverlay(
                registry = coverRegistry,
                spec = localSharedSpec,
                overlayOriginInWindow = Offset(pageOriginLeft, pageOriginTop),
            )
        }
    }
}

@Composable
private fun FolderHierarchyHeroHeader(
    node: FolderHierarchyNode,
    coverVisible: Boolean,
    hideForShared: Boolean,
    onCoverEndpointChanged: (FolderHierarchyCoverEndpoint?) -> Unit,
) {
    SideEffect {
        if (!coverVisible) onCoverEndpointChanged(null)
    }
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(maxWidth)
                .graphicsLayer { alpha = if (coverVisible && !hideForShared) 1f else 0f }
                .clip(RoundedCornerShape(ReferenceHeroArtworkGeometry.cornerRadius))
                .onGloballyPositioned { coordinates ->
                    if (!coverVisible) return@onGloballyPositioned
                    val bounds = coordinates.boundsInRoot()
                    onCoverEndpointChanged(
                        FolderHierarchyCoverEndpoint(
                            left = bounds.left,
                            top = bounds.top,
                            right = bounds.right,
                            bottom = bounds.bottom,
                            radiusDp = COLLECTION_HEADER_RADIUS_DP,
                            coverKey = node.coverKey,
                            folderId = node.id,
                        )
                    )
                },
        ) {
            CrossfadeAlbumArt(
                key = node.coverKey,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                showPlaceholder = true,
                fadeMillis = 0,
                holdPreviousOnKeyChange = false,
            )
        }
    }
}

@Composable
private fun FolderHierarchyRetainedHeroCover(
    endpoint: FolderHierarchyCoverEndpoint,
    motionProvider: () -> FolderHierarchyRoleMotion,
    pivotX: Float,
    pivotY: Float,
    originLeft: Float,
    originTop: Float,
) {
    if (endpoint.coverKey.isBlank()) return
    val density = LocalDensity.current
    val left = endpoint.left - originLeft
    val top = endpoint.top - originTop
    val originX = if (endpoint.width > 0f) (pivotX - left) / endpoint.width else 0.5f
    val originY = if (endpoint.height > 0f) (pivotY - top) / endpoint.height else 0.5f
    CrossfadeAlbumArt(
        key = endpoint.coverKey,
        modifier = Modifier
            .offset { IntOffset(left.roundToInt(), top.roundToInt()) }
            .requiredSize(
                width = with(density) { endpoint.width.toDp() },
                height = with(density) { endpoint.height.toDp() },
            )
            .clip(RoundedCornerShape(endpoint.radiusDp.dp))
            .graphicsLayer {
                val motion = motionProvider()
                scaleX = motion.scale
                scaleY = motion.scale
                alpha = motion.alpha
                transformOrigin = androidx.compose.ui.graphics.TransformOrigin(originX, originY)
            },
        contentScale = ContentScale.Crop,
        showPlaceholder = true,
        fadeMillis = 0,
        holdPreviousOnKeyChange = false,
    )
}
