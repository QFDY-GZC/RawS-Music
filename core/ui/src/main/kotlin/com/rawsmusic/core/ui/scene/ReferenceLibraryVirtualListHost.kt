package com.rawsmusic.core.ui.scene

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.zIndex
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryBodyRender
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderIdentity
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderRegistry
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderScene
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceLibraryProviderPublicationOnly
import com.rawsmusic.core.ui.widget.virtuallist.LocalReferenceRetainedVirtualListProvider
import com.rawsmusic.core.ui.widget.virtuallist.ReferenceLibraryProviderRegistry
import com.rawsmusic.core.ui.widget.virtuallist.ComposeReferenceLibraryProviderBody
import com.rawsmusic.core.ui.widget.virtuallist.ComposeReferencePreparedCustomPopulation
import com.rawsmusic.core.ui.widget.virtuallist.ComposeReferencePreparedOrdinaryPopulation
import com.rawsmusic.core.ui.widget.virtuallist.ComposeReferencePreparedPersistentHeader
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListPersistentRuntime
import com.rawsmusic.core.ui.widget.virtuallist.LocalVirtualListPresentationHostExternal
import com.rawsmusic.core.ui.widget.virtuallist.ReferenceLibraryPresentationHostBackend
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListPresentationHostBackend
import com.rawsmusic.core.ui.widget.virtuallist.virtualListPresentationHost
import com.rawsmusic.core.ui.widget.virtuallist.VirtualListPersistentRuntime
import dev.chrisbanes.haze.hazeSource
import kotlinx.coroutines.flow.distinctUntilChanged

/** True only for the retained provider/layout population inside the single library owner. */
internal val LocalReferenceRetainedLibraryPopulation = staticCompositionLocalOf { false }

/**
 * Compose only the destination TopNav shell for a retained HOME/category handoff.
 *
 * The persistent host keeps the source controller mounted for the whole GenericPivot so its input
 * and list provider identity remain stable. reference player still has both retained scene layout child states
 * available during the handoff, however, so Raw composes one lightweight destination-chrome pass
 * beneath the source chrome. Pages must return before publishing/rendering their list body when
 * this flag is true.
 */
internal val LocalReferenceLibraryTopChromeOverlayOnly = staticCompositionLocalOf { false }

internal val ReferenceLibraryScenes: Set<NavScene> = setOf(
    NavScene.SONG_STATS,
    NavScene.LIBRARY_ANALYSIS_DETAIL,
    NavScene.HOME,
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.FOLDER_DETAIL,
    NavScene.ALBUMS,
    NavScene.ALBUM_DETAIL,
    NavScene.ARTISTS,
    NavScene.ARTIST_DETAIL,
    NavScene.ARTIST_BIOGRAPHY,
    NavScene.GENRE,
    NavScene.GENRE_DETAIL,
    NavScene.YEAR,
    NavScene.YEAR_DETAIL,
    NavScene.COMPOSER,
    NavScene.COMPOSER_DETAIL,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
)

internal fun isReferenceLibraryScene(scene: NavScene): Boolean = scene in ReferenceLibraryScenes

private val ReferenceLibraryTopChromeScenes = setOf(
    NavScene.SONG_STATS,
    NavScene.LIBRARY_ANALYSIS_DETAIL,
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.GENRE,
    NavScene.YEAR,
    NavScene.COMPOSER,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
)

/**
 * Resolve the destination whose provider must be bound as VirtualList's next/retained provider.
 * The visible owner remains one persistent VirtualList. The destination page is composed only in
 * an invisible publication slot while it is being prepared; the visible page slot is never moved
 * between parents at the endpoint. List holders themselves remain owned by the one persistent
 * VirtualList runtime.
 */
internal fun resolveReferenceLibraryStandbyScene(
    frame: ReferenceLibraryLayoutFrame,
    currentScene: NavScene,
): NavScene? = frame.preparingScene
    ?.takeIf { it != currentScene && isReferenceLibraryScene(it) }
    ?: frame.retainedScene?.takeIf {
        frame.active && it != currentScene && isReferenceLibraryScene(it)
    }

internal data class ReferenceLibraryHostFrameResolution(
    val frame: ReferenceLibraryLayoutFrame,
    val presentationScene: NavScene,
    val presentsRetainedRole: Boolean,
)

/**
 * Keep the settled presentation/controller on the true visual source at transition start.
 *
 * Forward HOME/category navigation promotes displayedScene to the destination before GenericPivot
 * starts. Reference keeps the old VirtualList layout as the source LayoutRes instead of switching the
 * whole controller geometry first. Back motion already leaves displayedScene on the visual source.
 */
internal fun resolveReferenceLibraryHostFrame(
    frame: ReferenceLibraryLayoutFrame,
    requestedScene: NavScene,
): ReferenceLibraryHostFrameResolution {
    val requestedCurrentScene = frame.currentScene ?: requestedScene
    val presentationScene = frame.presentationScene
        ?.takeIf(::isReferenceLibraryScene)
        ?: requestedCurrentScene
    val presentsRetainedRole = frame.active &&
        presentationScene == frame.retainedScene &&
        presentationScene != requestedCurrentScene
    val effectiveFrame = if (presentsRetainedRole) {
        frame.copy(
            retainedScene = requestedCurrentScene,
            currentScene = presentationScene,
            retainedProviderIdentity = frame.currentProviderIdentity,
            currentProviderIdentity = frame.retainedProviderIdentity,
            retainedTransform = frame.currentTransform,
            currentTransform = frame.retainedTransform,
        )
    } else {
        frame.copy(currentScene = presentationScene)
    }
    return ReferenceLibraryHostFrameResolution(
        frame = effectiveFrame,
        presentationScene = presentationScene,
        presentsRetainedRole = presentsRetainedRole,
    )
}

/**
 * One persistent VirtualList owner for HOME and the root library providers.
 *
 * ReferenceGenericPivotTransitionBase owns exactly one VirtualList.  The previous provider does not
 * survive by keeping a second Fragment/page/View tree alive; it survives as VirtualList's retained
 * provider/layout state.  Raw mirrors that boundary here: only the current controller/page is
 * composed in the fixed visible slot. Its ComposeVirtualList call publishes the current provider
 * and immediately renders the one physical list body at that same page-owned call site. The prior
 * provider is a plain retained snapshot in
 * [ReferenceLibraryProviderRegistry], while a prepared destination uses a separate invisible
 * publication pass until the endpoint.
 */
@Composable
internal fun ReferenceLibraryVirtualListHost(
    requestedScene: NavScene,
    registry: ReferenceLibraryProviderRegistry,
    physicalRuntime: VirtualListPersistentRuntime,
    presentationVisible: Boolean = true,
    controllerVisible: Boolean = true,
    providerIdentity: (NavScene) -> String = { "" },
    modifier: Modifier = Modifier,
    content: @Composable (NavScene) -> Unit,
) {
    val appHazeState = LocalAppHazeState.current
    val pageFrameAnimationActive = LocalUiFrameAnimationActive.current && presentationVisible
    // Ordinary VirtualList pixels are drawn by this fixed presentation owner, not by the page
    // publisher. Keep the real presentation inside the shared Haze source graph so the fixed top
    // chrome can progressively blur rows/artwork after the external-presentation migration.
    val persistentListHazeSource = if (
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && appHazeState != null
    ) {
        Modifier.hazeSource(appHazeState)
    } else {
        Modifier
    }
    val frame = LocalReferenceLibraryLayoutFrame.current
    val resolvedFrame = resolveReferenceLibraryHostFrame(frame, requestedScene)
    val effectiveFrame = resolvedFrame.frame
    val presentationScene = resolvedFrame.presentationScene
    val currentScene = effectiveFrame.currentScene ?: presentationScene
    fun roleProviderIdentity(scene: NavScene): String {
        if (!scene.isDetail()) return ""
        return when (scene) {
            effectiveFrame.currentScene -> effectiveFrame.currentProviderIdentity
            effectiveFrame.retainedScene -> effectiveFrame.retainedProviderIdentity
            effectiveFrame.preparingScene -> effectiveFrame.preparingProviderIdentity
            effectiveFrame.presentationScene -> effectiveFrame.presentationProviderIdentity
            else -> providerIdentity(scene)
        }
    }
    val providerMotionActive = registry[currentScene]?.providerNavigationActive == true
    val currentContent by rememberUpdatedState(content)
    val sharedCoverRegistry = LocalSharedCoverRegistry.current
    val sharedTransitionSpec = LocalSharedTransitionSpec.current
    val preparedSharedPair = if (sharedTransitionSpec.transitionKey.isNotBlank()) {
        sharedCoverRegistry.getPreparedPair(sharedTransitionSpec.transitionKey)
    } else {
        null
    }
    val collectionSession = sharedCoverRegistry.collectionSessionSnapshot()
    val sharedTransitionActiveState = rememberUpdatedState(sharedTransitionSpec.active)
    val requestedSceneState = rememberUpdatedState(requestedScene)
    // baseline implementation owns exactly one VirtualList object throughout GenericPivot. Keep the physical
    // holder allocator/LayoutRes table and the presentation node beside the retained provider
    // registry for the complete HOME/root-library owner lifetime.
    val externalPresentationTransform =
        LocalExternalRetainedSceneItemTransform.current.takeUnless { effectiveFrame.active }

    SideEffect {
        val runtime = physicalRuntime.settledNodeRuntime
        val pair = preparedSharedPair
        when {
            sharedTransitionSpec.active &&
                pair != null &&
                runtime.hasPromotedSharedHolder(pair.first.elementId) -> {
                runtime.updateSharedHolderGeometry(
                    transitionKey = sharedTransitionSpec.transitionKey,
                    target = pair.second,
                    progressProvider = sharedTransitionSpec.progressProvider,
                    animateFromCurrent = true,
                )
            }

            !sharedTransitionSpec.active &&
                collectionSession != null &&
                requestedScene.name == collectionSession.detailSceneId &&
                runtime.hasPromotedSharedHolder(collectionSession.elementId) -> {
                val liveHeader = sharedCoverRegistry.liveSnapshot(
                    sceneId = collectionSession.detailSceneId,
                    elementId = collectionSession.elementId,
                ) ?: collectionSession.headerEndpoint
                runtime.updateSharedHolderGeometry(
                    transitionKey = "detail:${collectionSession.elementId}",
                    target = liveHeader,
                    progressProvider = null,
                    animateFromCurrent = false,
                )
            }

        }
    }

    DisposableEffect(sharedCoverRegistry, physicalRuntime) {
        val owner = Any()
        sharedCoverRegistry.attachPhysicalReturnFinalizer(owner) { elementId ->
            physicalRuntime.settledNodeRuntime.finishSharedHolderReturn(elementId)
        }
        sharedCoverRegistry.attachPhysicalPromotionCanceller(owner) { elementId ->
            physicalRuntime.settledNodeRuntime.cancelSharedHolderPromotion(elementId)
        }
        sharedCoverRegistry.attachPhysicalPromotionStarter(owner) { source, target, transitionKey ->
            val runtime = physicalRuntime.settledNodeRuntime
            val promoted = runtime.beginSharedHolderPromotion(
                slotId = source.physicalSlotId,
                elementId = source.elementId,
            )
            if (promoted) {
                // holder migration in reference player runs only after NEXT LayoutRes exists, so the migrated View
                // already belongs to a transition with both endpoints. Seed Raw's promoted holder
                // with that target synchronously too; progress stays at source=0 until the scene
                // clock starts, then the normal SideEffect swaps in the live provider.
                runtime.updateSharedHolderGeometry(
                    transitionKey = transitionKey,
                    target = target,
                    progressProvider = { 0f },
                    animateFromCurrent = true,
                )
            }
            promoted
        }
        sharedCoverRegistry.attachPhysicalPromotionLiveness(owner) { elementId ->
            physicalRuntime.settledNodeRuntime.hasPromotedSharedHolder(elementId)
        }
        sharedCoverRegistry.attachPhysicalEndpointListener(owner) { snapshot ->
            if (sharedTransitionActiveState.value) return@attachPhysicalEndpointListener
            val session = sharedCoverRegistry.collectionSessionSnapshot()
                ?: return@attachPhysicalEndpointListener
            if (
                session.elementId != snapshot.elementId ||
                session.detailSceneId != snapshot.sceneId ||
                requestedSceneState.value.name != session.detailSceneId
            ) return@attachPhysicalEndpointListener
            physicalRuntime.settledNodeRuntime.updateSharedHolderGeometry(
                transitionKey = "detail:${session.elementId}",
                target = snapshot,
                progressProvider = null,
                animateFromCurrent = false,
            )
        }
        sharedCoverRegistry.attachCollectionReturnTargetPreparer(owner) returnPreparer@{ session ->
            val targetScene = NavScene.entries.firstOrNull { it.name == session.listSceneId }
                ?: return@returnPreparer null
            val provider = registry[targetScene] ?: return@returnPreparer null
            val targetState = provider.state ?: return@returnPreparer null
            val oldEndpoint = session.listEndpoint
            val holderBounds = oldEndpoint.itemViewportBounds ?: return@returnPreparer null
            val itemIndex = oldEndpoint.itemIndex.takeIf { it in provider.songs.indices }
                ?: provider.songs.indices.firstOrNull { index ->
                    provider.sharedCoverElementIdProvider(provider.songs[index], index) == session.elementId
                }
                ?: return@returnPreparer null
            val scrollDelta = targetState.prepareCollectionReturnCenter(
                itemTopPx = holderBounds.top,
                itemBottomPx = holderBounds.bottom,
            ) ?: return@returnPreparer null

            // A positive destination scroll moves every retained LayoutRes upward by the same
            // amount. Publish the centered shared target from that exact pre-layout result so the
            // promoted cover and the whole retained list never disagree about the return endpoint.
            oldEndpoint.copy(
                itemIndex = itemIndex,
                boundsInWindow = Rect(
                    left = oldEndpoint.boundsInWindow.left,
                    top = oldEndpoint.boundsInWindow.top - scrollDelta,
                    right = oldEndpoint.boundsInWindow.right,
                    bottom = oldEndpoint.boundsInWindow.bottom - scrollDelta,
                ),
                itemViewportBounds = Rect(
                    left = holderBounds.left,
                    top = holderBounds.top - scrollDelta,
                    right = holderBounds.right,
                    bottom = holderBounds.bottom - scrollDelta,
                ),
            )
        }
        onDispose {
            sharedCoverRegistry.detachPhysicalPromotionStarter(owner)
            sharedCoverRegistry.detachPhysicalPromotionLiveness(owner)
            sharedCoverRegistry.detachPhysicalPromotionCanceller(owner)
            sharedCoverRegistry.detachPhysicalReturnFinalizer(owner)
            sharedCoverRegistry.detachPhysicalEndpointListener(owner)
            sharedCoverRegistry.detachCollectionReturnTargetPreparer(owner)
        }
    }
    val presentationVisibleProvider = remember(
        presentationVisible,
        externalPresentationTransform,
    ) {
        {
            // Keep the physical holder population attached and synchronized while PLAYER owns
            // the screen.  Gating visibility on external alpha==0 used to hide every child and
            // reset the parent transform at the PLAYER endpoint; the first downward-return frame
            // then exposed a full-alpha list before the next runtime sync restored the current
            // scale/alpha.  reference player keeps the same holders alive at alpha=0 and only changes their
            // presentation properties, so return starts from the exact hidden endpoint.
            presentationVisible
        }
    }
    LaunchedEffect(externalPresentationTransform, physicalRuntime) {
        if (externalPresentationTransform == null) {
            physicalRuntime.settledNodeRuntime.invalidatePresentation()
            return@LaunchedEffect
        }
        snapshotFlow {
            externalPresentationTransform.scaleProvider().coerceIn(0.5f, 1.5f) to
                externalPresentationTransform.alphaProvider().coerceIn(0f, 1f)
        }
            .distinctUntilChanged()
            .collect {
                // MAIN <-> PLAYER is a parent-View affine, equivalent to reference player changing
                // VirtualList/View scaleX/scaleY/alpha. Do not turn each ratio tick into CONTENT:
                // that rebuilds ViewGroup presentation state and walks every resident holder.
                physicalRuntime.settledNodeRuntime.invalidateParentTransform()
            }
    }
    LaunchedEffect(requestedScene, currentScene, presentationScene, effectiveFrame.active) {
        AppLogger.i(
            "SceneHandoff",
            "HOST_FRAME requested=$requestedScene current=$currentScene presentation=$presentationScene" +
                " retained=${effectiveFrame.retainedScene} preparing=${effectiveFrame.preparingScene}" +
                " active=${effectiveFrame.active} swapped=${resolvedFrame.presentsRetainedRole}" +
                " runtime=${System.identityHashCode(physicalRuntime)}",
        )
    }
    val transitionStandbyScene = resolveReferenceLibraryStandbyScene(effectiveFrame, currentScene)
    @Composable
    fun VisibleController(scene: NavScene) {
        val sceneProviderIdentity = roleProviderIdentity(scene)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(20f)
                .graphicsLayer { alpha = if (controllerVisible) 1f else 0f },
        ) {
            CompositionLocalProvider(
                LocalReferenceLibraryProviderScene provides scene,
                LocalReferenceLibraryProviderIdentity provides sceneProviderIdentity,
                LocalReferenceLibraryBodyRender provides false,
                LocalReferenceLibraryProviderPublicationOnly provides false,
                LocalReferenceLibraryFullScenePreflight provides false,
                LocalReferenceRetainedVirtualListProvider provides null,
                LocalVirtualListPersistentRuntime provides physicalRuntime,
                LocalVirtualListPresentationHostExternal provides true,
                LocalSceneBackgroundFrozen provides effectiveFrame.active,
                LocalUiFrameAnimationActive provides pageFrameAnimationActive,
                LocalReferenceRetainedLibraryPopulation provides false,
                LocalRetainedSceneMotionActive provides effectiveFrame.active,
                LocalRetainedScenePreflightOwnership provides false,
                LocalRetainedSceneItemTransform provides null,
            ) {
                DisposableEffect(scene, sceneProviderIdentity) {
                    AppLogger.i(
                        "SceneHandoff",
                        "VISIBLE_CONTROLLER_ATTACH scene=$scene runtime=${System.identityHashCode(physicalRuntime)}",
                    )
                    onDispose {
                        AppLogger.i(
                            "SceneHandoff",
                            "VISIBLE_CONTROLLER_DETACH scene=$scene runtime=${System.identityHashCode(physicalRuntime)}",
                        )
                    }
                }
                currentContent(scene)
            }
        }
    }

    @Composable
    fun ProviderPreflight(scene: NavScene) {
        if (effectiveFrame.active || scene == presentationScene) return
        val expectedProviderIdentity = roleProviderIdentity(scene)
        val retainedProvider = registry[scene]?.takeIf {
            it.providerIdentity == expectedProviderIdentity
        }
        // Scene-level readiness alone is not enough: the permanent VirtualList host may have reused a
        // physical slot since this scene was last visible. Always revalidate the prepared endpoint;
        // the runtime's residency-aware prewarm key makes the steady-state path a strict no-op.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .zIndex(10f)
                // reference player endpoint preparation binds NEXT provider/LayoutRes/holder state without drawing a hidden
                // target frame. Keep this subtree composition/layout-only; alpha zero lets the
                // permanent holder host materialize NEXT while HWUI submits no target pixels.
                .graphicsLayer { alpha = 0f },
        ) {
            CompositionLocalProvider(
                LocalReferenceLibraryProviderScene provides scene,
                LocalReferenceLibraryProviderIdentity provides expectedProviderIdentity,
                LocalReferenceLibraryBodyRender provides false,
                // NEXT is a provider/LayoutRes record, not a second page controller. Every root
                // library page has a bounded publication-only branch which returns before chrome,
                // dialogs, Haze and the visual list body are composed.
                LocalReferenceLibraryProviderPublicationOnly provides true,
                LocalReferenceLibraryFullScenePreflight provides true,
                LocalReferenceRetainedVirtualListProvider provides null,
                LocalVirtualListPersistentRuntime provides physicalRuntime,
                LocalVirtualListPresentationHostExternal provides true,
                LocalSceneBackgroundFrozen provides true,
                LocalUiFrameAnimationActive provides false,
                LocalReferenceRetainedLibraryPopulation provides false,
                LocalRetainedSceneMotionActive provides false,
                LocalRetainedScenePreflightOwnership provides true,
                LocalRetainedSceneItemTransform provides null,
            ) {
                DisposableEffect(scene, expectedProviderIdentity) {
                    AppLogger.i(
                        "SceneHandoff",
                        "PROVIDER_PREFLIGHT_ATTACH scene=$scene runtime=${System.identityHashCode(physicalRuntime)}",
                    )
                    onDispose {
                        AppLogger.i(
                            "SceneHandoff",
                            "PROVIDER_PREFLIGHT_DETACH scene=$scene runtime=${System.identityHashCode(physicalRuntime)}",
                        )
                    }
                }
                // A scene which already owned the persistent list keeps its provider/LayoutState
                // record in the registry after the page controller is disposed. Reuse that record
                // directly; recreating the destination page tree just to publish the same provider
                // would reintroduce the dual-Compose-owner architecture this host is removing.
                // Only a never-before-bound destination gets one publication-only composition, and
                // this preflight subtree is gone before GenericPivot becomes active.
                if (retainedProvider == null) {
                    currentContent(scene)
                }

                // Revalidate/materialize NEXT while this provider-only subtree exists. Exact
                // endpoint + exact resident slot identity returns immediately inside the runtime,
                // so a warm return does not touch structure generation or child attachment.
                registry[scene]
                    ?.takeIf { it.providerIdentity == expectedProviderIdentity }
                    ?.let { preparedProvider ->
                    ComposeReferencePreparedCustomPopulation(
                        provider = preparedProvider,
                        runtime = physicalRuntime,
                        modifier = Modifier.fillMaxSize(),
                    )
                    ComposeReferencePreparedOrdinaryPopulation(
                        provider = preparedProvider,
                        runtime = physicalRuntime,
                        onMaterialized = {
                            registry.markPreparedProviderMaterialized(preparedProvider)
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    ComposeReferencePreparedPersistentHeader(
                        provider = preparedProvider,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }

    CompositionLocalProvider(
        LocalReferenceLibraryLayoutFrame provides effectiveFrame,
        LocalReferenceLibraryProviderRegistry provides registry,
        LocalReferenceLibraryProviderScene provides null,
        LocalReferenceLibraryProviderIdentity provides "",
        LocalReferenceLibraryBodyRender provides false,
        LocalReferenceLibraryProviderPublicationOnly provides false,
        LocalReferenceRetainedVirtualListProvider provides null,
        LocalVirtualListPersistentRuntime provides physicalRuntime,
        LocalVirtualListPresentationHostExternal provides true,
        LocalSceneBackgroundFrozen provides effectiveFrame.active,
        LocalReferenceRetainedLibraryPopulation provides false,
        LocalRetainedSceneMotionActive provides effectiveFrame.active,
        // GenericPivot is owned exactly once by the persistent VirtualList holder/LayoutRes layer.
        LocalRetainedSceneItemTransform provides null,
        ) {
        // One retained-layout VirtualList presentation owner for the complete library lifetime.
        // Pages only publish their viewport/provider/LayoutRes into the persistent runtime; this
        // Modifier.Node never moves between HOME/category parents.
        Box(
            modifier = modifier
                .fillMaxSize()
                .onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    physicalRuntime.settledNodeRuntime.updatePresentationHostBoundsInRoot(
                        left = bounds.left,
                        top = bounds.top,
                    )
                },
        ) {
            when (ReferenceLibraryPresentationHostBackend) {
                VirtualListPresentationHostBackend.COMPOSE_NODE -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(persistentListHazeSource)
                            .virtualListPresentationHost(
                                runtime = physicalRuntime.settledNodeRuntime,
                                externalTransform = externalPresentationTransform,
                                presentationVisibleProvider = presentationVisibleProvider,
                            ),
                    )
                }

                // Android View backend is mounted inside the one fixed ComposeVirtualList body so
                // its holder Views are descendants of the list's scrollable input owner. Keeping
                // it here as a sibling made the full-screen Compose scroll layer win hit testing and
                // prevented HOME ComposeView holders from receiving their own clicks.
                VirtualListPresentationHostBackend.ANDROID_VIEW -> Unit
            }

            // Exactly one real page/controller tree is mounted. NEXT exists only during the
            // preflight boundary below and is disposed before GenericPivot starts; from then on
            // CURRENT/NEXT are plain provider/LayoutRes records owned by the persistent list.
            VisibleController(presentationScene)
            transitionStandbyScene?.let { standby ->
                ProviderPreflight(standby)
            }

            // One fixed VirtualList body consumes the provider pair. Only these record references
            // change at commit; neither a second page tree nor a second physical body exists.
            val bodyProvider = registry[currentScene]?.takeIf { provider ->
                !currentScene.isDetail() || provider.providerIdentity == roleProviderIdentity(currentScene)
            }
            val retainedBodyProvider = if (effectiveFrame.active) {
                val retainedScene = effectiveFrame.retainedScene
                registry[retainedScene]?.takeIf { provider ->
                    retainedScene == null ||
                        !retainedScene.isDetail() ||
                        provider.providerIdentity == roleProviderIdentity(retainedScene)
                }
            } else {
                null
            }
            if (bodyProvider != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .zIndex(5f),
                ) {
                    ComposeReferenceLibraryProviderBody(
                        provider = bodyProvider,
                        retainedProvider = retainedBodyProvider,
                        physicalPresentationExternalTransform = externalPresentationTransform,
                        physicalPresentationVisibleProvider = presentationVisibleProvider,
                        onOuterPopulationPublished = {
                            registry.markOuterPopulationBound(bodyProvider, retainedBodyProvider)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (ReferenceLibraryPresentationHostBackend == VirtualListPresentationHostBackend.ANDROID_VIEW) {
                                    persistentListHazeSource
                                } else {
                                    Modifier
                                }
                            ),
                    )
                }
            }

            if (!controllerVisible) {
                // reference player does not destroy the attached HOME/category Views merely because a
                // Settings/other scene covers VirtualList. Ordinary Raw holders already survive in
                // settledNodeRuntime; heterogeneous HOME/category holders are Compose-backed and
                // previously disappeared with the page controller, then re-entered at scroll=0 for
                // one layout before the remembered viewport was restored. Keep those exact movable
                // physical holders parked in the same owner instead. Alpha zero is intentional here:
                // these holders have already had a visible/display-list pass, so RenderThread may
                // prune drawing while their composition/identity/layout record remains retained.
                registry[presentationScene]?.takeIf { it.customProvider != null }?.let { parkedProvider ->
                    CompositionLocalProvider(LocalUiFrameAnimationActive provides false) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer { alpha = 0f },
                    ) {
                        ComposeReferencePreparedCustomPopulation(
                            provider = parkedProvider,
                            runtime = physicalRuntime,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    }
                }
            }
        }
    }
}
