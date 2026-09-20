package com.rawsmusic.core.ui.scene

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.util.fastFirstOrNull
import androidx.compose.ui.util.lerp
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.perf.TransitionPerfStage
import com.rawsmusic.core.ui.perf.TransitionPerfTrace
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.BitmapImage
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.PlaybackArtworkPerfTrace
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collect
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.cos
import kotlin.math.roundToInt

private const val NORMAL_ANIM_MS = 320
internal const val VIRTUAL_LIST_COMMIT_MS = 250
private const val VIRTUAL_LIST_CANCEL_MS = 500
private const val VIRTUAL_LIST_INDEXER_FADE_MS = 250
private const val VIRTUAL_LIST_COMMIT_PROGRESS = 0.3f
private const val VIRTUAL_LIST_VELOCITY_DP_PER_S = 500f
private const val SHARED_PAIR_WAIT_FRAMES = 6
// Destination holder/RenderNode warming belongs to the settled idle owner. Navigation itself only
// needs one frame boundary to publish the destination provider/callback binding before LayoutRes
// roles switch; adding another cold-preparation frame puts work back onto the click hot path.
private const val SCENE_PREPARE_FRAMES = 1
private const val VIRTUALLIST_PROVIDER_BIND_FRAMES = 1
private const val SETTINGS_FRAGMENT_ANIM_MS = 300
private const val EDGE_DETECT_WIDTH_DP = 24
private const val OVER_DRAG_UNIT = 0.05f
private const val VIRTUAL_LIST_SCALE_MIN = 0.5f
private const val VIRTUAL_LIST_SCALE_MAX = 1.5f

private val Decelerate2 = CubicBezierEasing(0f, 0f, 0.2f, 1f)
private val FragmentFastOutExtraSlowIn = FragmentSceneEasing
private val transitionTween = tween<Float>(NORMAL_ANIM_MS, easing = Decelerate2)
private val virtualListTransitionTween = tween<Float>(
    durationMillis = VIRTUAL_LIST_COMMIT_MS,
    easing = VirtualListAccelerateDecelerate
)
private val settingsFragmentTween = tween<Float>(SETTINGS_FRAGMENT_ANIM_MS, easing = FragmentFastOutExtraSlowIn)

internal enum class PageMotion {
    Generic,
    HomeCategoryForward,
    HomeCategoryBack,
    FolderSharedForward,
    FolderSharedBack,
    SettingsForward,
    SettingsBack,
}

/**
 * A frame of the page transition shared with persistent chrome/background layers.
 *
 * Keep the background renderer alive while pages move above it. Exposing the same
 * normalized progress prevents the background from being committed one frame after the page.
 */
data class SceneTransitionFrame(
    val active: Boolean,
    val progress: Float,
    val fromScene: NavScene,
    val toScene: NavScene,
    val isBack: Boolean,
    val progressProvider: (() -> Float)? = null,
) {
    companion object {
        fun idle(scene: NavScene): SceneTransitionFrame = SceneTransitionFrame(
            active = false,
            progress = 1f,
            fromScene = scene,
            toScene = scene,
            isBack = false,
        )
    }
}

/**
 * Stable holder for the frame shared with persistent chrome/background layers.
 *
 * The transition animation changes only [progress]. Keeping the holder identity stable lets
 * Compose invalidate the readers of that field instead of rebuilding AppMainLayout on every
 * display frame. This is the Compose representation of a long-lived renderer state.
 */
@Stable
class SceneTransitionFrameState(initialScene: NavScene) {
    private data class Meta(
        val active: Boolean,
        val fromScene: NavScene,
        val toScene: NavScene,
        val isBack: Boolean,
    )

    // Route ownership changes only at transition boundaries; the scalar changes every display
    // frame. Keeping them in one snapshot object made every progress tick invalidate compositions
    // that only needed from/to/active. The scalar is intentionally a separate draw/layer clock.
    private var meta by mutableStateOf(
        Meta(
            active = false,
            fromScene = initialScene,
            toScene = initialScene,
            isBack = false,
        )
    )
    private var progressState by mutableFloatStateOf(1f)
    // retained-view implementation advances one retained scene clock directly into View properties. Keep Raw's
    // transition scalar single-owned as well: while a transition is active, draw/layer readers
    // sample the original Animatable instead of receiving a second per-vsync Snapshot write.
    // Provider identity only changes at scene ownership boundaries.
    private var progressProvider by mutableStateOf<(() -> Float)?>(null)

    val active: Boolean get() = meta.active
    val progress: Float get() = progressProvider?.invoke()?.coerceIn(0f, 1f) ?: progressState
    val fromScene: NavScene get() = meta.fromScene
    val toScene: NavScene get() = meta.toScene
    val isBack: Boolean get() = meta.isBack

    fun update(frame: SceneTransitionFrame) {
        val nextMeta = Meta(
            active = frame.active,
            fromScene = frame.fromScene,
            toScene = frame.toScene,
            isBack = frame.isBack,
        )
        if (meta != nextMeta) meta = nextMeta
        if (progressProvider !== frame.progressProvider) progressProvider = frame.progressProvider
        if (progressState != frame.progress) progressState = frame.progress
    }
}

private val settingsScenes = setOf(
    NavScene.SETTINGS,
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.FOLDER_DETAIL,
    NavScene.ALBUMS,
    NavScene.ALBUM_DETAIL,
    NavScene.ARTISTS,
    NavScene.ARTIST_DETAIL,
    NavScene.PLAYLISTS,
    NavScene.PLAYLIST_DETAIL,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.WEBDAV,
    NavScene.ABOUT,
    NavScene.SONG_STATS,
    NavScene.LOG_VIEWER,
    NavScene.ANALYTICS,
    NavScene.APPEARANCE,
    NavScene.PERSONALIZATION_SETTINGS,
    NavScene.AUDIO_SETTINGS,
    NavScene.ALBUM_ART_SETTINGS,
    NavScene.BASS_TREBLE_BOOST,
    NavScene.COMPRESSOR,
    NavScene.GLOBAL_FONT_SETTINGS,
    NavScene.LYRIC_FONT_SETTINGS,
    NavScene.LYRIC_MANAGEMENT,
    NavScene.PANORAMIC_360,
    NavScene.PEQ,
    NavScene.AUDIO_EFFECTS,
    NavScene.PLAYER_INTERFACE,
    NavScene.SPATIAL_SOUND,
    NavScene.STATUS_BAR_LYRIC,
    NavScene.SURROUND_360,
    NavScene.USB_DAC_SETTINGS,
    NavScene.WEBDAV_BACKUP,
)

// Persistent VirtualList ownership is broader than HOME's first-level navigation graph: collection
// detail scenes also live in the same physical list so Header<->Item can swap LayoutRes in place.
// Do not use that broad owner set to classify HOME/category motion, otherwise a detail scene is
// accidentally treated as a HOME PivotTransition endpoint and receives the wrong retained window /
// pivot contract.
private val homeCategoryTransitionScenes = setOf(
    NavScene.SONG_STATS,
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.GENRE,
    NavScene.YEAR,
    NavScene.COMPOSER,
)

private fun shouldAnimate(from: NavScene, to: NavScene): Boolean {
    return from != to
}

internal fun isHomeCategorySceneTransition(from: NavScene, to: NavScene): Boolean =
    (from == NavScene.HOME && to in homeCategoryTransitionScenes) ||
        (to == NavScene.HOME && from in homeCategoryTransitionScenes)

private fun usesHomeCategoryMotion(from: NavScene, to: NavScene): Boolean =
    isHomeCategorySceneTransition(from, to)

private fun usesSettingsFragmentMotion(from: NavScene, to: NavScene): Boolean =
    from in settingsScenes && to in settingsScenes

private fun usesSharedCoverMotion(from: NavScene, to: NavScene): Boolean {
    return (from == NavScene.FOLDERS && to == NavScene.FOLDER_DETAIL) ||
        (from == NavScene.FOLDER_DETAIL && to == NavScene.FOLDERS) ||
        (from == NavScene.ALBUMS && to == NavScene.ALBUM_DETAIL) ||
        (from == NavScene.ALBUM_DETAIL && to == NavScene.ALBUMS) ||
        (from == NavScene.ARTISTS && to == NavScene.ARTIST_DETAIL) ||
        (from == NavScene.ARTIST_DETAIL && to == NavScene.ARTISTS) ||
        (from == NavScene.GENRE && to == NavScene.GENRE_DETAIL) ||
        (from == NavScene.GENRE_DETAIL && to == NavScene.GENRE) ||
        (from == NavScene.YEAR && to == NavScene.YEAR_DETAIL) ||
        (from == NavScene.YEAR_DETAIL && to == NavScene.YEAR) ||
        (from == NavScene.COMPOSER && to == NavScene.COMPOSER_DETAIL) ||
        (from == NavScene.COMPOSER_DETAIL && to == NavScene.COMPOSER)
}

private fun allowsContentBackDrag(from: NavScene, to: NavScene): Boolean {
    if (from in settingsScenes || to in settingsScenes) return false
    // SEARCH owns a horizontally scrollable filter rail. SceneTransitionHost observes at Initial
    // pass, so allowing content-wide back there races the LazyRow before it can establish scroll
    // ownership. Keep SEARCH on the same policy as SOURCE_IMPORT: children own interior drags and
    // scene navigation remains available from the system-style edge only.
    if (from == NavScene.SOURCE_IMPORT || from == NavScene.SEARCH) return false
    return from != to
}

private val horizontalCategoryScenes = listOf(
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.PLAYLISTS,
    NavScene.QUEUE,
    NavScene.RECENTLY_ADDED,
    NavScene.GENRE,
    NavScene.YEAR,
    NavScene.COMPOSER,
)

private val alphabetIndexedScenes = setOf(
    NavScene.SONGS,
    NavScene.FOLDERS,
    NavScene.FOLDER_HIERARCHY,
    NavScene.ALBUMS,
    NavScene.ARTISTS,
    NavScene.GENRE,
    NavScene.YEAR,
    NavScene.COMPOSER,
)

private fun usesAlphabetIndexer(scene: NavScene): Boolean = scene in alphabetIndexedScenes

/**
 * Artist detail -> biography is a provider-to-provider navigation inside the same physical
 * Reference VirtualList. It deliberately uses the ordinary PivotTransition LayoutRes curve rather
 * than a scene-root dialog/page animation: the song/header population leaves as CURRENT while the
 * biography holder population arrives as NEXT, and back swaps those roles in reverse.
 */
private fun usesArtistBiographyMotion(from: NavScene, to: NavScene): Boolean =
    (from == NavScene.ARTIST_DETAIL && to == NavScene.ARTIST_BIOGRAPHY) ||
        (from == NavScene.ARTIST_BIOGRAPHY && to == NavScene.ARTIST_DETAIL)

private fun usesRetainedVirtualListPivot(from: NavScene, to: NavScene): Boolean {
    // Every root library category, including PLAYLISTS/QUEUE/RECENTLY_ADDED, publishes into the
    // same persistent ReferenceLibraryVirtualListHost. GenericPivot therefore always swaps
    // CURRENT/RETAINED provider LayoutRes instead of promoting a second full-scene root.
    if (!isReferenceLibraryScene(from) || !isReferenceLibraryScene(to)) return false
    return isHomeCategorySceneTransition(from, to) ||
        (from == NavScene.SONG_STATS && to == NavScene.LIBRARY_ANALYSIS_DETAIL) ||
        (from == NavScene.LIBRARY_ANALYSIS_DETAIL && to == NavScene.SONG_STATS) ||
        usesArtistBiographyMotion(from, to) ||
        usesSharedCoverMotion(from, to) ||
        (from in horizontalCategoryScenes && to in horizontalCategoryScenes && from != to)
}

private fun adjacentCategory(scene: NavScene, direction: Float): NavScene? {
    val index = horizontalCategoryScenes.indexOf(scene)
    if (index < 0) return null
    val targetIndex = if (direction < 0f) index + 1 else index - 1
    return horizontalCategoryScenes.getOrNull(targetIndex)
}

private fun dampProgress(progress: Float): Float {
    if (progress in 0f..1f) return progress
    val over = if (progress < 0f) -progress else 1f - progress
    val damped = ln(abs(over) / OVER_DRAG_UNIT + 1f) * OVER_DRAG_UNIT
    return if (progress < 0f) -damped else 1f + damped
}

private suspend fun awaitSharedLayouts(
    coverRegistry: SharedCoverRegistry,
    fromScene: NavScene,
    toScene: NavScene,
    transitionKey: String,
    allowRememberedTarget: Boolean = false
): Boolean {
    if (allowRememberedTarget) {
        // retained-view implementation HeaderToItem first repositions the destination VirtualList around the clicked item,
        // then captures that new LayoutRes as the reverse target. Raw's persistent provider/state
        // can perform the same pre-layout synchronously before the remembered pair is resolved.
        coverRegistry.prepareCollectionReturnTarget(
            fromSceneId = fromScene.name,
            toSceneId = toScene.name,
        )
        val retainedPairs = coverRegistry.findPairs(
            fromSceneId = fromScene.name,
            toSceneId = toScene.name,
            allowRememberedTarget = true,
            requireLiveTarget = false,
        )
        if (retainedPairs.isNotEmpty()) {
            val (from, to) = retainedPairs.first()
            coverRegistry.prepareTransition(transitionKey, from, to)
            return true
        }
    }
    val pair = awaitCollectionSharedPair(candidate = {
        coverRegistry.findPairs(
            fromSceneId = fromScene.name,
            toSceneId = toScene.name,
            allowRememberedTarget = allowRememberedTarget,
            requireLiveTarget = allowRememberedTarget,
        ).firstOrNull()
    }) ?: return false
    coverRegistry.prepareTransition(transitionKey, pair.first, pair.second)
    return true
}

internal fun acquirePhysicalSharedActor(
    coverRegistry: SharedCoverRegistry,
    transitionKey: String,
) : Boolean {
    val physicalPair = coverRegistry.getPreparedPair(transitionKey)
    if (physicalPair != null && physicalPair.first.elementId.isNotBlank()) {
        // retained-view implementation VirtualListItemToHeaderTransition.B() first commits NEXT provider/LayoutRes in
        // endpoint preparation, then holder migration migrates the concrete source View. [awaitSharedLayouts] has just
        // established the stable pair, so this is the first legal point for Raw to detach/promote
        // the source holder. HeaderToItem is different: its concrete View is already the promoted
        // ItemToHeader owner living in the detail header, so it must be reused even though the
        // header endpoint itself no longer has a source physicalSlotId.
        return coverRegistry.promotePreparedPhysicalSource(transitionKey)
    }
    return false
}

@Composable
internal fun SceneTransitionHost(
    state: NavigationState,
    modifier: Modifier = Modifier,
    horizontalGestureExclusionBounds: Rect? = null,
    gesturesEnabled: Boolean = true,
    providerBackGesture: LibraryContentBackGesture? = null,
    onTransitionActiveChanged: (Boolean) -> Unit = {},
    onTransitionFrameChanged: (SceneTransitionFrame) -> Unit = {},
    onCollectionDetailOffscreenReturnCommitted: (NavScene) -> Unit = {},
    // Root-level owner for HOME/category VirtualList scenes. This slot is deliberately outside
    // the per-scene movable content so the VirtualList presentation host is never moved or replaced
    // when the route changes; only its provider/LayoutRes roles change.
    persistentLibraryContent: @Composable (
        NavScene,
        Boolean,
        Boolean,
        @Composable (NavScene) -> Unit,
    ) -> Unit = { _, _, _, _ -> },
    content: @Composable (NavScene) -> Unit
) {
    val scope = rememberCoroutineScope()
    // Back-release is an edge-triggered transaction, not level state. Keep the last observed token
    // consumed by this mounted host so a later forward navigation (which changes
    // transitionRequestId) cannot replay an old release and overwrite the new pageMotion/fromScene.
    // Initializing from the current token also prevents a remounted host from re-consuming an
    // already-finished release.
    var consumedDragBackReleaseToken by remember {
        mutableIntStateOf(state.dragBackReleaseToken)
    }
    val transitionProbeView = LocalView.current
    val transitionRefreshRateHz = transitionProbeView.display?.refreshRate ?: 60f
    DisposableEffect(transitionProbeView) {
        TransitionPerfTrace.attach(transitionProbeView)
        onDispose {
            TransitionPerfTrace.stop("host_dispose")
            TransitionPerfTrace.detach()
        }
    }
    val animProgress = remember { Animatable(0f) }
    var displayedScene by remember { mutableStateOf(state.currentScene) }
    var parkedLibraryScene by remember {
        mutableStateOf(state.currentScene.takeIf(::isPersistentHomeCategoryScene))
    }
    var fromScene by remember { mutableStateOf(state.currentScene) }
    var retainedScene by remember { mutableStateOf<NavScene?>(null) }
    // Detail scenes are provider identities, not just route classes. retained-view implementation may reuse the same
    // header/list scene object, but it rebinds the concrete album/artist item before transition.
    // Keep the argument which owned a retained detail so a different item cannot reuse stale
    // header content for the first frame of the next ItemToHeader transition.
    var retainedDetailArgument by remember { mutableStateOf<String?>(null) }
    var preparingScene by remember { mutableStateOf<NavScene?>(null) }
    var isAnimating by remember { mutableStateOf(false) }
    var isGestureActive by remember { mutableStateOf(false) }
    var isBackTransition by remember { mutableStateOf(false) }
    var usesDirectionalBackPivot by remember { mutableStateOf(false) }
    var pageMotion by remember { mutableStateOf(PageMotion.Generic) }
    // retained-view implementation keeps the Item/Header transition family even when the concrete shared AA holder is
    // unavailable. Shared-artwork ownership therefore has to be tracked separately from the scene
    // motion; otherwise an offscreen header incorrectly changes the transition curve itself.
    var sharedCoverActorEligible by remember { mutableStateOf(false) }
    var hostPositionInRoot by remember { mutableStateOf(Offset.Zero) }
    var hostPositionInWindow by remember { mutableStateOf(Offset.Zero) }
    val alphabetIndexAlpha = remember { Animatable(1f) }
    val topMenuAlpha = remember { Animatable(1f) }
    var screenWidthPx by remember { mutableFloatStateOf(0f) }
    var screenHeightPx by remember { mutableFloatStateOf(0f) }
    val prevSceneRef = remember { mutableStateOf(state.currentScene) }
    val sharedCoverRegistry = remember { SharedCoverRegistry() }
    LaunchedEffect(displayedScene) {
        if (isPersistentHomeCategoryScene(displayedScene)) {
            parkedLibraryScene = displayedScene
        }
    }

    // 共享元素专用 from/to（独立于渲染用的 fromScene/displayedScene）
    var sharedFromScene by remember { mutableStateOf(state.currentScene) }
    var sharedToScene by remember { mutableStateOf(state.currentScene) }
    // ComposeNavHost supplies a new route lambda when repository/player state changes. Keep the
    // cached movable slots, but make their body resolve the current lambda. Without this bridge a
    // retained VirtualList slot can keep the first data snapshot it saw, which then forces a later
    // artwork/text rebuild during a transition and looks like a one-frame flash.
    val currentSceneContent by rememberUpdatedState(content)
    var frozenSceneContent by remember {
        mutableStateOf<(@Composable (NavScene) -> Unit)?>(null)
    }
    val movableSceneContent = remember { mutableMapOf<NavScene, @Composable () -> Unit>() }
    // HOME and top-level category providers belong to one persistent VirtualList owner in Reference.
    // Keep one movable composition for that owner across provider switches; the library host inside
    // ComposeNavHost receives the requested scene as a provider parameter and retains old/current
    // layout states itself.
    val movableLibraryContent = remember {
        movableContentOf { scene: NavScene ->
            val renderer = frozenSceneContent ?: currentSceneContent
            renderer(scene)
        }
    }

    @Composable
    fun SceneContent(scene: NavScene) {
        if (isPersistentHomeCategoryScene(scene)) {
            movableLibraryContent(scene)
            return
        }
        val movable = movableSceneContent.getOrPut(scene) {
            movableContentOf {
                val renderer = frozenSceneContent ?: currentSceneContent
                renderer(scene)
            }
        }
        movable()
    }

    LaunchedEffect(state.currentScene, state.transitionRequestId) {
        val newScene = state.currentScene
        val requestId = state.transitionRequestId
        // displayedScene is the last scene actually owned by the presentation host. prevSceneRef
        // can already point at a prepared-but-never-presented route when a second click supersedes
        // the first request, which made the next transition start from an invisible page.
        val oldScene = displayedScene
        if (newScene == oldScene) {
            prevSceneRef.value = newScene
            // switchToSilent()/restorePersistentState() can cancel a route coroutine without
            // changing the visual scene. Release every local owner in that case, otherwise the
            // next composition keeps rendering the stale endpoint/frozen page.
            if (!state.isTransitioning && !state.isAnimatingBack && !state.isDraggingBack) {
                isAnimating = false
                isGestureActive = false
                preparingScene = null
                retainedScene = null
                frozenSceneContent = null
                isBackTransition = false
                usesDirectionalBackPivot = false
                animProgress.snapTo(0f)
                TransitionPerfTrace.stop("forward_same_scene_reset")
            }
            return@LaunchedEffect
        }
        try {
        prevSceneRef.value = newScene

        if (isGestureActive || state.isDraggingBack) {
            isGestureActive = false
            animProgress.snapTo(0f)
        }

        if (!shouldAnimate(oldScene, newScene)) {
            displayedScene = newScene
            retainedScene = null
            preparingScene = null
            frozenSceneContent = null
            return@LaunchedEffect
        }

        fromScene = oldScene
        isBackTransition = false
        usesDirectionalBackPivot = false
        // index scroller.K(mode) animates only when the indexer mode really changes. In
        // particular indexed -> indexed keeps the same index scroller and does not replay a
        // fade. Because Raw composes the destination controller before PivotTransition starts, gate a
        // newly-created indexed target at zero until the LayoutRes commit; otherwise leave the
        // existing mode untouched.
        val revealAlphabetIndexerAfterCommit =
            !usesAlphabetIndexer(oldScene) && usesAlphabetIndexer(newScene)
        alphabetIndexAlpha.snapTo(if (revealAlphabetIndexerAfterCommit) 0f else 1f)
        topMenuAlpha.snapTo(1f)
        sharedFromScene = oldScene
        sharedToScene = newScene
        val sharedForwardRoute = usesSharedCoverMotion(oldScene, newScene)
        // retained-view implementation ItemToHeader does not hand shared pixels to the transition merely because this
        // route family is active. endpoint preparation first binds NEXT, holder migration then migrates the concrete
        // source View, and only after that O(progress) exposes the motion clock. Keep Raw's shared
        // actor disabled until the physical promotion has actually succeeded.
        sharedCoverActorEligible = false
        pageMotion = when {
            sharedForwardRoute -> PageMotion.FolderSharedForward
            usesHomeCategoryMotion(oldScene, newScene) -> PageMotion.HomeCategoryForward
            usesArtistBiographyMotion(oldScene, newScene) -> PageMotion.HomeCategoryForward
            state.navigationMotionHint == NavigationMotionHint.BOTTOM_NAVIGATION -> PageMotion.Generic
            usesSettingsFragmentMotion(oldScene, newScene) -> PageMotion.SettingsForward
            else -> PageMotion.Generic
        }
        frozenSceneContent = if (usesRetainedVirtualListPivot(oldScene, newScene)) {
            currentSceneContent
        } else {
            null
        }
        TransitionPerfTrace.start(
            newLabel = "forward ${oldScene.name}->${newScene.name} motion=$pageMotion",
            refreshRateHz = transitionRefreshRateHz,
        )
        TransitionPerfTrace.mark(
            "scene_begin",
            "kind=forward artwork=${PlaybackArtworkPerfTrace.overlapSummary()} retainedPivot=${frozenSceneContent != null} " +
                "owner=${if (frozenSceneContent != null) "persistent_layout" else "scene_root"} ${BitmapProvider.transitionDiagnosticsSummary()}",
        )
        AppLogger.i("SceneHandoff", "SCENE_BEGIN kind=forward from=$oldScene to=$newScene motion=$pageMotion retained=${frozenSceneContent != null}")

        // Register the destination provider/endpoints at final geometry before motion begins.
        // Root library categories all use the persistent owner, so a retained destination can be
        // reused directly instead of going through a second scene-root preparation lifecycle.
        val retainedVirtualListEntry = usesRetainedVirtualListPivot(oldScene, newScene)
        val requiresEntryPreflight =
            retainedScene != newScene ||
                (newScene.isDetail() && retainedDetailArgument != state.currentArgument)
        if (requiresEntryPreflight) {
            val prepareStartedNs = System.nanoTime()
            preparingScene = newScene
            val prepareFrames = if (retainedVirtualListEntry) {
                VIRTUALLIST_PROVIDER_BIND_FRAMES
            } else {
                SCENE_PREPARE_FRAMES
            }
            repeat(prepareFrames) {
                withFrameNanos { }
            }
            // retained-view implementation's endpoint preparation is synchronous. It binds NEXT, then immediately starts the
            // transition transaction if the source/target pair is available; it does not park the
            // click hot path behind an async prewarm/materialization barrier. The one frame above is
            // Raw's Compose publication boundary; any colder holder work must arrive through the
            // normal provider path rather than delaying O(progress).
            TransitionPerfTrace.recordDuration(
                TransitionPerfStage.SCENE_PREPARE,
                System.nanoTime() - prepareStartedNs,
            )
        }
        // Match retained-view implementation's exact transaction boundary: NEXT provider/LayoutRes is prepared while
        // CURRENT still owns the visible frame. Put the scalar at the exact source endpoint before
        // migrating the concrete View so the shared progress provider cannot observe a stale
        // settled value and momentarily place that View at the target header.
        animProgress.snapTo(1f)
        if (pageMotion == PageMotion.FolderSharedForward) {
            val sharedKey = "${oldScene.name}->${newScene.name}"
            val prepared = awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                fromScene = oldScene,
                toScene = newScene,
                transitionKey = sharedKey
            )
            if (!prepared) {
                sharedCoverActorEligible = false
                AppLogger.w(
                    "SceneHandoff",
                    "ITEM_TO_HEADER_TARGET_NOT_READY from=$oldScene to=$newScene fallback=scene_only",
                )
            } else {
                sharedCoverActorEligible = acquirePhysicalSharedActor(sharedCoverRegistry, sharedKey)
            }
        }

        // holder migration has now either migrated the same physical holder or deliberately fallen back to
        // scene-only motion. Publish displayedScene/isAnimating only after that ownership decision.
        Snapshot.withMutableSnapshot {
            displayedScene = newScene
            isAnimating = true
        }
        if (pageMotion != PageMotion.FolderSharedForward &&
            !usesRetainedVirtualListPivot(oldScene, newScene)
        ) {
            // A retained HOME/category target already spent its one required hidden measurement
            // frame above (or was already retained). Do not add a second idle vsync before motion.
            withFrameNanos { }
        }
        val animateStartedNs = System.nanoTime()
        TransitionPerfTrace.markAnimateStart("kind=forward motion=$pageMotion")
        animProgress.animateTo(
            0f,
            when (pageMotion) {
                PageMotion.FolderSharedForward,
                PageMotion.HomeCategoryForward -> virtualListTransitionTween
                PageMotion.SettingsForward -> settingsFragmentTween
                else -> transitionTween
            }
        )
        TransitionPerfTrace.recordDuration(
            TransitionPerfStage.SCENE_ANIMATE,
            System.nanoTime() - animateStartedNs,
        )
        AppLogger.i("SceneHandoff", "ANIMATION_ENDPOINT kind=forward from=$oldScene to=$newScene progress=${animProgress.value}")
        // retained-view implementation commits CURRENT/NEXT LayoutRes roles synchronously at the terminal scalar. The
        // Raw library host now keeps the same physical holder owner through that role commit, so an
        // extra endpoint frame/controller handoff would be a second owner that retained-view implementation does not
        // have. Keep the exact final LayoutRes on the attached Views and release transition state
        // immediately below.
        if (pageMotion == PageMotion.FolderSharedForward) {
            // Drop the page transform while the prepared shared owner is still covering the
            // target. The next frame can then hand the pixels back without exposing a stale
            // layout or running the cover through two coordinate systems.
            fromScene = displayedScene
        }
        // The same physical holder owner already contains the exact terminal LayoutRes. Releasing
        // transition state only commits which LayoutRes role is current.
        retainedScene = oldScene
        retainedDetailArgument = if (oldScene.isDetail()) retainedDetailArgument else null
        preparingScene = null
        isAnimating = false
        frozenSceneContent = null
        isBackTransition = false
        usesDirectionalBackPivot = false
        state.completeTransitionAt(displayedScene)
        if (!displayedScene.isDetail()) {
            val retiredSharedOwner = sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
            AppLogger.i(
                "SceneHandoff",
                "SHARED_OWNER_BARRIER kind=forward scene=$displayedScene retired=$retiredSharedOwner",
            )
        }
        TransitionPerfTrace.stop("forward_complete")
        AppLogger.i("SceneHandoff", "SCENE_END kind=forward displayed=$displayedScene")
        if (revealAlphabetIndexerAfterCommit) {
            scope.launch {
                withFrameNanos { }
                alphabetIndexAlpha.animateTo(
                    1f,
                    tween(durationMillis = VIRTUAL_LIST_INDEXER_FADE_MS),
                )
            }
        }
        } finally {
            // A new route cancels this coroutine while its old page may still be marked as
            // animating. Only the owner of the current request may clear the transition fields;
            // otherwise the next route briefly renders an idle host (the source of the no-motion
            // and two-page overlap seen when switching library tabs quickly).
            if (state.transitionRequestId == requestId && state.currentScene == newScene) {
                isAnimating = false
                isGestureActive = false
                preparingScene = null
                frozenSceneContent = null
                isBackTransition = false
                usesDirectionalBackPivot = false
                state.completeTransitionAt(newScene)
                if (!newScene.isDetail()) {
                    sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
                }
                TransitionPerfTrace.stop("forward_cancelled")
            }
        }
    }

    LaunchedEffect(state.isAnimatingBack, state.transitionRequestId) {
        val requestId = state.transitionRequestId
        if (!state.isAnimatingBack) return@LaunchedEffect
        val targetScene = state.getPreviousScene() ?: run {
            state.completeAnimatingBack()
            return@LaunchedEffect
        }
        val retainedVirtualListBack = usesRetainedVirtualListPivot(state.currentScene, targetScene)
        try {
        // Back navigation needs the same explicit destination-preparation owner as forward
        // Header->Item. Previously the target existed only through the derived
        // preflightBackPrepareScene while this effect was waiting for shared geometry. During that
        // window `sharedActive` was still false, so a recomposition could clear the frozen shared
        // pair before FolderSharedBack acquired it. Keep the target preparation transaction alive
        // until the CURRENT/RETAINED pair becomes active, matching VirtualList's synchronous bind of
        // the destination LayoutRes before starting the reverse transition.
        if (retainedVirtualListBack) {
            preparingScene = targetScene
        }

        fromScene = targetScene
        isBackTransition = true
        usesDirectionalBackPivot = false
        sharedFromScene = state.currentScene
        sharedToScene = targetScene
        val sharedReturnRoute = usesSharedCoverMotion(state.currentScene, targetScene)
        val sharedReturnSourceReady = sharedReturnRoute &&
            sharedCoverRegistry.captureLiveCollectionReturnSource(
                fromSceneId = state.currentScene.name,
                toSceneId = targetScene.name,
            )
        val resetDetailToTopAfterCommit = sharedReturnRoute && !sharedReturnSourceReady
        if (sharedReturnRoute && !sharedReturnSourceReady) {
            // retained-view implementation HeaderToItem does not keep the previously promoted ItemToHeader View as a
            // shared owner when the detail/header source is offscreen. Without a concrete source
            // View there is no shared return; the destination category holder must draw its normal
            // cover for the entire back motion.
            sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
        }
        // HeaderToItem can keep its scene-motion family even when no concrete shared actor exists
        // (for example an offscreen header). retained-view implementation only hands pixels to the transition after the
        // physical View has been migrated, so a captured/live source is not itself ownership.
        sharedCoverActorEligible = false
        pageMotion = when {
            // HeaderToItem remains the transition family when the concrete AA/header holder is
            // offscreen. retained-view implementation simply omits the promoted shared View and keeps the same retained
            // scene geometry/AccelerateDecelerate timeline.
            sharedReturnRoute -> PageMotion.FolderSharedBack
            usesHomeCategoryMotion(state.currentScene, targetScene) -> PageMotion.HomeCategoryBack
            usesArtistBiographyMotion(state.currentScene, targetScene) -> PageMotion.HomeCategoryBack
            state.backNavigationMotionHint == NavigationMotionHint.BOTTOM_NAVIGATION -> PageMotion.Generic
            usesSettingsFragmentMotion(state.currentScene, targetScene) -> PageMotion.SettingsBack
            else -> PageMotion.Generic
        }
        val revealAlphabetIndexerAfterCommit =
            !usesAlphabetIndexer(state.currentScene) && usesAlphabetIndexer(targetScene)
        alphabetIndexAlpha.snapTo(if (revealAlphabetIndexerAfterCommit) 0f else 1f)
        frozenSceneContent = if (retainedVirtualListBack) {
            currentSceneContent
        } else {
            null
        }
        TransitionPerfTrace.start(
            newLabel = "back ${state.currentScene.name}->${targetScene.name} motion=$pageMotion",
            refreshRateHz = transitionRefreshRateHz,
        )
        TransitionPerfTrace.mark(
            "scene_begin",
            "kind=back artwork=${PlaybackArtworkPerfTrace.overlapSummary()} retainedPivot=${frozenSceneContent != null} " +
                "owner=${if (frozenSceneContent != null) "persistent_layout" else "scene_root"} ${BitmapProvider.transitionDiagnosticsSummary()}",
        )
        AppLogger.i("SceneHandoff", "SCENE_BEGIN kind=back from=${state.currentScene} to=$targetScene motion=$pageMotion retained=${frozenSceneContent != null}")
        // Resolve the retained source/target holders before exposing the transition layer. If the
        // detail list has moved, showing page transforms while the shared pair is still being
        // measured produces a full-screen preparation flash.
        val prepareStartedNs = System.nanoTime()
        if (pageMotion == PageMotion.FolderSharedBack && sharedReturnSourceReady) {
            val sharedKey = "${state.currentScene.name}->${targetScene.name}"
            val prepared = awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                fromScene = state.currentScene,
                toScene = targetScene,
                transitionKey = sharedKey,
                allowRememberedTarget = true
            )
            if (!prepared) {
                sharedCoverActorEligible = false
            } else {
                sharedCoverActorEligible = acquirePhysicalSharedActor(sharedCoverRegistry, sharedKey)
            }
        }
        TransitionPerfTrace.recordDuration(
            TransitionPerfStage.SCENE_PREPARE,
            System.nanoTime() - prepareStartedNs,
        )
        animProgress.snapTo(0f)
        isAnimating = true
        // Do not insert an extra vsync/materialization barrier here. retained-view implementation's HeaderToItem starts
        // the progress clock immediately after the target LayoutRes is captured/repositioned.
        val animateStartedNs = System.nanoTime()
        TransitionPerfTrace.markAnimateStart("kind=back motion=$pageMotion")
        animProgress.animateTo(
            1f,
            when (pageMotion) {
                PageMotion.FolderSharedBack,
                PageMotion.HomeCategoryBack -> virtualListTransitionTween
                PageMotion.SettingsBack -> settingsFragmentTween
                else -> transitionTween
            }
        )
        TransitionPerfTrace.recordDuration(
            TransitionPerfStage.SCENE_ANIMATE,
            System.nanoTime() - animateStartedNs,
        )
        AppLogger.i("SceneHandoff", "ANIMATION_ENDPOINT kind=back from=${state.currentScene} to=$targetScene progress=${animProgress.value}")
        // The retained LayoutRes is already the terminal physical View state here. Commit the
        // navigation snapshot directly; do not manufacture an extra presentation-owner frame.
        // VirtualList HeaderToItem keeps the transition scalar at its destination LayoutRes until
        // P() has moved the concrete header View back into the destination holder via B.f(..., true).
        // Raw's physical holder is restored by the persistent VirtualList host after the route
        // commit becomes observable. Resetting animProgress to 0 before that handoff gives the
        // still-promoted View one draw at the header start geometry, which is the full-size flash
        // seen after the reverse animation has otherwise finished.
        val completingPhysicalSharedElementId = if (
            pageMotion == PageMotion.FolderSharedBack && sharedCoverActorEligible
        ) {
            sharedCoverRegistry.collectionSessionSnapshot()?.elementId.orEmpty()
        } else {
            ""
        }
        if (completingPhysicalSharedElementId.isNotBlank()) {
            sharedCoverRegistry.finishPhysicalReturn(completingPhysicalSharedElementId)
        }
        // The physical holder population already contains the terminal retained LayoutRes; this
        // snapshot only commits the navigation route and releases the second LayoutRes role.
        Snapshot.withMutableSnapshot {
            prevSceneRef.value = targetScene
            displayedScene = targetScene
            retainedScene = state.currentScene
            retainedDetailArgument = state.currentArgument.takeIf { state.currentScene.isDetail() }
            preparingScene = null
            isAnimating = false
            frozenSceneContent = null
            state.completeAnimatingBack()
        }
        if (!targetScene.isDetail()) {
            val retiredSharedOwner = sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
            AppLogger.i(
                "SceneHandoff",
                "SHARED_OWNER_BARRIER kind=back scene=$targetScene retired=$retiredSharedOwner",
            )
        }
        if (resetDetailToTopAfterCommit) {
            onCollectionDetailOffscreenReturnCommitted(sharedFromScene)
        }
        animProgress.snapTo(0f)
        topMenuAlpha.snapTo(1f)
        isBackTransition = false
        TransitionPerfTrace.stop("back_complete")
        AppLogger.i("SceneHandoff", "SCENE_END kind=back displayed=$displayedScene")
        if (revealAlphabetIndexerAfterCommit) {
            scope.launch {
                withFrameNanos { }
                alphabetIndexAlpha.animateTo(
                    1f,
                    tween(durationMillis = VIRTUAL_LIST_INDEXER_FADE_MS),
                )
            }
        } else {
            alphabetIndexAlpha.snapTo(1f)
        }
        } finally {
            // retained-view implementation destroys the transition object when its owning transaction ends; it does
            // not require a second navigation flag to have flipped first. Do the same here. If
            // this is still the current request, the effect ending is itself the terminal owner
            // boundary. In particular, do all non-suspending shared/View cleanup before touching
            // Animatable: a cancelled coroutine can throw again from snapTo() inside finally.
            if (state.transitionRequestId == requestId) {
                if (state.isAnimatingBack) {
                    state.abortAnimatingBack()
                }
                isAnimating = false
                isGestureActive = false
                preparingScene = null
                frozenSceneContent = null
                isBackTransition = false
                usesDirectionalBackPivot = false
                val settledScene = state.currentScene
                if (displayedScene == settledScene && !settledScene.isDetail()) {
                    sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
                }
                TransitionPerfTrace.stop("back_cancelled")
                withContext(NonCancellable) {
                    animProgress.snapTo(0f)
                    topMenuAlpha.snapTo(1f)
                }
            }
        }
    }

    LaunchedEffect(state.isDraggingBack, state.transitionRequestId) {
        val requestId = state.transitionRequestId
        if (!state.isDraggingBack) return@LaunchedEffect
        // Category/sibling gestures can target a scene that is not present in the
        // navigation back stack (notably a restored Music Library root).
        val targetScene = state.backPreviewScene ?: state.getPreviousScene()
            ?: return@LaunchedEffect
        val retainedVirtualListGesture = usesRetainedVirtualListPivot(state.currentScene, targetScene)
        preparingScene = targetScene.takeIf {
            retainedVirtualListGesture
        }
        fromScene = targetScene
        isBackTransition = true
        usesDirectionalBackPivot = true
        topMenuAlpha.snapTo(1f)
        sharedFromScene = state.currentScene
        sharedToScene = targetScene
        val sharedReturnRoute = usesSharedCoverMotion(state.currentScene, targetScene)
        val sharedReturnSourceReady = sharedReturnRoute &&
            sharedCoverRegistry.captureLiveCollectionReturnSource(
                fromSceneId = state.currentScene.name,
                toSceneId = targetScene.name,
            )
        if (sharedReturnRoute && !sharedReturnSourceReady) {
            sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
        }
        // Predictive HeaderToItem follows the same ownership rule as programmatic back: endpoint
        // capture prepares the transaction, but pixels are not handed off until the concrete View
        // promotion succeeds.
        sharedCoverActorEligible = false
        pageMotion = when {
            sharedReturnRoute -> PageMotion.FolderSharedBack
            usesHomeCategoryMotion(state.currentScene, targetScene) -> PageMotion.HomeCategoryBack
            usesArtistBiographyMotion(state.currentScene, targetScene) -> PageMotion.HomeCategoryBack
            state.backNavigationMotionHint == NavigationMotionHint.BOTTOM_NAVIGATION -> PageMotion.Generic
            usesSettingsFragmentMotion(state.currentScene, targetScene) -> PageMotion.SettingsBack
            else -> PageMotion.Generic
        }
        val revealAlphabetIndexerAfterCommit =
            !usesAlphabetIndexer(state.currentScene) && usesAlphabetIndexer(targetScene)
        alphabetIndexAlpha.snapTo(if (revealAlphabetIndexerAfterCommit) 0f else 1f)
        frozenSceneContent = if (retainedVirtualListGesture) {
            currentSceneContent
        } else {
            null
        }
        TransitionPerfTrace.start(
            newLabel = "drag ${state.currentScene.name}->${targetScene.name} motion=$pageMotion",
            refreshRateHz = transitionRefreshRateHz,
        )
        animProgress.snapTo(0f)
        TransitionPerfTrace.mark(
            "scene_begin",
            "kind=drag artwork=${PlaybackArtworkPerfTrace.overlapSummary()} retainedPivot=${frozenSceneContent != null} " +
                "owner=${if (frozenSceneContent != null) "persistent_layout" else "scene_root"} ${BitmapProvider.transitionDiagnosticsSummary()}",
        )
        if (pageMotion == PageMotion.FolderSharedBack && sharedReturnSourceReady) {
            val sharedKey = "${state.currentScene.name}->${targetScene.name}"
            val prepared = awaitSharedLayouts(
                coverRegistry = sharedCoverRegistry,
                fromScene = state.currentScene,
                toScene = targetScene,
                transitionKey = sharedKey,
                allowRememberedTarget = true
            )
            if (!prepared) {
                sharedCoverActorEligible = false
            } else {
                sharedCoverActorEligible = acquirePhysicalSharedActor(sharedCoverRegistry, sharedKey)
            }
        }
        // Gesture tracking must not block behind async prewarm. retained-view implementation samples the existing
        // CURRENT/NEXT LayoutRes immediately and lets O(progress) follow the finger from the first
        // frame; any later bitmap/text preparation is not allowed to delay gesture ownership.
        if (state.transitionRequestId != requestId) return@LaunchedEffect
        TransitionPerfTrace.mark(
            "gesture_provider_ready",
            "target=$targetScene progress=${"%.3f".format(state.dragBackProgress)}",
        )
        // Catch the presentation scalar up to the finger before exposing the transition owner for
        // all routes so the first predictive frame is already finger-synchronous.
        val initialGestureProgress = dampProgress(state.dragBackProgress).coerceIn(0f, 1f)
        animProgress.snapTo(initialGestureProgress)
        isGestureActive = true
        TransitionPerfTrace.markAnimateStart("kind=drag motion=$pageMotion")
        // From here until release, reversing the finger only updates the same scalar. CURRENT/NEXT
        // provider, holder and LayoutRes ownership stay fixed for the gesture lifetime.
    }

    LaunchedEffect(state.isDraggingBack, isGestureActive, state.transitionRequestId) {
        if (!state.isDraggingBack || !isGestureActive) return@LaunchedEffect
        snapshotFlow { state.dragBackProgress }
            .collect { progress ->
                animProgress.snapTo(dampProgress(progress))
            }
    }

    LaunchedEffect(state.dragBackReleaseToken) {
        val releaseToken = state.dragBackReleaseToken
        if (releaseToken == 0 || releaseToken == consumedDragBackReleaseToken) {
            return@LaunchedEffect
        }
        consumedDragBackReleaseToken = releaseToken
        val requestId = state.transitionRequestId
        val start = dampProgress(state.dragBackReleaseProgress).coerceIn(0f, 1f)
        val commit = state.dragBackReleaseCommit
        val target = if (commit) 1f else 0f
        // A release can arrive in the same snapshot turn that flips isDraggingBack=false. That
        // cancels the gesture-start LaunchedEffect, so none of its mutable SceneTransitionEngine
        // fields are authoritative here. In particular FolderSharedForward intentionally leaves
        // [fromScene] at its detail endpoint after the previous forward commit. Reading that stale
        // value here used to commit the visual scene back to the detail while NavigationState
        // simultaneously popped to the list; the next observer then misclassified DETAIL->LIST as
        // a new forward transition with no shared source.
        //
        // retained-view implementation's transition object owns immutable source/destination LayoutRes for its whole
        // lifetime. Reconstruct the release transaction from NavigationState itself so a rapid
        // predictive/system-back release cannot inherit endpoint state from the previous forward.
        val gestureSourceScene = state.currentScene
        val gestureTargetScene = state.backPreviewScene ?: state.getPreviousScene()
            ?: return@LaunchedEffect
        var releaseOwnerSettled = false
        try {
        val retainedGesturePivot = usesRetainedVirtualListPivot(gestureSourceScene, gestureTargetScene)
        val gestureOwnerWasReady = isGestureActive
        if (retainedGesturePivot && !gestureOwnerWasReady) {
            // A very fast release can cancel the gesture-start effect while the provider-only
            // preparation subtree is still composing. retained-view implementation does not wait here; reconstruct the
            // owner and continue with the LayoutRes that is currently available.
            preparingScene = gestureTargetScene
            frozenSceneContent = currentSceneContent
        }
        if (state.transitionRequestId != requestId) return@LaunchedEffect
        fromScene = gestureTargetScene
        sharedFromScene = gestureSourceScene
        sharedToScene = gestureTargetScene
        isBackTransition = true
        usesDirectionalBackPivot = true
        val releaseSharedReturnRoute = usesSharedCoverMotion(gestureSourceScene, gestureTargetScene)
        val releaseSharedReturnSourceReady = releaseSharedReturnRoute &&
            sharedCoverRegistry.captureLiveCollectionReturnSource(
                fromSceneId = gestureSourceScene.name,
                toSceneId = gestureTargetScene.name,
            )
        val resetDetailToTopAfterCommit =
            commit && releaseSharedReturnRoute && !releaseSharedReturnSourceReady
        if (commit && releaseSharedReturnRoute && !releaseSharedReturnSourceReady) {
            sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
        }
        val releaseSharedElementId = sharedCoverRegistry.collectionSessionSnapshot()?.elementId.orEmpty()
        sharedCoverActorEligible =
            releaseSharedReturnSourceReady &&
                releaseSharedElementId.isNotBlank() &&
                sharedCoverRegistry.isPhysicalPromotedElement(releaseSharedElementId)
        pageMotion = when {
            releaseSharedReturnRoute -> PageMotion.FolderSharedBack
            usesHomeCategoryMotion(gestureSourceScene, gestureTargetScene) -> PageMotion.HomeCategoryBack
            usesArtistBiographyMotion(gestureSourceScene, gestureTargetScene) -> PageMotion.HomeCategoryBack
            state.backNavigationMotionHint == NavigationMotionHint.BOTTOM_NAVIGATION -> PageMotion.Generic
            usesSettingsFragmentMotion(gestureSourceScene, gestureTargetScene) -> PageMotion.SettingsBack
            else -> PageMotion.Generic
        }
        val revealAlphabetIndexerAfterCommit = commit &&
            !usesAlphabetIndexer(gestureSourceScene) && usesAlphabetIndexer(gestureTargetScene)
        val sharedVirtualListSettle =
            pageMotion == PageMotion.FolderSharedBack && sharedCoverActorEligible
        if (sharedVirtualListSettle) {
            val targetScene = state.backPreviewScene ?: state.getPreviousScene()
            val transitionKey = targetScene?.let { "${state.currentScene.name}->${it.name}" }.orEmpty()
            if (targetScene != null && !sharedCoverRegistry.isPrepared(transitionKey)) {
                val prepared = awaitSharedLayouts(
                    coverRegistry = sharedCoverRegistry,
                    fromScene = state.currentScene,
                    toScene = targetScene,
                    transitionKey = transitionKey,
                    allowRememberedTarget = true
                )
                if (!prepared) sharedCoverActorEligible = false
            }
        }
        if (state.transitionRequestId != requestId) return@LaunchedEffect
        val normalizedVelocity = state.dragBackReleaseVelocity / screenWidthPx.coerceAtLeast(1f)
        val releaseMotion = collectionReleaseMotion(start, commit, normalizedVelocity)
        val carryVelocity = releaseMotion.carryVelocity
        val duration = releaseMotion.durationMillis
        val settleEasing = when {
            carryVelocity -> LinearEasing
            else -> VirtualListAccelerateDecelerate
        }
        TransitionPerfTrace.mark(
            "drag_release",
            "commit=$commit start=${"%.3f".format(start)} velocity=${state.dragBackReleaseVelocity.toInt()} duration=${duration}ms carry=$carryVelocity",
        )
        isAnimating = true
        animProgress.snapTo(start)
        val animateStartedNs = System.nanoTime()
        animProgress.animateTo(target, tween(duration, easing = settleEasing))
        TransitionPerfTrace.recordDuration(
            TransitionPerfStage.SCENE_ANIMATE,
            System.nanoTime() - animateStartedNs,
        )
        // Gesture commit uses the same terminal LayoutRes role commit as programmatic back. The
        // persistent physical holder owner remains attached, so no endpoint-only frame is needed.
        val completingGesturePhysicalSharedElementId = if (
            commit && pageMotion == PageMotion.FolderSharedBack && sharedCoverActorEligible
        ) {
            sharedCoverRegistry.collectionSessionSnapshot()?.elementId.orEmpty()
        } else {
            ""
        }
        if (completingGesturePhysicalSharedElementId.isNotBlank()) {
            sharedCoverRegistry.finishPhysicalReturn(completingGesturePhysicalSharedElementId)
        }
        // Retire the published live clock before resetting its scalar/direction. Otherwise the
        // persistent header can sample the cancelled HOME preview for one settled frame.
        val settledGestureScene = if (commit) gestureTargetScene else gestureSourceScene
        onTransitionFrameChanged(SceneTransitionFrame(
            active = false, progress = 1f,
            fromScene = settledGestureScene, toScene = settledGestureScene, isBack = false,
        ))
        Snapshot.withMutableSnapshot {
            if (commit) {
                val previousDisplayedScene = displayedScene
                // The target is already the final retained LayoutRes. Promote the route directly;
                // retained-view implementation swaps its current/retained slot references in-place and never exposes a
                // second endpoint role between gesture settle and the settled list.
                prevSceneRef.value = gestureTargetScene
                displayedScene = gestureTargetScene
                retainedScene = previousDisplayedScene
                retainedDetailArgument = state.currentArgument.takeIf { previousDisplayedScene.isDetail() }
            }
            preparingScene = null
            state.completeBackDrag(commit)
            isGestureActive = false
            isAnimating = false
            frozenSceneContent = null
        }
        if (commit && !gestureTargetScene.isDetail()) {
            val retiredSharedOwner = sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
            AppLogger.i(
                "SceneHandoff",
                "SHARED_OWNER_BARRIER kind=gesture scene=$gestureTargetScene retired=$retiredSharedOwner",
            )
        }
        if (resetDetailToTopAfterCommit) {
            onCollectionDetailOffscreenReturnCommitted(gestureSourceScene)
        }
        isBackTransition = false
        usesDirectionalBackPivot = false
        TransitionPerfTrace.stop(if (commit) "drag_commit" else "drag_cancel")
        AppLogger.i("SceneHandoff", "SCENE_END kind=gesture commit=$commit displayed=$displayedScene")
        releaseOwnerSettled = true
        withContext(NonCancellable) {
            animProgress.snapTo(0f)
            topMenuAlpha.snapTo(1f)
            if (!revealAlphabetIndexerAfterCommit) {
                alphabetIndexAlpha.snapTo(1f)
            }
        }
        if (revealAlphabetIndexerAfterCommit) {
            scope.launch {
                withFrameNanos { }
                alphabetIndexAlpha.animateTo(
                    1f,
                    tween(durationMillis = VIRTUAL_LIST_INDEXER_FADE_MS),
                )
            }
        }
        } finally {
            if (!releaseOwnerSettled && state.transitionRequestId == requestId) {
                // A predictive/system-back release can be cancelled while animateTo/snapTo is
                // suspended. retained-view implementation discards the transition object at that boundary; mirror it
                // by aborting NavigationState and retiring every local/shared presentation owner.
                state.completeBackDrag(commit = false)
                isAnimating = false
                isGestureActive = false
                preparingScene = null
                frozenSceneContent = null
                isBackTransition = false
                usesDirectionalBackPivot = false
                val settledScene = state.currentScene
                if (!settledScene.isDetail()) {
                    sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
                }
                TransitionPerfTrace.stop("drag_release_cancelled")
                withContext(NonCancellable) {
                    animProgress.snapTo(0f)
                    topMenuAlpha.snapTo(1f)
                }
            }
        }
    }

    val gestureModifier = Modifier.pointerInput(
        gesturesEnabled,
        providerBackGesture,
        state.canNavigateBack(),
        state.currentScene,
        horizontalGestureExclusionBounds,
        hostPositionInRoot
    ) {
        if (!gesturesEnabled) return@pointerInput
        val canNavigateBack = state.canNavigateBack()
        val canNavigateCategory = state.currentScene in horizontalCategoryScenes
        if (!canNavigateBack && !canNavigateCategory && providerBackGesture == null) return@pointerInput
        val edgeWidthPx = EDGE_DETECT_WIDTH_DP.dp.toPx()
        val touchSlop = viewConfiguration.touchSlop

        awaitEachGesture {
            val down = awaitFirstDown(
                requireUnconsumed = false,
                pass = PointerEventPass.Initial
            )
            if (isAnimating || state.isDraggingBack || state.isSettlingBack) {
                return@awaitEachGesture
            }
            // The mini-player owns horizontal track switching. Do not let the parent scene
            // interceptor observe the same pointer sequence and start a page transition. The
            // mini-player bounds are reported in root coordinates, while this pointer node may
            // be translated during a scene transition, so normalize the down point first.
            val downInRoot = Offset(
                x = down.position.x + hostPositionInRoot.x,
                y = down.position.y + hostPositionInRoot.y,
            )
            val miniPlayerBounds = horizontalGestureExclusionBounds
            val expandedMiniPlayerBounds = miniPlayerBounds?.let { bounds ->
                Rect(
                    // The mini player owns the whole horizontal pointer sequence, not just
                    // the visible card. Cover the complete scene width over its vertical band
                    // so an edge-origin swipe cannot be re-captured by the parent host after
                    // the card's horizontal padding or rounded corners are crossed.
                    left = minOf(bounds.left - edgeWidthPx, hostPositionInRoot.x),
                    top = bounds.top - edgeWidthPx,
                    right = maxOf(
                        bounds.right + edgeWidthPx,
                        hostPositionInRoot.x + size.width.toFloat()
                    ),
                    bottom = bounds.bottom + edgeWidthPx,
                )
            }
            if (expandedMiniPlayerBounds?.contains(downInRoot) == true) {
                return@awaitEachGesture
            }
            val localWidthPx = size.width.toFloat().coerceAtLeast(1f)
            val providerBack = providerBackGesture?.takeIf { it.available }
            val prev = state.getPreviousScene()
            val categoryScene = providerBack == null && state.currentScene in horizontalCategoryScenes
            val sharedVirtualListBack = prev?.let { usesSharedCoverMotion(state.currentScene, it) } == true
            val allowContentDrag = providerBack != null || (prev?.let { allowsContentBackDrag(state.currentScene, it) }
                ?: canNavigateCategory)
            var direction = when {
                down.position.x <= edgeWidthPx -> 1f
                down.position.x >= localWidthPx - edgeWidthPx -> -1f
                else -> 0f
            }
            if (!allowContentDrag && direction == 0f) return@awaitEachGesture

            var gestureDecided = false
            var dragging = false
            var startX = down.position.x
            var effectiveStartX = startX
            var startY = down.position.y
            var lastX = startX
            var lastTime = down.uptimeMillis
            var velocityX = 0f
            var rawProgress = 0f
            if (prev != null && providerBack == null) fromScene = prev
            var categoryTarget: NavScene? = null
            var providerGestureStarted = false
            try {
            while (true) {
                // Observe before VirtualList/LazyColumn consumes the stream. We still leave vertical
                // gestures untouched and only consume after horizontal intent is established.
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.fastFirstOrNull { it.id == down.id }
                if (change == null) {
                    if (dragging && providerBack != null) {
                        return@awaitEachGesture
                    }
                    break
                }
                if (!change.pressed) {
                    break
                }

                val dx = change.position.x - startX
                val dy = change.position.y - startY
                val absDx = abs(dx)
                val absDy = abs(dy)

                if (!gestureDecided && (absDx > touchSlop || absDy > touchSlop)) {
                    gestureDecided = true
                    if (direction == 0f) {
                        direction = if (dx < 0f) -1f else 1f
                    }
                    dragging = absDx > absDy * 1.15f && (allowContentDrag || dx * direction > 0f)
                    if (!dragging) return@awaitEachGesture
                    // A full-width horizontal gesture inside a category switches to its sibling.
                    // Keep the system-style edge gesture reserved for returning to the parent.
                    val isEdgeBack = direction > 0f && startX <= edgeWidthPx
                    if (providerBack != null) {
                        if (!providerBack.begin(direction)) return@awaitEachGesture
                        providerGestureStarted = true
                    } else if (categoryScene && !isEdgeBack) {
                        categoryTarget = adjacentCategory(state.currentScene, direction)
                        if (categoryTarget != null) {
                            fromScene = categoryTarget
                            val started = state.startSiblingDrag(categoryTarget, direction)
                            if (!started) return@awaitEachGesture
                        } else if (state.currentScene == NavScene.SONGS && direction > 0f) {
                            // SONGS may be restored as the root entry, so a previous stack item is
                            // not guaranteed. Its rightward gesture still has an explicit HOME target.
                            categoryTarget = NavScene.HOME
                            fromScene = NavScene.HOME
                            val started = state.startSiblingDrag(NavScene.HOME, direction)
                            if (!started) {
                                return@awaitEachGesture
                            }
                        } else if (prev != null && direction > 0f) {
                            fromScene = prev
                            if (!state.startBackDrag(direction)) return@awaitEachGesture
                        } else {
                            return@awaitEachGesture
                        }
                    } else if (prev != null) {
                        if (!state.startBackDrag(direction)) return@awaitEachGesture
                    } else {
                        categoryTarget = adjacentCategory(state.currentScene, direction)
                        if (categoryTarget == null) return@awaitEachGesture
                        fromScene = categoryTarget
                        if (!state.startSiblingDrag(categoryTarget, direction)) return@awaitEachGesture
                    }
                    // Remove touch slop from the captured origin. The first visual frame
                    // therefore starts at exactly zero instead of jumping by the recognition delta.
                    effectiveStartX = startX + direction * touchSlop
                    change.consume()
                }

                if (dragging) {
                    val dt = (change.uptimeMillis - lastTime).coerceAtLeast(1L)
                    velocityX = (change.position.x - lastX) / dt * 1000f
                    lastX = change.position.x
                    lastTime = change.uptimeMillis
                    val effectiveDx = change.position.x - effectiveStartX
                    rawProgress = if (localWidthPx > 0f) {
                        (effectiveDx * direction) / localWidthPx
                    } else {
                        0f
                    }
                    if (providerBack != null) providerBack.update(rawProgress.coerceIn(0f, 1f))
                    else state.updateBackDrag(rawProgress)
                    change.consume()
                }
            }

                if (dragging) {
                    val signedVelocity = velocityX * direction
                    val releaseProgress = rawProgress.coerceIn(0f, 1f)
                    val velocityDpPerSecond = signedVelocity / density
                    val commit = if (abs(velocityDpPerSecond) >= VIRTUAL_LIST_VELOCITY_DP_PER_S) {
                        velocityDpPerSecond > 0f
                    } else {
                        releaseProgress > VIRTUAL_LIST_COMMIT_PROGRESS
                    }
                    if (providerBack != null) {
                        providerGestureStarted = false
                        providerBack.release(commit, signedVelocity / localWidthPx)
                    } else if (categoryTarget != null) {
                        state.releaseBackDrag(commit = commit, velocity = signedVelocity)
                    } else if (prev != null) {
                        state.releaseBackDrag(commit = commit, velocity = signedVelocity)
                    }
            }
            } finally {
                if (providerGestureStarted) providerBack?.cancel?.invoke()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { coordinates ->
                hostPositionInRoot = coordinates.positionInRoot()
                hostPositionInWindow = coordinates.positionInWindow()
            }
            .then(gestureModifier)
    ) {
        Layout(
            content = {},
            modifier = Modifier.fillMaxSize(),
            measurePolicy = { _, constraints ->
                screenWidthPx = constraints.maxWidth.toFloat()
                screenHeightPx = constraints.maxHeight.toFloat()
                layout(0, 0) {}
            }
        )

        // state.isDraggingBack is also the preflight ownership signal. HOME/category no longer
        // exposes two scene-root nodes; isGestureActive only switches the unified old/current layout
        // owner into motion while the VirtualList scroll mutex is handed off synchronously.
        val inTransition = isAnimating || isGestureActive
        LaunchedEffect(inTransition) {
            onTransitionActiveChanged(inTransition)
        }
        LaunchedEffect(inTransition, displayedScene, state.currentScene) {
            if (
                !inTransition &&
                displayedScene == state.currentScene
            ) {
                if (!displayedScene.isDetail()) {
                    val retiredSharedOwner = sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
                    if (retiredSharedOwner) {
                        AppLogger.i(
                            "SceneHandoff",
                            "SHARED_OWNER_WATCHDOG scene=$displayedScene retired=true",
                        )
                    }
                } else if (sharedCoverRegistry.isPhysicalPromotionActive()) {
                    val session = sharedCoverRegistry.collectionSessionSnapshot()
                    val validDetailOwner = session != null &&
                        session.detailSceneId == displayedScene.name &&
                        sharedCoverRegistry.isPhysicalPromotedElement(session.elementId)
                    if (!validDetailOwner) {
                        val retiredSharedOwner = sharedCoverRegistry.retireAnyPhysicalSharedOwnership()
                        AppLogger.w(
                            "SceneHandoff",
                            "ORPHAN_SHARED_OWNER detail=$displayedScene retired=$retiredSharedOwner",
                        )
                    }
                }
            }
        }

        // the retained-view implementation's transition manager owns one scalar clock and writes it directly into retained
        // View geometry. Do the same here: publish route ownership once, then let background/chrome
        // draw/layer callbacks sample animProgress directly. The previous snapshotFlow -> callback ->
        // mutableFloatState relay duplicated every vsync through the Compose Snapshot system.
        LaunchedEffect(inTransition, isBackTransition, fromScene, displayedScene, state.currentScene) {
            // NavigationState publishes the new route before this host can acquire the transition
            // owner. Keep the old background alive during that handoff instead of publishing an
            // idle frame for old->old. That one frame is the Compose equivalent of detaching the
            // old retained scene layout before retained-view implementation's transition mode is enabled.
            val routeChangePending = !inTransition &&
                state.currentScene != displayedScene &&
                shouldAnimate(displayedScene, state.currentScene)
            val backgroundFromScene = when {
                routeChangePending -> displayedScene
                inTransition && isBackTransition -> displayedScene
                else -> fromScene
            }
            val backgroundToScene = when {
                routeChangePending -> state.currentScene
                inTransition && isBackTransition -> fromScene
                else -> displayedScene
            }
            if (!inTransition) {
                if (routeChangePending) {
                    onTransitionFrameChanged(
                        SceneTransitionFrame(
                            active = true,
                            // Forward navigation has not moved yet. The source layer must remain
                            // fully owned until the persistent list publishes its retained role.
                            progress = 0f,
                            fromScene = backgroundFromScene,
                            toScene = backgroundToScene,
                            isBack = false,
                        )
                    )
                    return@LaunchedEffect
                }
                // Keep the last active endpoint through the first settled draw pass. The
                // permanent VirtualList presentation host can then atomically publish SETTLED
                // before the background/chrome leaves transition mode, matching retained-view implementation's
                // attach-new/retire-old ordering.
                withFrameNanos { }
                if (isAnimating || isGestureActive) return@LaunchedEffect
                onTransitionFrameChanged(
                    SceneTransitionFrame(
                        active = false,
                        progress = 1f,
                        fromScene = displayedScene,
                        toScene = displayedScene,
                        isBack = false,
                    )
                )
            } else {
                val publishedBackDirection = isBackTransition
                val directProgressProvider: () -> Float = {
                    val frameProgress = animProgress.value.coerceIn(0f, 1f)
                    if (publishedBackDirection) frameProgress else 1f - frameProgress
                }
                onTransitionFrameChanged(
                    SceneTransitionFrame(
                        active = true,
                        progress = directProgressProvider(),
                        fromScene = backgroundFromScene,
                        toScene = backgroundToScene,
                        isBack = isBackTransition,
                        progressProvider = directProgressProvider,
                    )
                )
            }
        }

        val sharedItemMotion =
            sharedCoverActorEligible &&
                (pageMotion == PageMotion.FolderSharedForward || pageMotion == PageMotion.FolderSharedBack)
        val retainedVirtualListPivot = inTransition && usesRetainedVirtualListPivot(fromScene, displayedScene)
        val physicalVirtualListProgressProvider: () -> Float =
            { animProgress.value.coerceIn(0f, 1f) }
        val sceneFrameProgressProvider: () -> Float =
            { animProgress.value.coerceIn(0f, 1f) }
        val freezeRetainedVirtualListDynamics = retainedVirtualListPivot
        val detachAlphabetIndex = usesDirectionalBackPivot && fromScene == NavScene.HOME
        // Shared cover motion must not be the last path which pulls the transition clock back into
        // SceneTransitionHost composition.  The overlay and retained holder population sample this
        // provider from their draw/layer callbacks, just like VirtualList's shared-item View property
        // animation.  Route/owner changes still recompose at transition boundaries; vsync progress
        // does not.
        val sharedProgressProvider = remember(
            animProgress,
            pageMotion,
            isBackTransition,
        ) {
            {
                val frameProgress = animProgress.value.coerceIn(0f, 1f)
                when (pageMotion) {
                    PageMotion.FolderSharedForward -> 1f - frameProgress
                    PageMotion.FolderSharedBack -> frameProgress
                    else -> if (isBackTransition) frameProgress else 1f - frameProgress
                }.coerceIn(0f, 1f)
            }
        }

        val sharedActive = sharedItemMotion &&
            (inTransition || preparingScene != null) &&
            sharedFromScene != sharedToScene
        val transitionKey = "${sharedFromScene.name}->${sharedToScene.name}"
        if (!sharedActive) {
            sharedCoverRegistry.clearFrozen()
        }

        val preparedSharedPair = sharedCoverRegistry.getPreparedPair(transitionKey)
        val sharedSpec = SharedTransitionSpec(
            active = sharedActive,
            fromSceneId = sharedFromScene.name,
            toSceneId = sharedToScene.name,
            activeSceneId = displayedScene.name,
            progress = 0f,
            progressProvider = if (sharedActive) sharedProgressProvider else null,
            transitionKey = transitionKey,
            allowRememberedTarget = pageMotion == PageMotion.FolderSharedBack,
            // retained-view implementation promotes one concrete View/holder between CURRENT/NEXT LayoutRes records.
            // Publish only the element identity here; the list/header holders are allowed to hide
            // their normal artwork lane later only when the registry confirms the concrete
            // promoted View is live. A prepared pair alone must not hide pixels.
            ownedElementId = preparedSharedPair?.first?.elementId.orEmpty(),
            sharedCoverOverlayOwnsAnchor = sharedActive
        )
        // Keep the animation scalar in a State read by the render nodes below.  Do not build a
        // new PageTransform in the host composition for every frame: that makes the complete
        // scene slot participate in the animation recomposition.  Reference keeps its cached
        // layout holders in place and updates only their layer properties.
        // Reference PivotTransition does not animate two full page RenderNodes.
        // VirtualListPivotTransition.B() asks one VirtualList for its current `r` layout and
        // retained previous `r` layout, then configures mode=4 on those two layout states. Holder
        // LayoutRes consumes pivot/scale/alpha from the shared transition clock. Mirror that owner
        // split here: the unified host below retains two layout slots, while motion lives in the
        // existing Home/VirtualList holder draw/layer state instead of two scene-root nodes.
        // PivotTransition uses the 0.5 / 1 / 1.5 LayoutRes depth endpoints. retained-view implementation forward moves the
        // visible CURRENT/source 1 -> 1.5 while NEXT/destination settles 0.5 -> 1; Back is the exact
        // retained/current role reverse. Programmatic forward navigation remains centre-pivoted;
        // captured back/sibling gestures use the established off-screen page-space pivot.
        val genericPivotX = if (
            pageMotion == PageMotion.HomeCategoryBack && usesDirectionalBackPivot
        ) {
            if (state.dragBackDirection >= 0f) screenWidthPx * 1.5f else -screenWidthPx * 0.5f
        } else {
            screenWidthPx * 0.5f
        }
        val genericPivotY = screenHeightPx * 0.5f
        val sharedBackPivotX = collectionBackPivotX(
            screenWidthPx,
            pageMotion == PageMotion.FolderSharedBack && usesDirectionalBackPivot,
            state.dragBackDirection,
        )
        // retained-view implementation uses two different transition families here:
        //
        // ItemToHeader (list -> detail): the CURRENT list LayoutRes pivots around the selected
        // item's bottom edge; the NEXT detail/header LayoutRes stays viewport-centred.
        // HeaderToItem (detail -> list): the CURRENT detail/header stays viewport-centred while
        // the NEXT list LayoutRes pivots around the target item's bottom edge. Back-swipe applies
        // the same +/- one-viewport horizontal pivot offset to both LayoutRes records.
        val sharedForwardSourcePivotY = preparedSharedPair?.first
            ?.virtualListItemBottomInHost(hostPositionInWindow)
            ?: genericPivotY
        val sharedBackTargetPivotY = preparedSharedPair?.second
            ?.virtualListItemBottomInHost(hostPositionInWindow)
            ?: genericPivotY
        val fromPivotX = when (pageMotion) {
            PageMotion.FolderSharedBack -> sharedBackPivotX
            else -> genericPivotX
        }
        val fromPivotY = when (pageMotion) {
            PageMotion.FolderSharedForward -> sharedForwardSourcePivotY
            // Back RETAINED is the destination collection/list LayoutRes. HeaderToItem gives
            // that target the clicked item's bottom-edge pivot.
            PageMotion.FolderSharedBack -> sharedBackTargetPivotY
            else -> genericPivotY
        }
        val currentPivotX = when (pageMotion) {
            PageMotion.FolderSharedBack -> sharedBackPivotX
            else -> genericPivotX
        }
        val currentPivotY = when (pageMotion) {
            PageMotion.FolderSharedForward -> genericPivotY
            // Back CURRENT is the detail/header source and remains viewport-centred.
            PageMotion.FolderSharedBack -> genericPivotY
            else -> genericPivotY
        }
        val fromSceneItemTransform: RetainedSceneItemTransform? = if (retainedVirtualListPivot) {
            RetainedSceneItemTransform(
                pivotX = fromPivotX,
                pivotY = fromPivotY,
                scaleProvider = {
                    referenceGenericPivotScale(
                        motion = pageMotion,
                        progress = physicalVirtualListProgressProvider(),
                        sourceLayout = true,
                    )
                },
                alphaProvider = {
                    referenceGenericPivotAlpha(
                        motion = pageMotion,
                        progress = physicalVirtualListProgressProvider(),
                        sourceLayout = true,
                    )
                },
            )
        } else null
        val currentSceneItemTransform: RetainedSceneItemTransform? = if (retainedVirtualListPivot) {
            RetainedSceneItemTransform(
                pivotX = currentPivotX,
                pivotY = currentPivotY,
                scaleProvider = {
                    referenceGenericPivotScale(
                        motion = pageMotion,
                        progress = physicalVirtualListProgressProvider(),
                        sourceLayout = false,
                    )
                },
                alphaProvider = {
                    referenceGenericPivotAlpha(
                        motion = pageMotion,
                        progress = physicalVirtualListProgressProvider(),
                        sourceLayout = false,
                    )
                },
            )
        } else null
        val preflightHomeCategoryMotion =
            !inTransition && state.isAnimatingBack && displayedScene in homeCategoryTransitionScenes
        // Predictive/sibling back is different from forward navigation: the target route is published
        // before isGestureActive/isAnimating flips the persistent VirtualList into its two-layout mode.
        // Use that preflight composition to publish the destination provider while the owner is still
        // idle. Registry publication is synchronous, so the following transition composition can
        // acquire the retained provider immediately instead of showing an empty target on the first
        // process visit. Do not wait for a later transition frame; by then retainedProvider was
        // already resolved from the registry for that composition.
        val preflightBackPrepareScene = if (!inTransition) {
            when {
                state.isDraggingBack -> state.backPreviewScene ?: state.getPreviousScene()
                state.isAnimatingBack -> state.getPreviousScene()
                else -> null
            }?.takeIf { target ->
                target != displayedScene && usesRetainedVirtualListPivot(displayedScene, target)
            }
        } else {
            null
        }
        // startSiblingDrag()/predictive back publishes the target before the LaunchedEffect above has
        // swapped fromScene. Give the currently-visible VirtualList the ownership signal in that same
        // composition so it can cancel an active fling before the first scene RenderNode frame.
        val preflightDraggingRetainedVirtualListMotion =
            state.isDraggingBack &&
                state.backPreviewScene?.let { usesRetainedVirtualListPivot(displayedScene, it) } == true
        val effectivePreparingScene = preparingScene ?: preflightBackPrepareScene
        val preparingRetainedVirtualListMotion =
            effectivePreparingScene?.let { usesRetainedVirtualListPivot(displayedScene, it) } == true ||
                preflightHomeCategoryMotion ||
                preflightDraggingRetainedVirtualListMotion
        val chromePreparingScene = effectivePreparingScene?.takeIf { !inTransition && it != displayedScene }
        val chromeBackDirection = isBackTransition
        val topMenuExitScene = if (chromePreparingScene != null) displayedScene else if (chromeBackDirection) displayedScene else fromScene
        val topMenuEnterScene = chromePreparingScene ?: if (chromeBackDirection) fromScene else displayedScene
        // Top chrome has its own retained scene layout-style property animation and is not coupled to
        // the persistent VirtualList holder population. It still consumes the same scene clock so
        // source and destination title/actions hand off with the provider LayoutRes.
        val topMenuSceneHandoffActive = (inTransition || chromePreparingScene != null) && topMenuExitScene != topMenuEnterScene
        CompositionLocalProvider(
            LocalSharedCoverRegistry provides sharedCoverRegistry,
            LocalSharedTransitionSpec provides sharedSpec,
            LocalSceneChromeAlpha provides SceneChromeAlpha(
                // index scroller owns its alpha as a View property. Sample both the independent
                // mode animator and the rare detached-swipe envelope in graphicsLayer instead of
                // reading either scalar in this composition.
                alphabetIndex = 1f,
                alphabetIndexProvider = {
                    val gestureAlpha = if (detachAlphabetIndex) {
                        ((1f - sceneFrameProgressProvider()) / 0.2f).coerceIn(0f, 1f)
                    } else {
                        1f
                    }
                    alphabetIndexAlpha.value * gestureAlpha
                },
                topMenu = 1f,
                topMenuProvider = {
                    // TopNav has its own retained scene layout-style exit transform below. Do not reuse
                    // index scroller's short 20% detach envelope here: doing so makes the menu
                    // disappear before the finger/PivotTransition clock has travelled the same amount.
                    topMenuAlpha.value
                },
                topMenuExitProgressProvider = if (topMenuSceneHandoffActive) {
                    {
                        // The visible top-nav owner is always the currently presented/source
                        // controller while PivotTransition is in flight. retained-view implementation applies the scene
                        // transform to those already-attached children for both directions, so the
                        // same 0 -> 1 exit envelope must follow the live gesture clock on forward,
                        // programmatic back and predictive/sibling back.
                        val p = sceneFrameProgressProvider()
                        if (chromePreparingScene != null) 0f else if (chromeBackDirection) p else 1f - p
                    }
                } else {
                    null
                },
                topMenuExitSceneId = if (topMenuSceneHandoffActive) topMenuExitScene.name else "",
                topMenuEnterSceneId = if (topMenuSceneHandoffActive) topMenuEnterScene.name else "",
                topMenuTranslationProgress = 0f,
                detachAlphabetIndex = detachAlphabetIndex,
            ),
        ) {
            // Reference keeps one VirtualList object while its provider/layout state changes.  The
            // library branch below therefore composes one persistent owner only.  Retained/current
            // provider populations live inside that owner as layout states; SceneTransitionEngine
            // never creates a second HOME/category SceneContent root for PivotTransition.
            // Keep the one library owner mounted from the preflight composition onward. There is a
            // short scheduling window where animation ownership has started (`inTransition`) but
            // displayedScene/fromScene have not yet formed the retained pivot. Dropping the owner in
            // that window destroys the physical holder/runtime and recreates it in the first motion
            // frame, which exposes a blank/fallback frame after entry.
            val persistentHomeCategoryOwner = retainedVirtualListPivot ||
                preparingRetainedVirtualListMotion ||
                // Animation flags and endpoint roles can settle in adjacent compositions. With
                // equal endpoints there is no page pair to animate: keep the mounted library
                // visible instead of falling through to a fresh, top-positioned SceneContent.
                (isPersistentHomeCategoryScene(displayedScene) &&
                    (!inTransition || fromScene == displayedScene))
            // Settings/other root scenes may reveal HOME (or another reference library scene)
            // through an ordinary page transition. Keep that library side in the exact same
            // persistent owner that will remain after commit; composing it through SceneContent
            // during preview and replacing it with ReferenceLibraryVirtualListHost at the endpoint
            // causes the one-frame owner swap/jump seen on Settings -> HOME predictive back.
            val mixedPersistentLibraryTransition = inTransition &&
                fromScene != displayedScene &&
                (isPersistentHomeCategoryScene(fromScene) xor
                    isPersistentHomeCategoryScene(displayedScene))
            val mixedPersistentLibraryScene = when {
                !mixedPersistentLibraryTransition -> null
                isPersistentHomeCategoryScene(fromScene) -> fromScene
                isPersistentHomeCategoryScene(displayedScene) -> displayedScene
                else -> null
            }
            val persistentLibraryScene = when {
                persistentHomeCategoryOwner -> displayedScene
                mixedPersistentLibraryTransition -> mixedPersistentLibraryScene
                else -> parkedLibraryScene
            }
            val persistentLibraryPresentationVisible =
                persistentHomeCategoryOwner || mixedPersistentLibraryTransition
            // Settings covers the library; it must not detach its page/viewport and
            // recreate HOME's measured header and scroll geometry during return.
            val persistentLibraryControllerVisible = persistentLibraryScene != null

            fun transitionProviderIdentity(scene: NavScene?): String {
                if (scene == null || !scene.isDetail()) return ""
                return when (scene) {
                    state.transitionFromScene -> state.transitionFromArgument
                    state.transitionToScene -> state.transitionToArgument
                    displayedScene -> state.currentArgument
                    else -> retainedDetailArgument.orEmpty()
                }
            }

            // retained-view implementation's endpoint preparation binds NEXT provider/LayoutRes before O(progress) starts, but it
            // does not start the PivotTransition transaction merely to prepare NEXT. Raw's previous
            // "preflight active" role tried to emulate endpoint preparation by setting active=true early; that
            // is not equivalent here because ReferenceLibraryVirtualListHost intentionally skips
            // ProviderPreflight while the frame is active. The result was a cold entry that never
            // published the exact detail provider until the animation was already underway. Keep
            // preflight as provider-publication-only; only the real 250ms motion owns active dual
            // LayoutRes roles.
            val libraryDualLayoutActive = retainedVirtualListPivot
            val libraryRetainedScene = when {
                retainedVirtualListPivot -> fromScene
                else -> null
            }

            val libraryFrame = ReferenceLibraryLayoutFrame(
                active = libraryDualLayoutActive,
                retainedScene = libraryRetainedScene,
                currentScene = persistentLibraryScene ?: displayedScene,
                retainedProviderIdentity = if (libraryDualLayoutActive) {
                    transitionProviderIdentity(libraryRetainedScene)
                } else {
                    ""
                },
                currentProviderIdentity = transitionProviderIdentity(
                    persistentLibraryScene ?: displayedScene
                ),
                // Forward navigation promotes displayedScene to the destination before the first
                // PivotTransition frame. Keep the source controller/presentation mounted until motion
                // completes so progress=0 is pixel-identical to the settled source. Back/gesture
                // motion already leaves displayedScene on the visible source until commit.
                presentationScene = if (!persistentLibraryPresentationVisible) {
                    persistentLibraryScene
                } else if (mixedPersistentLibraryTransition) {
                    mixedPersistentLibraryScene
                } else {
                    if (retainedVirtualListPivot && !isBackTransition) {
                        fromScene
                    } else {
                        displayedScene
                    }
                },
                presentationProviderIdentity = transitionProviderIdentity(
                    if (!persistentLibraryPresentationVisible) {
                        persistentLibraryScene
                    } else if (mixedPersistentLibraryTransition) {
                        mixedPersistentLibraryScene
                    } else {
                        if (retainedVirtualListPivot && !isBackTransition) fromScene else displayedScene
                    }
                ),
                preparingScene = if (!retainedVirtualListPivot && !mixedPersistentLibraryTransition) {
                    effectivePreparingScene
                } else {
                    null
                },
                preparingProviderIdentity = transitionProviderIdentity(
                    if (!retainedVirtualListPivot && !mixedPersistentLibraryTransition) {
                        effectivePreparingScene
                    } else {
                        null
                    }
                ),
                retainedTransform = if (retainedVirtualListPivot) fromSceneItemTransform else null,
                currentTransform = if (retainedVirtualListPivot) currentSceneItemTransform else null,
            )

            // This is the structural equivalent of retained-view implementation's one retained scene layout/VirtualList owner:
            // it is a fixed sibling of route content. H/C transitions only update locals and the
            // persistent provider body; they do not move the presentation host through movable
            // SceneContent or remount it at the entry endpoint.
            if (persistentLibraryScene != null) {
                val libraryUsesFromRole = mixedPersistentLibraryTransition &&
                    persistentLibraryScene == fromScene
                val libraryUsesCurrentRole = mixedPersistentLibraryTransition &&
                    persistentLibraryScene == displayedScene
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            if (!persistentLibraryPresentationVisible) {
                                alpha = 0f
                                return@graphicsLayer
                            }
                            if (!mixedPersistentLibraryTransition) return@graphicsLayer
                            val frameProgress = sceneFrameProgressProvider()
                            val motion = pageMotion
                            val transform = pageTransforms(
                                motion = motion,
                                progress = frameProgress,
                                directionalBack = usesDirectionalBackPivot,
                                backDirection = state.dragBackDirection,
                                viewportWidth = screenWidthPx,
                                viewportHeight = screenHeightPx,
                                hostPositionInWindow = hostPositionInWindow,
                                sharedPair = preparedSharedPair,
                            )
                            if (libraryUsesFromRole) {
                                translationX = transform.fromTranslationX
                                translationY = transform.fromTranslationY
                                scaleX = transform.fromScale
                                scaleY = transform.fromScale
                                alpha = transform.fromAlpha
                                transformOrigin = transform.fromTransformOrigin
                            } else if (libraryUsesCurrentRole) {
                                translationX = transform.currentTranslationX
                                translationY = transform.currentTranslationY
                                scaleX = transform.currentScale
                                scaleY = transform.currentScale
                                alpha = transform.currentAlpha
                                transformOrigin = transform.currentTransformOrigin
                            }
                            // Match ordinary Android View alpha/transform semantics used by the
                            // reference scene hierarchy. ModulateAlpha pushes the full-screen
                            // transition alpha through every child draw op, which keeps HWUI's
                            // IssueDrawCommands path expensive even when the scene display lists
                            // themselves are unchanged.
                            compositingStrategy = CompositingStrategy.Auto
                        }
                ) {
                CompositionLocalProvider(
                    LocalReferenceLibraryLayoutFrame provides libraryFrame,
                    LocalSceneBackgroundFrozen provides freezeRetainedVirtualListDynamics,
                    LocalRetainedSceneMotionActive provides retainedVirtualListPivot,
                    LocalRetainedScenePreflightOwnership provides (
                        preparingRetainedVirtualListMotion && !retainedVirtualListPivot
                    ),
                    LocalRetainedSceneItemTransform provides null,
                ) {
                        persistentLibraryContent(
                            persistentLibraryScene,
                            persistentLibraryPresentationVisible,
                            persistentLibraryControllerVisible,
                            content,
                        )
                    }
                }
            }

            if (persistentHomeCategoryOwner) {
                // The fixed persistent library slot above is the complete visible owner.
            } else if (mixedPersistentLibraryTransition) {
                // The library side is already rendered by the fixed slot above. Render only the
                // non-library counterpart here so Settings -> HOME does not create a second HOME
                // tree during preview and then swap owners at commit.
                val nonLibraryScene = if (isPersistentHomeCategoryScene(fromScene)) {
                    displayedScene
                } else {
                    fromScene
                }
                val nonLibraryUsesFromRole = nonLibraryScene == fromScene
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val frameProgress = sceneFrameProgressProvider()
                            val transform = pageTransforms(
                                motion = pageMotion,
                                progress = frameProgress,
                                directionalBack = usesDirectionalBackPivot,
                                backDirection = state.dragBackDirection,
                                viewportWidth = screenWidthPx,
                                viewportHeight = screenHeightPx,
                                hostPositionInWindow = hostPositionInWindow,
                                sharedPair = preparedSharedPair,
                            )
                            if (nonLibraryUsesFromRole) {
                                translationX = transform.fromTranslationX
                                translationY = transform.fromTranslationY
                                scaleX = transform.fromScale
                                scaleY = transform.fromScale
                                alpha = transform.fromAlpha
                                transformOrigin = transform.fromTransformOrigin
                            } else {
                                translationX = transform.currentTranslationX
                                translationY = transform.currentTranslationY
                                scaleX = transform.currentScale
                                scaleY = transform.currentScale
                                alpha = transform.currentAlpha
                                transformOrigin = transform.currentTransformOrigin
                            }
                            compositingStrategy = CompositingStrategy.Auto
                        }
                ) {
                    SceneContent(nonLibraryScene)
                }
            } else if (inTransition && fromScene != displayedScene) {
                // 来源页面（下层）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val frameProgress = sceneFrameProgressProvider()
                            // Shared-holder preparation is a separate owner from the Item/Header
                            // scene motion. If the hero is offscreen there is intentionally no pair,
                            // but the retained scene transition must still animate normally.
                            val motion = pageMotion
                            val preparationTransform = if (
                                sharedCoverActorEligible && preparedSharedPair == null
                            ) {
                                sharedPreparationTransform(motion)
                            } else {
                                null
                            }
                            val transform = preparationTransform ?: pageTransforms(
                                motion = motion,
                                progress = frameProgress,
                                directionalBack = usesDirectionalBackPivot,
                                backDirection = state.dragBackDirection,
                                viewportWidth = screenWidthPx,
                                viewportHeight = screenHeightPx,
                                hostPositionInWindow = hostPositionInWindow,
                                sharedPair = preparedSharedPair,
                            )
                            translationX = transform.fromTranslationX
                            translationY = transform.fromTranslationY
                            scaleX = transform.fromScale
                            scaleY = transform.fromScale
                            alpha = transform.fromAlpha
                            compositingStrategy = CompositingStrategy.Auto
                            transformOrigin = transform.fromTransformOrigin
                        }
                ) {
                    CompositionLocalProvider(
                        // Keep expensive Haze/background recorders quiet during the short retained
                        // pivot. Holder artwork/marquee clocks stay live; holder geometry is driven
                        // independently through LocalRetainedSceneItemTransform.
                        LocalSceneBackgroundFrozen provides freezeRetainedVirtualListDynamics,
                        LocalRetainedSceneMotionActive provides retainedVirtualListPivot,
                        LocalRetainedSceneItemTransform provides fromSceneItemTransform,
                    ) {
                        SceneContent(fromScene)
                    }
                }

                // 当前页面（上层）
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer {
                            val frameProgress = sceneFrameProgressProvider()
                            // See the source role above: no shared holder means no preparation mask,
                            // not a different scene transition family.
                            val motion = pageMotion
                            val preparationTransform = if (
                                sharedCoverActorEligible && preparedSharedPair == null
                            ) {
                                sharedPreparationTransform(motion)
                            } else {
                                null
                            }
                            val transform = preparationTransform ?: pageTransforms(
                                motion = motion,
                                progress = frameProgress,
                                directionalBack = usesDirectionalBackPivot,
                                backDirection = state.dragBackDirection,
                                viewportWidth = screenWidthPx,
                                viewportHeight = screenHeightPx,
                                hostPositionInWindow = hostPositionInWindow,
                                sharedPair = preparedSharedPair,
                            )
                            translationX = transform.currentTranslationX
                            translationY = transform.currentTranslationY
                            scaleX = transform.currentScale
                            scaleY = transform.currentScale
                            alpha = transform.currentAlpha
                            compositingStrategy = CompositingStrategy.Auto
                            transformOrigin = transform.currentTransformOrigin
                        }
                ) {
                    CompositionLocalProvider(
                        LocalSceneBackgroundFrozen provides freezeRetainedVirtualListDynamics,
                        LocalRetainedSceneMotionActive provides retainedVirtualListPivot,
                        LocalRetainedSceneItemTransform provides currentSceneItemTransform,
                    ) {
                        SceneContent(displayedScene)
                    }
                }

            } else {
                CompositionLocalProvider(
                    LocalRetainedSceneItemTransform provides null,
                    // Preflight only transfers input/scroll ownership. Do not switch the current
                    // holder population into visual scene-motion mode before the animation exists.
                    LocalRetainedSceneMotionActive provides false,
                    LocalRetainedScenePreflightOwnership provides preparingRetainedVirtualListMotion,
                ) {
                    SceneContent(displayedScene)
                }

                // Retain exactly one fully measured spare scene alongside the two-slot
                // list host. It stays outside drawing/input while remaining ready for a smooth
                // reverse transition. A newly requested scene temporarily occupies this slot
                // during its preparation frame.
                val spareScene = preparingScene ?: retainedScene
                // Library scenes already have a mounted controller in the fixed slot above,
                // including while Settings covers that slot. Never warm them as a generic
                // full-page spare: HOME would use its fallback scroll layout outside the library
                // locals, creating a second header/carousel and a different viewport at return.
                val spareIsHomeCategoryFullScene = spareScene != null &&
                    isPersistentHomeCategoryScene(spareScene)
                if (
                    spareScene != null &&
                    spareScene != displayedScene &&
                    !spareIsHomeCategoryFullScene
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val isPreparing = preparingScene != null
                                // Compose still composes and measures an alpha-zero layer, but
                                // the renderer skips its pixels. This warms layout without the
                                // full-screen Offscreen buffer that caused cold-entry jank.
                                alpha = 0f
                                translationX = if (isPreparing) {
                                    0f
                                } else {
                                    screenWidthPx.coerceAtLeast(1f) * 2f
                                }
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            }
                    ) {
                        val prepareItemTransform = if (
                            preparingScene != null &&
                            spareScene in homeCategoryTransitionScenes &&
                            usesHomeCategoryMotion(displayedScene, spareScene)
                        ) {
                            RetainedSceneItemTransform(
                                pivotX = screenWidthPx * 0.5f,
                                pivotY = screenHeightPx * 0.5f,
                                scaleProvider = { 1f },
                            )
                        } else {
                            null
                        }
                        CompositionLocalProvider(
                            LocalSceneBackgroundFrozen provides true,
                            LocalRetainedSceneItemTransform provides prepareItemTransform,
                            // Hidden preparation is alpha=0. Reference ArtworkItemNode.setAlpha(0)
                            // disables MarqueeTextNode until the holder becomes visible.
                            LongTextMotionState.LocalListMarqueeVisibility provides false,
                        ) {
                            SceneContent(spareScene)
                        }
                    }
                }
            }

        }
    }
}

private fun isPersistentHomeCategoryScene(scene: NavScene): Boolean =
    isReferenceLibraryScene(scene)

internal fun referenceGenericPivotScale(
    motion: PageMotion,
    progress: Float,
    sourceLayout: Boolean,
): Float {
    val p = progress.coerceIn(0f, 1f)
    return when (motion) {
        PageMotion.HomeCategoryForward,
        PageMotion.Generic -> resolveReferenceGenericPivotScale(
            isBack = false,
            retainedLayout = sourceLayout,
            elapsed = 1f - p,
        )
        PageMotion.FolderSharedForward -> {
            val elapsed = 1f - p
            if (sourceLayout) 1f + 0.5f * elapsed else 0.5f + 0.5f * elapsed
        }
        PageMotion.HomeCategoryBack -> resolveReferenceGenericPivotScale(
            isBack = true,
            retainedLayout = sourceLayout,
            elapsed = p,
        )
        // HeaderToItem: in Raw's back frame the retained slot is the destination list and the
        // current slot is the disappearing detail/header source.
        PageMotion.FolderSharedBack -> if (sourceLayout) {
            1.5f - 0.5f * p
        } else {
            1f - 0.5f * p
        }
        else -> 1f
    }
}

internal fun referenceGenericPivotAlpha(
    motion: PageMotion,
    progress: Float,
    sourceLayout: Boolean,
): Float {
    val p = progress.coerceIn(0f, 1f)
    return when (motion) {
        PageMotion.HomeCategoryForward,
        PageMotion.FolderSharedForward,
        PageMotion.Generic -> {
            val elapsed = 1f - p
            if (sourceLayout) lerp(1f, 0f, elapsed) else lerp(0f, 1f, elapsed)
        }
        PageMotion.HomeCategoryBack,
        PageMotion.FolderSharedBack -> {
            if (sourceLayout) lerp(0f, 1f, p) else lerp(1f, 0f, p)
        }
        else -> 1f
    }.coerceIn(0f, 1f)
}

private data class PageTransform(
    val fromScale: Float,
    val fromAlpha: Float,
    val fromTranslationX: Float,
    val fromTranslationY: Float = 0f,
    val fromTransformOrigin: TransformOrigin = TransformOrigin.Center,
    val currentScale: Float,
    val currentAlpha: Float,
    val currentTranslationX: Float,
    val currentTranslationY: Float = 0f,
    val currentTransformOrigin: TransformOrigin = TransformOrigin.Center,
)

private fun pageTransforms(
    motion: PageMotion,
    progress: Float,
    directionalBack: Boolean,
    backDirection: Float,
    viewportWidth: Float,
    viewportHeight: Float,
    hostPositionInWindow: Offset,
    sharedPair: Pair<SharedCoverSnapshot, SharedCoverSnapshot>?,
): PageTransform {
    val directionalPivot = if (backDirection >= 0f) {
        TransformOrigin(1.5f, 0.5f)
    } else {
        TransformOrigin(-0.5f, 0.5f)
    }
    return when (motion) {
        PageMotion.HomeCategoryForward -> {
            val elapsed = 1f - progress
            PageTransform(
                // reference player GenericPivot forward: visible CURRENT/source expands 1 -> 1.5 while
                // prepared NEXT/destination comes from 0.5 -> 1.
                fromScale = resolveReferenceGenericPivotScale(false, true, elapsed),
                fromAlpha = lerp(1f, 0f, elapsed),
                fromTranslationX = 0f,
                currentScale = resolveReferenceGenericPivotScale(false, false, elapsed),
                currentAlpha = lerp(0f, 1f, elapsed),
                currentTranslationX = 0f,
            )
        }

        PageMotion.HomeCategoryBack -> {
            val elapsed = progress
            PageTransform(
                fromScale = resolveReferenceGenericPivotScale(true, true, elapsed),
                fromAlpha = lerp(0f, 1f, elapsed),
                fromTranslationX = 0f,
                fromTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
                currentScale = resolveReferenceGenericPivotScale(true, false, elapsed),
                currentAlpha = lerp(1f, 0f, elapsed),
                currentTranslationX = 0f,
                currentTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
            )
        }

        PageMotion.SettingsForward -> {
            val elapsed = 1f - progress
            PageTransform(
                fromScale = lerp(1f, VIRTUAL_LIST_SCALE_MAX, elapsed),
                fromAlpha = lerp(1f, 0f, elapsed),
                fromTranslationX = 0f,
                currentScale = lerp(VIRTUAL_LIST_SCALE_MIN, 1f, elapsed),
                currentAlpha = lerp(0f, 1f, elapsed),
                currentTranslationX = 0f,
            )
        }

        PageMotion.FolderSharedForward -> {
            val elapsed = 1f - progress
            val pair = sharedPair
            val sourcePivot = pair?.first?.toVirtualListItemPivot(
                hostPositionInWindow = hostPositionInWindow,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                horizontalOffsetFraction = 0f,
            ) ?: TransformOrigin.Center
            PageTransform(
                // ItemToHeader: CURRENT source item expands around its own bottom edge while the
                // NEXT detail/header arrives from the half-scale viewport-centred LayoutRes.
                fromScale = lerp(1f, VIRTUAL_LIST_SCALE_MAX, elapsed),
                fromAlpha = lerp(1f, 0f, elapsed),
                fromTranslationX = 0f,
                fromTransformOrigin = sourcePivot,
                currentScale = lerp(VIRTUAL_LIST_SCALE_MIN, 1f, elapsed),
                currentAlpha = lerp(0f, 1f, elapsed),
                currentTranslationX = 0f,
                currentTransformOrigin = TransformOrigin.Center,
            )
        }

        PageMotion.FolderSharedBack -> {
            val elapsed = progress
            val pair = sharedPair
            val horizontalPivotOffset = if (directionalBack) {
                if (backDirection >= 0f) 1f else -1f
            } else {
                0f
            }
            val targetPivot = pair?.second?.toVirtualListItemPivot(
                hostPositionInWindow = hostPositionInWindow,
                viewportWidth = viewportWidth,
                viewportHeight = viewportHeight,
                horizontalOffsetFraction = horizontalPivotOffset,
            ) ?: TransformOrigin(
                pivotFractionX = 0.5f + horizontalPivotOffset,
                pivotFractionY = 0.5f,
            )
            val headerPivot = TransformOrigin(
                pivotFractionX = 0.5f + horizontalPivotOffset,
                pivotFractionY = 0.5f,
            )
            PageTransform(
                // Header -> item uses the same two layout engines in reverse. The destination
                // list is centred on the exact target item, not on a copied screen coordinate.
                fromScale = lerp(VIRTUAL_LIST_SCALE_MAX, 1f, elapsed),
                fromAlpha = lerp(0f, 1f, elapsed),
                fromTranslationX = 0f,
                fromTransformOrigin = targetPivot,
                currentScale = lerp(1f, VIRTUAL_LIST_SCALE_MIN, elapsed),
                currentAlpha = lerp(1f, 0f, elapsed),
                currentTranslationX = 0f,
                currentTransformOrigin = headerPivot,
            )
        }

        PageMotion.SettingsBack -> {
            val elapsed = progress
            PageTransform(
                fromScale = lerp(VIRTUAL_LIST_SCALE_MAX, 1f, elapsed),
                fromAlpha = lerp(0f, 1f, elapsed),
                fromTranslationX = 0f,
                fromTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
                currentScale = lerp(1f, VIRTUAL_LIST_SCALE_MIN, elapsed),
                currentAlpha = lerp(1f, 0f, elapsed),
                currentTranslationX = 0f,
                currentTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
            )
        }

        PageMotion.Generic -> PageTransform(
            fromScale = lerp(VIRTUAL_LIST_SCALE_MAX, 1f, progress),
            fromAlpha = progress,
            fromTranslationX = 0f,
            fromTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
            currentScale = lerp(1f, VIRTUAL_LIST_SCALE_MIN, progress),
            currentAlpha = 1f - progress,
            currentTranslationX = 0f,
            currentTransformOrigin = if (directionalBack) directionalPivot else TransformOrigin.Center,
        )

    }
}

private fun sharedPreparationTransform(motion: PageMotion): PageTransform? {
    return when (motion) {
        PageMotion.FolderSharedForward -> PageTransform(
            fromScale = 1f,
            fromAlpha = 1f,
            fromTranslationX = 0f,
            currentScale = 1f,
            currentAlpha = 0f,
            currentTranslationX = 0f,
        )
        PageMotion.FolderSharedBack -> PageTransform(
            fromScale = 1f,
            fromAlpha = 0f,
            fromTranslationX = 0f,
            currentScale = 1f,
            currentAlpha = 1f,
            currentTranslationX = 0f,
        )
        else -> null
    }
}

private fun SharedCoverSnapshot.virtualListItemBottomInHost(
    hostPositionInWindow: Offset,
): Float = itemViewportBounds?.bottom ?: (boundsInWindow.bottom - hostPositionInWindow.y)

private fun SharedCoverSnapshot.toVirtualListItemPivot(
    hostPositionInWindow: Offset,
    viewportWidth: Float,
    viewportHeight: Float,
    horizontalOffsetFraction: Float,
): TransformOrigin {
    // VirtualList's native layout engine uses viewportCenter +
    // (-viewportHeight / 2 + itemTop + itemHeight), which resolves to item.bottom.
    // The reference transition reads the full migrated item-holder y+height; use Raw's captured full
    // holder LayoutRes when available. boundsInWindow is only the artwork rect and is therefore a
    // fallback for non-physical callers, not the canonical collection pivot.
    // Its swipe variant shifts the horizontal pivot by exactly one viewport width.
    val localBottom = virtualListItemBottomInHost(hostPositionInWindow)
    return TransformOrigin(
        pivotFractionX = 0.5f + horizontalOffsetFraction,
        pivotFractionY = localBottom / viewportHeight.coerceAtLeast(1f),
    )
}

/**
 * 共享封面 overlay。
 * 在 SceneTransitionHost 根层绘制，不受来源页/目标页的 alpha 影响。
 * 从 SharedCoverRegistry 读取两端 bounds/radius，插值后用 CrossfadeAlbumArt 渲染。
 */
@Composable
internal fun SharedCoverOverlay(
    registry: SharedCoverRegistry,
    spec: SharedTransitionSpec,
    overlayOriginInWindow: Offset,
) {
    if (!spec.active) return

    val density = LocalDensity.current

    Box(
        modifier = Modifier.fillMaxSize()
    ) {
        val preparedPair = registry.getPreparedPair(spec.transitionKey) ?: return@Box
        with(preparedPair) {
            val from = first
            val to = second

            val fromRect = from.boundsInWindow
            val toRect = to.boundsInWindow
            val sourceSidePx = fromRect.width.coerceAtLeast(1f)
            val targetSidePx = toRect.width.coerceAtLeast(1f)
            val sourceCenterX = (fromRect.left + fromRect.right) * 0.5f
            val sourceCenterY = (fromRect.top + fromRect.bottom) * 0.5f
            val targetCenterX = (toRect.left + toRect.right) * 0.5f
            val targetCenterY = (toRect.top + toRect.bottom) * 0.5f
            val coverKey = from.coverKey.ifBlank { to.coverKey }
            if (coverKey.isBlank()) return@with
            // Prefer the exact bitmap promoted from the clicked physical holder. reference player moves
            // that holder/View itself into the shared transition, so its already-presented artwork
            // can never briefly regress to a placeholder while a second provider lookup catches up.
            val holderPromotedBitmap = remember(
                spec.transitionKey,
                from.promotedBitmap,
                to.promotedBitmap,
            ) {
                from.promotedBitmap?.takeIf { !it.isRecycled }
                    ?: to.promotedBitmap?.takeIf { !it.isRecycled }
            }
            // The physical VirtualList holder is provider-backed. Promote that exact already-owned
            // wrapper only when the click-time holder did not expose concrete pixels (legacy Compose
            // endpoints, for example). This is now a compatibility fallback, not the primary owner.
            val promotedHandle = remember(
                spec.transitionKey,
                coverKey,
                sourceSidePx.roundToInt(),
                holderPromotedBitmap,
            ) {
                if (holderPromotedBitmap != null) {
                    null
                } else {
                    BitmapProvider.acquireBestThumbnail(
                        key = coverKey,
                        targetWidth = sourceSidePx.roundToInt().coerceAtLeast(1),
                        targetHeight = sourceSidePx.roundToInt().coerceAtLeast(1),
                        surface = ArtworkSurface.List,
                        aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                    ) ?: BitmapProvider.acquireAny(
                        key = coverKey,
                        surface = ArtworkSurface.List,
                        minimumSide = 1,
                        aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                    ) ?: BitmapProvider.acquireAny(
                        key = coverKey,
                        surface = ArtworkSurface.List,
                        minimumSide = 1,
                        aspectPolicy = ArtworkAspectPolicy.Crop,
                    )
                }
            }
            DisposableEffect(promotedHandle) {
                onDispose { promotedHandle?.release() }
            }
            val promotedImage = remember(holderPromotedBitmap, promotedHandle) {
                (holderPromotedBitmap
                    ?: promotedHandle?.takeIf { it.isValid }?.bitmap)
                    ?.takeIf { !it.isRecycled }
                    ?.asImageBitmap()
            }
            var fallbackPainterReady by remember(spec.transitionKey, coverKey) { mutableStateOf(false) }
            LaunchedEffect(spec.transitionKey, promotedImage, fallbackPainterReady) {
                if (promotedImage == null && !fallbackPainterReady) return@LaunchedEffect
                // Do not hide the physical holder in the same composition that mounts the actor.
                // One frame boundary guarantees the promoted/default painter has had a drawable pass.
                withFrameNanos { }
                if (registry.getPreparedPair(spec.transitionKey) != null) {
                    registry.markOverlayReady(spec.transitionKey)
                }
            }
            val imageModifier = Modifier
                .requiredSize(
                    width = with(density) { sourceSidePx.toDp() },
                    height = with(density) { sourceSidePx.toDp() }
                )
                .graphicsLayer {
                    val progress = (spec.progressProvider?.invoke() ?: spec.progress)
                        .coerceIn(0f, 1f)
                    // When reference player's shared holder owns both CURRENT/NEXT `s` records,
                    // The retained-layout interpolation path bypasses the normal layout engine and interpolates those two
                    // holder records directly. GenericPivot/ItemToHeader/HeaderToItem therefore
                    // belongs only to the surrounding population; applying it again to the shared
                    // actor double-transforms the cover and loses the real swipe geometry.
                    val currentCenterX = lerp(sourceCenterX, targetCenterX, progress)
                    val currentCenterY = lerp(sourceCenterY, targetCenterY, progress)
                    val currentSidePx = lerp(sourceSidePx, targetSidePx, progress).coerceAtLeast(1f)
                    val uniformScale = currentSidePx / sourceSidePx
                    val radiusDp = lerp(from.radiusDp, to.radiusDp, progress)

                    this.translationX = currentCenterX - overlayOriginInWindow.x - sourceSidePx * 0.5f
                    this.translationY = currentCenterY - overlayOriginInWindow.y - sourceSidePx * 0.5f
                    scaleX = uniformScale
                    scaleY = uniformScale
                    transformOrigin = TransformOrigin.Center
                    alpha = 1f
                    clip = true
                    shape = RoundedCornerShape((radiusDp / uniformScale.coerceAtLeast(0.001f)).dp)
                }

            if (promotedImage != null) {
                Image(
                    bitmap = promotedImage,
                    contentDescription = null,
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit,
                    filterQuality = FilterQuality.High
                )
            } else {
                BitmapImage(
                    key = coverKey,
                    contentDescription = null,
                    modifier = imageModifier,
                    contentScale = ContentScale.Fit,
                    targetWidth = sourceSidePx.roundToInt().coerceAtLeast(1),
                    targetHeight = sourceSidePx.roundToInt().coerceAtLeast(1),
                    surface = ArtworkSurface.List,
                    aspectPolicy = ArtworkAspectPolicy.KeepAspect,
                    fadeInMillis = 0,
                    holdPreviousOnKeyChange = false,
                    fadeOnBitmapChange = false,
                    freezeBitmapUpdates = true,
                    // A shared actor must never invent a placeholder that the source holder was not
                    // showing. Real no-art Node holders promote their already-drawn fallback bitmap;
                    // legacy paths simply keep their physical endpoint visible until real pixels exist.
                    showDefaultArtwork = false,
                    onSuccess = { fallbackPainterReady = true },
                )
            }
        }
    }
}

private object FragmentSceneEasing : Easing {
    override fun transform(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        val segment = if (x <= 0.166666f) firstSegment else secondSegment
        var low = 0f
        var high = 1f
        repeat(14) {
            val mid = (low + high) * 0.5f
            if (cubic(segment.x0, segment.x1, segment.x2, segment.x3, mid) < x) {
                low = mid
            } else {
                high = mid
            }
        }
        val t = (low + high) * 0.5f
        return cubic(segment.y0, segment.y1, segment.y2, segment.y3, t)
    }

    private val firstSegment = CubicPathSegment(
        x0 = 0f,
        y0 = 0f,
        x1 = 0.05f,
        y1 = 0f,
        x2 = 0.133333f,
        y2 = 0.06f,
        x3 = 0.166666f,
        y3 = 0.4f,
    )

    private val secondSegment = CubicPathSegment(
        x0 = 0.166666f,
        y0 = 0.4f,
        x1 = 0.208333f,
        y1 = 0.82f,
        x2 = 0.25f,
        y2 = 1f,
        x3 = 1f,
        y3 = 1f,
    )
}

private data class CubicPathSegment(
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
    val x2: Float,
    val y2: Float,
    val x3: Float,
    val y3: Float,
)

private fun cubic(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
    val oneMinusT = 1f - t
    return oneMinusT * oneMinusT * oneMinusT * p0 +
        3f * oneMinusT * oneMinusT * t * p1 +
        3f * oneMinusT * t * t * p2 +
        t * t * t * p3
}

internal object VirtualListAccelerateDecelerate : Easing {
    override fun transform(fraction: Float): Float {
        val x = fraction.coerceIn(0f, 1f)
        return (cos((x + 1f) * Math.PI).toFloat() * 0.5f) + 0.5f
    }
}
