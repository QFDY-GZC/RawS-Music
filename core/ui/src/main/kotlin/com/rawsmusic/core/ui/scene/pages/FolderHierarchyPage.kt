package com.rawsmusic.core.ui.scene.pages

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.navigationevent.NavigationEventInfo
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState
import androidx.navigationevent.compose.NavigationBackHandler
import androidx.navigationevent.compose.rememberNavigationEventState
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.model.FolderHierarchyNode
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.data.repository.sourceFolderDirectory
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.LocalBottomChromeInsets
import com.rawsmusic.core.ui.scene.LocalRawWindowLayoutProfile
import com.rawsmusic.core.ui.scene.LocalSharedCoverRegistry
import com.rawsmusic.core.ui.scene.LocalSharedTransitionSpec
import com.rawsmusic.core.ui.scene.NavScene
import com.rawsmusic.core.ui.scene.LocalReferenceLibraryTopChromeOverlayOnly
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderRegistry
import com.rawsmusic.core.ui.scene.COLLECTION_HEADER_RADIUS_DP
import com.rawsmusic.core.ui.scene.RetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.scene.SharedTransitionSpec
import com.rawsmusic.core.ui.scene.awaitCollectionSharedPair
import com.rawsmusic.core.ui.scene.collectionReleaseDuration
import com.rawsmusic.core.ui.scene.collectionReleaseMotion
import com.rawsmusic.core.ui.scene.collectionBackPivotX
import com.rawsmusic.core.ui.scene.LibraryContentBackGesture
import com.rawsmusic.core.ui.scene.providerOwnsBack
import com.rawsmusic.core.ui.scene.VirtualListAccelerateDecelerate
import com.rawsmusic.core.ui.scene.VIRTUAL_LIST_COMMIT_MS
import com.rawsmusic.core.ui.widget.virtuallist.RenderedPopulationReadiness
import com.rawsmusic.core.ui.widget.index.RawAlphabetIndex
import com.rawsmusic.core.ui.widget.index.rememberAdaptiveAlphabetIndexData
import com.rawsmusic.core.ui.widget.virtuallist.ComposeGenericVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.ComposeVirtualListState
import com.rawsmusic.core.ui.widget.virtuallist.FolderVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListProviderNavigationFrame
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListProviderNavigationFrame
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListVisualItem
import com.rawsmusic.core.ui.widget.virtuallist.ReferenceRegisteredVirtualList
import com.rawsmusic.core.ui.widget.virtuallist.SongVirtualListItem
import com.rawsmusic.core.ui.widget.virtuallist.copyForRetainedProvider
import com.rawsmusic.core.ui.widget.virtuallist.formatVirtualListDuration
import com.rawsmusic.core.ui.widget.virtuallist.retainedMappedList
import com.rawsmusic.core.ui.widget.virtuallist.toVirtualListAudioFile
import com.rawsmusic.core.ui.systemui.rawStableStatusBarTopPadding
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos

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
            directSongsByFolder = songs.groupBy { sourceFolderDirectory(it.path) },
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


internal data class FolderHierarchyTransition(
    val serial: Int,
    val direction: FolderHierarchyDirection,
    val retainedProvider: ReferenceRegisteredVirtualList,
    val sourcePath: String?,
    val targetPath: String?,
    val interactive: Boolean,
    val targetPublished: Boolean = false,
    val sharedFolderPath: String? = null,
    val sharedElementId: String = "",
    val sourceCover: FolderHierarchyCoverEndpoint? = null,
    /** Exact physical/window-space source when the click came from an attached VirtualList holder. */
    val sourceSharedSnapshot: SharedCoverSnapshot? = null,
    // null = target preparation still open; true/false is frozen before motion begins.
    // This prevents a late layout callback from creating a shared actor halfway through PivotTransition.
    val sharedEligible: Boolean? = null,
    /** ItemToHeader/HeaderToItem pivots the item-side LayoutRes at holder y + height. */
    val itemPivotY: Float? = null,
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
        // Folder hierarchy endpoints are always published in window coordinates.  The previous
        // implementation mixed boundsInRoot() values from the hero with window coordinates from
        // list holders, which made shared-element interpolation start/end from different spaces.
        boundsInWindow = Rect(left, top, right, bottom),
        coverKey = coverKey,
        radiusDp = radiusDp,
        promotedBitmap = promotedBitmap,
    )

/**
 * retained-layout Folders Hierarchy.
 *
 * One physical VirtualList remains mounted for the whole hierarchy session.  ItemToHeader forward
 * publishes parent/source as RETAINED and child/destination as CURRENT; HeaderToItem back keeps the
 * child/header source CURRENT and prepares the parent/destination in RETAINED.  The shared artwork
 * is the concrete physical holder promoted from the folder item, parked at the hierarchy header
 * between levels, then reactivated and returned to the matching parent-row slot on Back.  If that
 * concrete holder relationship cannot be established, navigation falls back to GenericPivot.
 */
@Composable
fun FolderHierarchyPage(
    songs: List<AudioFile>,
    onBack: () -> Unit,
    onPlayQueue: (List<AudioFile>, Int) -> Unit,
    onShuffle: (List<AudioFile>) -> Unit,
    onSearch: () -> Unit = {},
    virtualListState: ComposeVirtualListState,
    initialPath: String? = null,
    pageState: FolderHierarchyPageState = remember { FolderHierarchyPageState(initialPath) },
    predictiveBackEnabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    // Same provider-metadata owner as Folders/Albums/Artists: LibrarySceneGroupingWarmup builds the
    // hierarchy from the published song snapshot before navigation (prewarmRootProviders), so the
    // first composition already has the authoritative rows instead of waiting on a second
    // repository flow that may still be empty during a cold-start entry.
    val hierarchy by produceState(
        initialValue = LibrarySceneGroupingWarmup.folderHierarchy(songs),
        key1 = songs,
    ) {
        value = LibrarySceneGroupingWarmup.loadFolderHierarchy(songs)
    }
    var currentPath by pageState.pathState
    // Do not getOrCreate() here. Like every other root page, only ComposeVirtualList creates the
    // registry record together with its first publication. An earlier empty record (state == null)
    // made ProviderPreflight treat this scene as already bound and the fixed body rendered an empty
    // list on the first cold visit. Navigation reads the published record lazily.
    val providerRegistry = LocalReferenceLibraryProviderRegistry.current
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
    val currentScaffoldTopPadding = remember { arrayOf(0.dp) }
    val currentHeroOuterHeight = remember { arrayOf(0.dp) }
    val currentHeroCoverHeight = remember { arrayOf(0.dp) }
    val lastClickedCoverTarget = remember { arrayOfNulls<CoverTransitionTarget>(1) }
    var currentHeroEndpoint by pageState.heroEndpointState
    var pageOriginLeft by remember { mutableFloatStateOf(0f) }
    var pageOriginTop by remember { mutableFloatStateOf(0f) }
    // Pivot geometry only. Deliberately not BoxWithConstraints: its SubcomposeLayout deferred the
    // list publication to layout, so ProviderPreflight could not bind this scene like the others.
    var pageSizePx by remember { mutableStateOf(IntSize.Zero) }
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

    fun rowsForPath(path: String?): List<VirtualListVisualItem> {
        val node = path?.let(nodesByPath::get)
        val children = childrenByParent[node?.id ?: 0L].orEmpty()
        val levelSongs = if (path == null) {
            emptyList()
        } else {
            directSongsByFolder[path].orEmpty().sortedBy { it.displayName.lowercase() }
        }
        return buildList(children.size + levelSongs.size) {
            children.forEach { child ->
                add(
                    FolderVirtualListItem(
                        path = child.path,
                        name = child.name,
                        parentName = child.parentLabel,
                        cover = child.coverKey,
                        songCount = child.hierarchicalSongCount,
                        totalDurationMs = child.hierarchicalDurationMs,
                        folderId = child.id,
                        parentFolderId = child.parentId,
                        childFolderCount = child.childFolderCount,
                        showFolderGlyph = true,
                    )
                )
            }
            levelSongs.forEach { add(SongVirtualListItem(it)) }
        }
    }
    val subtreeSongs = remember(currentPath, songs) {
        val path = currentPath
        if (path == null) songs else songs.filter { song ->
            val dir = sourceFolderDirectory(song.path)
            dir == path || dir.startsWith("$path/")
        }
    }
    val chromePath = if (transition != null) transition?.sourcePath else currentPath
    val chromeNode = chromePath?.let(nodesByPath::get)
    val chromeChildren = childrenByParent[chromeNode?.id ?: 0L].orEmpty()
    val chromeDirectSongCount = if (chromePath == null) 0 else directSongsByFolder[chromePath].orEmpty().size
    val title = chromeNode?.name ?: stringResource(com.rawsmusic.core.ui.R.string.library_title_folder_hierarchy)
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
        scrollY: Float = virtualListState.viewportScrollY,
        topPadding: Dp = currentContentTopPadding[0],
    ): ReferenceRegisteredVirtualList {
        val retainedRows = rows.toList()
        return ReferenceRegisteredVirtualList(
            NavScene.FOLDER_HIERARCHY,
            physicalPoolIdentity = providerRegistry?.get(NavScene.FOLDER_HIERARCHY)
                ?.nestedPhysicalIdentity(sourceNode?.path.orEmpty()) ?: Any(),
        ).apply {
            this.songs = retainedMappedList(retainedRows) { it.toVirtualListAudioFile() }
            state = virtualListState.copyForRetainedProvider().also { retainedState ->
                retainedState.viewportScrollY = scrollY
            }
            // Critical: Reference retains the complete layout record. Losing this inset made Raw's
            // retained hierarchy population jump upward on frame zero before PivotTransition began.
            contentTopPadding = topPadding
            persistentHeaderHeight = if (sourceNode != null) currentHeroOuterHeight[0] else 0.dp
            persistentHeaderVisibilityHeight = persistentHeaderHeight
            persistentHeaderSceneItemVisibilityHeight = if (sourceNode != null) currentHeroCoverHeight[0] else 0.dp
            persistentHeaderSceneItemId = sourceNode?.let { "cover:folder:${it.id}" }.orEmpty()
            virtualListEdgeEnabled = sourceNode != null
            sharedCoverSceneId = NavScene.FOLDER_HIERARCHY.name
            if (sourceNode != null) {
                val sourceElementId = "cover:folder:${sourceNode.id}"
                persistentHeaderContent = { _, coverVisible ->
                    val sharedSpec = LocalSharedTransitionSpec.current
                    val sharedOwner = sharedSpec.active &&
                        sharedSpec.ownedElementId == sourceElementId &&
                        sharedSpec.sharedCoverOverlayOwnsAnchor
                    FolderHierarchyHeroHeader(
                        node = sourceNode,
                        coverHeight = currentHeroCoverHeight[0],
                        coverVisible = coverVisible,
                        hideForShared = sharedOwner || coverRegistry.isPhysicalPromotedElement(sourceElementId) ||
                            coverRegistry.isPhysicalStashedElement(sourceElementId),
                        freezeArtworkUpdates = true,
                        onBack = {},
                        onShuffle = {},
                        onSearch = {},
                        // RETAINED owns its own LayoutRes/header geometry. Only CURRENT publishes
                        // the destination endpoint used to prepare the next shared transaction.
                        onCoverEndpointChanged = {},
                    )
                }
            }
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
        clickedSharedSnapshot: SharedCoverSnapshot? = null,
        gestureDirection: Float = 1f,
        contentGesture: Boolean = false,
    ) {
        if (transition != null || targetPath == currentPath) return
        val sourceNode = currentNode
        scrollByPath[currentLevelKey(currentPath)] = virtualListState.viewportScrollY
        val retainedProviderForTransition = when (direction) {
            FolderHierarchyDirection.FORWARD -> retainedProvider(
                rows = currentRows,
                sourceNode = sourceNode,
                scrollY = virtualListState.viewportScrollY,
                topPadding = currentContentTopPadding[0],
            )
            FolderHierarchyDirection.BACK -> {
                val targetNode = targetPath?.let(nodesByPath::get)
                retainedProvider(
                    rows = rowsForPath(targetPath),
                    sourceNode = targetNode,
                    scrollY = scrollByPath[currentLevelKey(targetPath)] ?: 0f,
                    topPadding = if (targetNode != null) 0.dp else currentScaffoldTopPadding[0],
                )
            }
        }

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
        val sourceSharedSnapshot = when (direction) {
            FolderHierarchyDirection.FORWARD -> clickedSharedSnapshot
            FolderHierarchyDirection.BACK -> sourceHeroCover?.toSharedSnapshot(sharedElementId)
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
            retainedProvider = retainedProviderForTransition,
            sourcePath = currentPath,
            targetPath = targetPath,
            interactive = interactive,
            gestureDirection = gestureDirection,
            contentGesture = contentGesture,
            sharedFolderPath = sharedFolderPath,
            sharedElementId = sharedElementId,
            sourceCover = sourceCover,
            sourceSharedSnapshot = sourceSharedSnapshot,
            sharedEligible = if ((sourceSharedSnapshot == null && sourceCover == null) || sharedElementId.isBlank()) false else null,
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
        // The two concrete transition types use opposite LayoutRes roles:
        // ItemToHeader keeps the parent/source in RETAINED, while HeaderToItem keeps the
        // parent/destination in RETAINED and leaves the child/header source CURRENT.
        val firstPreparedPath = if (initial.direction == FolderHierarchyDirection.BACK) {
            initial.targetPath
        } else {
            initial.sourcePath
        }
        while (!pageState.drawnLayouts.isReady(initial.serial to firstPreparedPath)) {
            androidx.compose.runtime.withFrameNanos { }
            if (transition?.serial != initial.serial) return@LaunchedEffect
        }
        if (transition?.serial != initial.serial) return@LaunchedEffect

        if (initial.direction == FolderHierarchyDirection.FORWARD) {
            // ItemToHeader: after the source RETAINED record exists, publish child as CURRENT.
            currentPath = initial.targetPath
            virtualListState.viewportScrollY = scrollByPath[currentLevelKey(initial.targetPath)] ?: 0f
            transition = transition?.copy(targetPublished = true)
            while (!pageState.drawnLayouts.isReady(initial.serial to initial.targetPath)) {
                androidx.compose.runtime.withFrameNanos { }
                if (transition?.serial != initial.serial) return@LaunchedEffect
            }
            if (transition?.serial != initial.serial) return@LaunchedEffect
        } else {
            // HeaderToItem: the target parent was already published into RETAINED by navigateHierarchy().
            // Keep currentPath on the child until the physical holder has been returned at p=1.
            transition = transition?.copy(targetPublished = true)
        }

        val sourceSnapshot = initial.sourceSharedSnapshot
            ?: initial.sourceCover?.toSharedSnapshot(initial.sharedElementId)
        val sharedKey = "$FOLDER_HIERARCHY_SHARED_PREFIX:${initial.serial}"
        val pair = if (sourceSnapshot != null && initial.sharedElementId.isNotBlank()) {
            awaitCollectionSharedPair(candidate = {
                if (transition?.serial != initial.serial) null else {
                    val targetSnapshot = when (initial.direction) {
                        FolderHierarchyDirection.FORWARD -> currentHeroEndpoint
                            ?.takeIf { it.first == initial.targetPath }
                            ?.second
                            ?.toSharedSnapshot(initial.sharedElementId)
                        FolderHierarchyDirection.BACK -> coverRegistry.resolvePhysicalRoleEndpoint(
                            sceneId = NavScene.FOLDER_HIERARCHY.name,
                            elementId = initial.sharedElementId,
                            retainedRole = true,
                        )
                    }
                    targetSnapshot?.let { sourceSnapshot to it }
                }
            })
        } else null
        if (transition?.serial != initial.serial) return@LaunchedEffect

        var physicalShared = false
        if (pair != null) {
            coverRegistry.prepareEphemeralTransition(sharedKey, pair.first, pair.second)
            physicalShared = when (initial.direction) {
                FolderHierarchyDirection.FORWARD -> coverRegistry.promotePreparedPhysicalSource(sharedKey)
                FolderHierarchyDirection.BACK ->
                    coverRegistry.activateStashedPhysicalPromotion(initial.sharedElementId)
            }
            if (!physicalShared) {
                // Fall back to GenericPivot when the concrete matching holder cannot be
                // acquired. Do not synthesize a second bitmap overlay for hierarchy navigation.
                coverRegistry.clearEphemeralTransition(sharedKey)
            }
        }
        val itemPivotY = pair?.takeIf { physicalShared }?.let { resolvedPair ->
            when (initial.direction) {
                FolderHierarchyDirection.FORWARD -> resolvedPair.first.itemViewportBounds?.bottom
                FolderHierarchyDirection.BACK -> resolvedPair.second.itemViewportBounds?.bottom
            }
        }
        transition = transition?.copy(
            sharedEligible = physicalShared,
            itemPivotY = itemPivotY,
        )

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
        if (transition?.serial != initial.serial) return@LaunchedEffect

        if (physicalShared) {
            when (initial.direction) {
                // ItemToHeader commit keeps the migrated concrete holder as the settled child
                // header. It will only be parked if the user enters another child from this scene.
                FolderHierarchyDirection.FORWARD -> Unit
                FolderHierarchyDirection.BACK ->
                    coverRegistry.finishPhysicalReturn(initial.sharedElementId)
            }
        }

        if (initial.direction == FolderHierarchyDirection.BACK) {
            // HeaderToItem commits destination RETAINED into CURRENT only after the same concrete
            // holder has been reattached to the target item slot.
            pageState.drawnLayouts.invalidate()
            currentPath = initial.targetPath
            virtualListState.viewportScrollY = scrollByPath[currentLevelKey(initial.targetPath)] ?: 0f
            while (!pageState.drawnLayouts.isReady(initial.serial to initial.targetPath)) {
                androidx.compose.runtime.withFrameNanos { }
                if (transition?.serial != initial.serial) return@LaunchedEffect
            }
            androidx.compose.runtime.withFrameNanos { }
        }
        if (transition?.serial == initial.serial) transition = null
    }

    fun settleInteractiveBack(commit: Boolean, normalizedVelocity: Float = 0f) {
        val active = transition?.takeIf { it.interactive && it.direction == FolderHierarchyDirection.BACK } ?: return
        if (active.gestureReleased) return
        transition = active.copy(gestureReleased = true)
        settleJob?.cancel()
        if (!commit && !active.targetPublished) {
            transitionProgress = 0f
            pendingInteractiveProgress = 0f
            active.sourceCover?.let { source ->
                active.sourcePath?.let { path -> currentHeroEndpoint = path to source }
            }
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
                if (transition?.serial != active.serial) return@launch
                val resolved = transition ?: return@launch
                if (resolved.sharedEligible == true) {
                    coverRegistry.finishPhysicalReturn(resolved.sharedElementId)
                }
                pageState.drawnLayouts.invalidate()
                currentPath = resolved.targetPath
                virtualListState.viewportScrollY = scrollByPath[currentLevelKey(resolved.targetPath)] ?: 0f
                while (!pageState.drawnLayouts.isReady(resolved.serial to resolved.targetPath)) {
                    if (transition?.serial != resolved.serial) return@launch
                    androidx.compose.runtime.withFrameNanos { }
                }
                androidx.compose.runtime.withFrameNanos { }
                if (transition?.serial == resolved.serial) transition = null
            } else {
                transitionProgress = 0f
                androidx.compose.runtime.withFrameNanos { }
                if (transition?.serial != active.serial) return@launch
                val resolved = transition ?: return@launch
                // Cancelling HeaderToItem keeps the child authoritative. Retain its migrated
                // header instead of restoring the View to a parent row that is not being entered.
                resolved.sourceCover?.let { source ->
                    resolved.sourcePath?.let { path -> currentHeroEndpoint = path to source }
                }
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
    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { if (pageSizePx != it) pageSizePx = it }
            .onGloballyPositioned { coordinates ->
                // SharedCoverSnapshot is explicitly window-space. Keep the hierarchy overlay
                // origin in the same coordinate system; boundsInRoot() is only equivalent while
                // the Compose root itself happens to start at the window origin.
                val position = coordinates.positionInWindow()
                if (pageOriginLeft != position.x) pageOriginLeft = position.x
                if (pageOriginTop != position.y) pageOriginTop = position.y
            },
    ) {
        val hierarchyHeroCoverHeight =
            collectionHeaderBaseHeight(NavScene.FOLDER_HIERARCHY) +
                rawStableStatusBarTopPadding()
        val hierarchyHeroTotalHeight = hierarchyHeroCoverHeight
        val pivotX = collectionBackPivotX(
            pageSizePx.width.toFloat(),
            transition?.interactive == true,
            transition?.gestureDirection ?: 1f,
        )
        val viewportCenterPivotY = pageSizePx.height * 0.5f
        val activeTransition = transition
        val publishedLayoutKey = when {
            activeTransition?.direction == FolderHierarchyDirection.BACK &&
                currentPath == activeTransition.sourcePath ->
                activeTransition.serial to activeTransition.targetPath
            else -> transitionSerial to currentPath
        }
        val pProvider = { transitionProgress.coerceIn(0f, 1f) }
        val transitionKey = activeTransition?.let { "$FOLDER_HIERARCHY_SHARED_PREFIX:${it.serial}" }.orEmpty()
        val sharedPairReady = activeTransition != null &&
            activeTransition.sharedEligible == true &&
            activeTransition.sharedElementId.isNotBlank() &&
            activeTransition.itemPivotY != null
        val sharedOwnsPixels = sharedPairReady &&
            coverRegistry.isPhysicalPromotedElement(activeTransition.sharedElementId)

        // ItemToHeader/HeaderToItem uses the item-side LayoutRes bottom edge as pivot and
        // the header-side LayoutRes viewport centre. Back keeps the destination parent in RETAINED.
        val retainedPivotY = activeTransition?.itemPivotY?.takeIf { sharedPairReady }
            ?: viewportCenterPivotY
        val currentPivotY = viewportCenterPivotY

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
            VirtualListProviderNavigationFrame(
                physicalProviderIdentity = currentPath.orEmpty(),
                settledSharedTarget = currentHeroEndpoint
                    ?.takeIf { it.first == currentPath }
                    ?.second
                    ?.let { it.toSharedSnapshot("cover:folder:${it.folderId}") },
            )
        } else {
            VirtualListProviderNavigationFrame(
                active = true,
                physicalProviderIdentity = currentPath.orEmpty(),
                // At a committed Back endpoint, CURRENT still needs to acquire the parent rows.
                readyForMotion = activeTransition.readyForMotion &&
                    !(activeTransition.direction == FolderHierarchyDirection.BACK &&
                        currentPath == activeTransition.targetPath),
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
            val statisticsText = if (chromeNode == null) {
                stringResource(
                    com.rawsmusic.core.ui.R.string.library_statistics_hierarchy_root,
                    chromeChildren.size,
                    songs.size,
                )
            } else {
                stringResource(
                    com.rawsmusic.core.ui.R.string.library_statistics_hierarchy_level,
                    chromeChildren.size,
                    chromeDirectSongCount,
                    chromeNode.hierarchicalSongCount,
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
                currentScaffoldTopPadding[0] = topPadding
                // Reference hierarchy/detail header starts at window y=0 and lets the top chrome draw
                // over the artwork. Root hierarchy has no hero and keeps the normal library inset.
                val effectiveTopPadding = if (heroNode != null) 0.dp else topPadding
                currentContentTopPadding[0] = effectiveTopPadding
                currentHeroCoverHeight[0] = if (heroNode != null) hierarchyHeroCoverHeight else 0.dp
                currentHeroOuterHeight[0] = if (heroNode != null) hierarchyHeroTotalHeight else 0.dp
                val heroElementId = heroNode?.let { "cover:folder:${it.id}" }.orEmpty()
                val hideHeroCover =
                    (sharedOwnsPixels && activeTransition.sharedFolderPath == heroNode?.path) ||
                        coverRegistry.isPhysicalPromotedElement(heroElementId) ||
                        (activeTransition?.retainedProvider?.persistentHeaderSceneItemId == heroElementId &&
                            coverRegistry.isPhysicalStashedElement(heroElementId))
                ComposeGenericVirtualList(
                    items = currentRows,
                    state = virtualListState,
                    contentTopPadding = effectiveTopPadding,
                    persistentHeaderHeight = if (heroNode != null) hierarchyHeroTotalHeight else 0.dp,
                    persistentHeaderVisibilityHeight = if (heroNode != null) hierarchyHeroTotalHeight else 0.dp,
                    persistentHeaderSceneItemVisibilityHeight = if (heroNode != null) hierarchyHeroCoverHeight else 0.dp,
                    persistentHeaderSceneItemId = heroElementId,
                    virtualListEdgeEnabled = heroNode != null,
                    persistentHeaderContent = { _, coverVisible ->
                        if (heroNode != null) {
                            FolderHierarchyHeroHeader(
                                node = heroNode,
                                coverHeight = hierarchyHeroCoverHeight,
                                coverVisible = coverVisible,
                                hideForShared = hideHeroCover,
                                freezeArtworkUpdates = activeTransition != null,
                                headerEdgeOvershootProvider = { virtualListState.virtualListHeaderEdgeOvershootPx },
                                listEdgeOvershootProvider = { virtualListState.virtualListEdgeOvershootPx },
                                onBack = handleHierarchyBack,
                                onShuffle = {
                                    onShuffle(
                                        if (AppPreferences.Player.shuffleEntireFolderHierarchy) songs else subtreeSongs
                                    )
                                },
                                onSearch = onSearch,
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
                                    clickedSharedSnapshot = frozen,
                                )
                            }
                            is SongVirtualListItem -> {
                                val index = directSongs.indexOf(item.song)
                                if (index >= 0) onPlayQueue(directSongs, index)
                            }
                        }
                    },
                )
                if (publicationOnly) return@LibraryListScaffold

                run {
                    RawAlphabetIndex(
                        sceneId = NavScene.FOLDER_HIERARCHY.name,
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

    }
}

@Composable
private fun FolderHierarchyHeroHeader(
    node: FolderHierarchyNode,
    coverHeight: Dp,
    coverVisible: Boolean,
    hideForShared: Boolean,
    freezeArtworkUpdates: Boolean,
    headerEdgeOvershootProvider: () -> Float = { 0f },
    listEdgeOvershootProvider: () -> Float = { 0f },
    onBack: () -> Unit,
    onShuffle: () -> Unit,
    onSearch: () -> Unit,
    onCoverEndpointChanged: (FolderHierarchyCoverEndpoint?) -> Unit,
) {
    val windowLayoutProfile = LocalRawWindowLayoutProfile.current
    SideEffect {
        if (!coverVisible) onCoverEndpointChanged(null)
    }
    CollectionHeroHeader(
            hero = CollectionHeroData(
                stableKey = node.path,
                sharedElementId = "cover:folder:${node.id}",
                coverKey = node.coverKey,
                title = node.name,
                subtitle = node.parentLabel,
                meta = "${node.hierarchicalSongCount} | ${formatVirtualListDuration(node.hierarchicalDurationMs)}",
                songs = emptyList(),
            ),
            geometry = CollectionHeroWindowGeometry(
                headerHeight = coverHeight,
                artworkLeft = 0.dp,
                artworkTop = 0.dp,
                artworkWidth = windowLayoutProfile.libraryContentMaxWidth,
                artworkHeight = coverHeight,
                wide = false,
            ),
            listScene = NavScene.FOLDER_HIERARCHY,
            detailScene = NavScene.FOLDER_HIERARCHY,
            coverVisible = coverVisible,
            freezeArtworkUpdates = freezeArtworkUpdates,
            onBack = onBack,
            onMore = {},
            onShuffle = onShuffle,
            onSearch = onSearch,
            onBiography = null,
            onHeroArtworkLongPress = null,
            onHeroArtworkBoundsChanged = { bounds ->
                if (coverVisible) {
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
                }
            },
            hideHeroArtwork = hideForShared || !coverVisible,
            // The hierarchy remains one recursive scene. Its exact source/target pair is supplied
            // by the provider-role transaction above; registering the header under the same scene
            // key as the row would overwrite that row endpoint in SharedCoverRegistry.
            publishSharedAnchor = false,
            // LibraryListScaffold continues to own the hierarchy back affordance over the artwork.
            // Drawing the collection button too would create two physical back owners in one scene.
            showBackButton = false,
            // Hierarchy currently has no collection sort/more sheet; hide only the unsupported
            // action instead of rendering a dead button. Shuffle/search now use the same hero UI.
            showMoreAction = false,
            headerEdgeOvershootProvider = headerEdgeOvershootProvider,
            listEdgeOvershootProvider = listEdgeOvershootProvider,
        )
}
