package com.rawsmusic.core.ui.scene

import android.graphics.Bitmap
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.geometry.Rect
import com.rawsmusic.core.common.utils.AppLogger

/**
 * Shared collection headers use a 16dp corner radius so retained artwork and destination
 * artwork have the same rounded outline throughout the transition.
 */
internal const val COLLECTION_HEADER_RADIUS_DP = 16f

/**
 * 共享元素过渡规格。
 *
 * progress 统一定义为 from -> to 的 0..1。
 */
data class SharedTransitionSpec(
    val active: Boolean = false,
    val fromSceneId: String = "",
    val toSceneId: String = "",
    val activeSceneId: String = "",
    val progress: Float = 0f,
    /**
     * Optional draw/layer-time transition clock.
     *
     * VirtualList shared-item motion mutates the already-attached LayoutRes/View properties; it does
     * not publish a new adapter/page state on every vsync.  Raw therefore keeps the scalar outside
     * composition whenever a renderer can sample it directly. [progress] remains as the static
     * fallback for non-animated callers/tests.
     */
    val progressProvider: (() -> Float)? = null,
    /**
     * Pivot of the detail/header provider LayoutRes in host-window pixels. Header child motion
     * uses the exact same pivot to invert the parent GenericPivot affine, including directional
     * HeaderToItem gestures whose horizontal pivot sits outside the viewport.
     */
    val detailPivotX: Float = Float.NaN,
    val detailPivotY: Float = Float.NaN,
    /** Separate layout displacement; never fed into cover-size interpolation. */
    val originOverscrollXProvider: (() -> Float)? = null,
    val transitionKey: String = "",
    val allowRememberedTarget: Boolean = false,
    // Physical shared artwork owner. When non-blank the real source/target holder with this
    // element id may stop drawing its normal artwork lane only if the registry also reports that
    // the concrete promoted holder is live. A prepared pair alone is not ownership in retained-view implementation.
    val ownedElementId: String = "",
    // The overlay owns the cover bounds during a prepared shared transition. The
    // corresponding VirtualList item must remain at its physical slot to avoid a
    // second affine transform fighting the shared cover interpolation.
    val sharedCoverOverlayOwnsAnchor: Boolean = false
) {
    fun ownsElement(sceneId: String, elementId: String): Boolean {
        if (!active || ownedElementId.isBlank() || elementId.isBlank()) return false
        return elementId == ownedElementId && (sceneId == fromSceneId || sceneId == toSceneId)
    }

    fun shouldTrackScene(sceneId: String): Boolean {
        return if (active) {
            sceneId == fromSceneId || sceneId == toSceneId
        } else {
            sceneId == activeSceneId
        }
    }
}

val LocalSharedTransitionSpec = staticCompositionLocalOf {
    SharedTransitionSpec()
}

/**
 * 共享封面快照。
 * 只服务文件夹大封面 hero 转场；不要拿它做所有页面的封面飞行动画。
 */
data class SharedCoverSnapshot(
    val sceneId: String,
    val elementId: String,
    val boundsInWindow: Rect,
    val coverKey: String,
    val radiusDp: Float,
    /** Source holder child LayoutRes in window coordinates for shared Item->Header text motion. */
    val titleBoundsInWindow: Rect? = null,
    val subtitleBoundsInWindow: Rect? = null,
    val metaBoundsInWindow: Rect? = null,
    /** Adapter/provider index captured from the concrete list holder, when available. */
    val itemIndex: Int = -1,
    /** Full list-item LayoutRes in viewport-local coordinates, used for retained-view implementation return centering. */
    val itemViewportBounds: Rect? = null,
    /** Physical VirtualList slot which owns the concrete Android holder View. */
    val physicalSlotId: Int = -1,
    /** Exact persistent-VirtualList viewport-local LayoutRes for the physical holder path. */
    val physicalViewportBounds: Rect? = null,
    /**
     * Exact pixels promoted from the already-visible holder, when that holder owns a concrete
     * bitmap. retained-view implementation promotes the real attached View between list/header LayoutRes records rather
     * than resolving artwork again from an id; carrying the currently-presented bitmap is Raw's
     * closest ownership equivalent for the retained Node renderer.
     */
    val promotedBitmap: Bitmap? = null,
    /** Whether this concrete holder is currently eligible to start a shared transition. */
    val sharedEligible: Boolean = true,
    val layoutColumns: Int = 1,
)

@Stable
class SharedCoverRegistry {
    private data class LiveEntry(
        val owner: Any,
        val snapshot: SharedCoverSnapshot,
        val resolve: ((SharedCoverSnapshot) -> SharedCoverSnapshot)? = null,
    ) {
        fun currentSnapshot(): SharedCoverSnapshot = resolve?.invoke(snapshot) ?: snapshot
    }

    private data class CollectionSession(
        val elementId: String,
        val listSceneId: String,
        val detailSceneId: String,
        val listEndpoint: SharedCoverSnapshot,
        val headerEndpoint: SharedCoverSnapshot
    )

    private val live = mutableMapOf<String, LiveEntry>()
    private val physicalLayout = mutableMapOf<String, LiveEntry>()
    private val synchronousSceneHeaderOwner = Any()
    private var requestedSource: SharedCoverSnapshot? = null
    private var collectionSession: CollectionSession? = null
    // A detail page can itself own an ItemToHeader child (artist detail -> all songs). Keep the
    // earlier concrete list/header transaction parked until that child returns, otherwise the
    // next HeaderToItem has no original holder to migrate back to the artists list.
    private val nestedCollectionParents = mutableListOf<CollectionSession>()
    private var preparedTransitionKey: String by mutableStateOf("")
    private var ephemeralPrepared = false
    private var preparedPair: Pair<SharedCoverSnapshot, SharedCoverSnapshot>? by mutableStateOf(null)
    // A prepared geometry pair is not enough to hide the physical holder. The shared actor must
    // have produced at least one drawable raster frame first, otherwise the holder disappears while
    // a cold artwork request is still empty. VirtualList shared-item transitions hand ownership to a
    // concrete promoted View; this latch is the Compose equivalent of that drawable-owner boundary.
    private var overlayReadyTransitionKey: String by mutableStateOf("")
    private var physicalPromotedElementId: String by mutableStateOf("")
    private var physicalPromotedSceneId: String = ""
    private var physicalEndpointListenerOwner: Any? = null
    private var physicalEndpointListener: ((SharedCoverSnapshot) -> Unit)? = null
    private var collectionReturnTargetPreparerOwner: Any? = null
    private var collectionReturnTargetPreparer: ((CollectionSessionSnapshot) -> SharedCoverSnapshot?)? = null
    private var physicalReturnFinalizerOwner: Any? = null
    private var physicalReturnFinalizer: ((String) -> Boolean)? = null
    private var physicalPromotionCancellerOwner: Any? = null
    private var physicalPromotionCanceller: ((String) -> Boolean)? = null
    private var physicalPromotionLivenessOwner: Any? = null
    private var physicalPromotionLiveness: ((String) -> Boolean)? = null
    private var physicalPromotionStarterOwner: Any? = null
    private var physicalPromotionStarter: ((SharedCoverSnapshot, SharedCoverSnapshot, String) -> Boolean)? = null
    private var physicalPromotionStasherOwner: Any? = null
    private var physicalPromotionStasher: ((String) -> Boolean)? = null
    private var physicalPromotionActivatorOwner: Any? = null
    private var physicalPromotionActivator: ((String, SharedCoverSnapshot, String) -> Boolean)? = null
    private var physicalPromotionStashClearerOwner: Any? = null
    private var physicalPromotionStashClearer: (() -> Int)? = null
    private var physicalRoleEndpointResolverOwner: Any? = null
    private var physicalRoleEndpointResolver: ((String, String, Boolean) -> SharedCoverSnapshot?)? = null
    private val physicallyStashedElements = linkedSetOf<String>()
    private var physicalPromotionActivatedFromStash = false

    internal data class CollectionSessionSnapshot(
        val elementId: String,
        val listSceneId: String,
        val detailSceneId: String,
        val listEndpoint: SharedCoverSnapshot,
        val headerEndpoint: SharedCoverSnapshot,
    )

    val elements: Map<String, SharedCoverSnapshot>
        get() = live.mapValues { it.value.snapshot }

    private fun key(sceneId: String, elementId: String): String {
        return "$sceneId::$elementId"
    }

    fun register(
        sceneId: String,
        elementId: String,
        owner: Any,
        snapshot: SharedCoverSnapshot
    ) {
        if (sceneId.isBlank() || elementId.isBlank()) return
        val key = key(sceneId, elementId)
        live[key] = LiveEntry(owner = owner, snapshot = snapshot)
        val session = collectionSession
        if (
            session != null &&
            sceneId == session.detailSceneId &&
            elementId == session.elementId &&
            snapshot.coverKey.isNotBlank() &&
            snapshot.coverKey != session.headerEndpoint.coverKey
        ) {
            // Detail artwork can legitimately change while the page remains mounted (artist-image
            // preview/override is one example). Keep the retained collection session bound to the
            // live header identity so a later HeaderToItem return promotes the pixels the user is
            // actually looking at instead of resurrecting the entry-time cover key.
            collectionSession = session.copy(
                headerEndpoint = session.headerEndpoint.copy(coverKey = snapshot.coverKey),
            )
        }
        if (isPhysicalPromotedElement(elementId)) {
            physicalEndpointListener?.invoke(snapshot)
        }
    }

    internal fun attachPhysicalEndpointListener(
        owner: Any,
        listener: (SharedCoverSnapshot) -> Unit,
    ) {
        physicalEndpointListenerOwner = owner
        physicalEndpointListener = listener
    }

    internal fun detachPhysicalEndpointListener(owner: Any) {
        if (physicalEndpointListenerOwner !== owner) return
        physicalEndpointListenerOwner = null
        physicalEndpointListener = null
    }

    internal fun attachCollectionReturnTargetPreparer(
        owner: Any,
        preparer: (CollectionSessionSnapshot) -> SharedCoverSnapshot?,
    ) {
        collectionReturnTargetPreparerOwner = owner
        collectionReturnTargetPreparer = preparer
    }

    internal fun detachCollectionReturnTargetPreparer(owner: Any) {
        if (collectionReturnTargetPreparerOwner !== owner) return
        collectionReturnTargetPreparerOwner = null
        collectionReturnTargetPreparer = null
    }

    internal fun attachPhysicalReturnFinalizer(
        owner: Any,
        finalizer: (String) -> Boolean,
    ) {
        physicalReturnFinalizerOwner = owner
        physicalReturnFinalizer = finalizer
    }

    internal fun detachPhysicalReturnFinalizer(owner: Any) {
        if (physicalReturnFinalizerOwner !== owner) return
        physicalReturnFinalizerOwner = null
        physicalReturnFinalizer = null
    }

    internal fun attachPhysicalPromotionCanceller(
        owner: Any,
        canceller: (String) -> Boolean,
    ) {
        physicalPromotionCancellerOwner = owner
        physicalPromotionCanceller = canceller
    }

    internal fun detachPhysicalPromotionCanceller(owner: Any) {
        if (physicalPromotionCancellerOwner !== owner) return
        physicalPromotionCancellerOwner = null
        physicalPromotionCanceller = null
    }

    internal fun attachPhysicalPromotionLiveness(
        owner: Any,
        liveness: (String) -> Boolean,
    ) {
        physicalPromotionLivenessOwner = owner
        physicalPromotionLiveness = liveness
    }

    internal fun detachPhysicalPromotionLiveness(owner: Any) {
        if (physicalPromotionLivenessOwner !== owner) return
        physicalPromotionLivenessOwner = null
        physicalPromotionLiveness = null
        // The checker belongs to the persistent Android presentation runtime which owns the
        // promoted concrete View. Once that runtime detaches, the physical actor cannot still be a
        // valid pixel owner even if the collection geometry/session remains useful for a fallback
        // return. Drop only physical ownership; keep the remembered endpoints intact.
        physicalPromotedElementId = ""
        physicalPromotedSceneId = ""
        physicallyStashedElements.clear()
        nestedCollectionParents.clear()
        physicalPromotionActivatedFromStash = false
    }

    internal fun attachPhysicalPromotionStarter(
        owner: Any,
        starter: (SharedCoverSnapshot, SharedCoverSnapshot, String) -> Boolean,
    ) {
        physicalPromotionStarterOwner = owner
        physicalPromotionStarter = starter
    }

    internal fun detachPhysicalPromotionStarter(owner: Any) {
        if (physicalPromotionStarterOwner !== owner) return
        physicalPromotionStarterOwner = null
        physicalPromotionStarter = null
    }

    internal fun attachPhysicalPromotionStasher(owner: Any, stasher: (String) -> Boolean) {
        physicalPromotionStasherOwner = owner
        physicalPromotionStasher = stasher
    }

    internal fun detachPhysicalPromotionStasher(owner: Any) {
        if (physicalPromotionStasherOwner !== owner) return
        physicalPromotionStasherOwner = null
        physicalPromotionStasher = null
    }

    internal fun attachPhysicalPromotionActivator(
        owner: Any,
        activator: (String, SharedCoverSnapshot, String) -> Boolean,
    ) {
        physicalPromotionActivatorOwner = owner
        physicalPromotionActivator = activator
    }

    internal fun detachPhysicalPromotionActivator(owner: Any) {
        if (physicalPromotionActivatorOwner !== owner) return
        physicalPromotionActivatorOwner = null
        physicalPromotionActivator = null
    }

    internal fun attachPhysicalPromotionStashClearer(owner: Any, clearer: () -> Int) {
        physicalPromotionStashClearerOwner = owner
        physicalPromotionStashClearer = clearer
    }

    internal fun detachPhysicalPromotionStashClearer(owner: Any) {
        if (physicalPromotionStashClearerOwner !== owner) return
        physicalPromotionStashClearerOwner = null
        physicalPromotionStashClearer = null
    }

    internal fun attachPhysicalRoleEndpointResolver(
        owner: Any,
        resolver: (String, String, Boolean) -> SharedCoverSnapshot?,
    ) {
        physicalRoleEndpointResolverOwner = owner
        physicalRoleEndpointResolver = resolver
    }

    internal fun detachPhysicalRoleEndpointResolver(owner: Any) {
        if (physicalRoleEndpointResolverOwner !== owner) return
        physicalRoleEndpointResolverOwner = null
        physicalRoleEndpointResolver = null
    }

    internal fun resolvePhysicalRoleEndpoint(
        sceneId: String,
        elementId: String,
        retainedRole: Boolean,
    ): SharedCoverSnapshot? = physicalRoleEndpointResolver?.invoke(sceneId, elementId, retainedRole)

    fun unregister(sceneId: String, elementId: String, owner: Any) {
        val key = key(sceneId, elementId)
        if (live[key]?.owner === owner) live.remove(key)
    }

    internal fun registerPhysicalLayoutEndpoint(
        sceneId: String,
        elementId: String,
        owner: Any,
        snapshot: SharedCoverSnapshot,
        resolve: ((SharedCoverSnapshot) -> SharedCoverSnapshot)? = null,
    ) {
        if (sceneId.isBlank() || elementId.isBlank()) return
        physicalLayout[key(sceneId, elementId)] = LiveEntry(owner, snapshot, resolve)
        if (isPhysicalPromotedElement(elementId)) {
            physicalEndpointListener?.invoke(snapshot)
        }
    }

    internal fun registerSynchronousSceneHeaderEndpoint(
        sceneId: String,
        elementId: String,
        widthPx: Int,
        sceneItemHeightPx: Int = widthPx,
        viewportLeftPx: Int = 0,
        viewportTopPx: Int = 0,
        windowLeftPx: Int = viewportLeftPx,
        windowTopPx: Int = viewportTopPx,
    ) {
        if (sceneId.isBlank() || elementId.isBlank()) return
        if (widthPx <= 0 || sceneItemHeightPx <= 0) return
        val viewportBounds = Rect(
            left = viewportLeftPx.toFloat(),
            top = viewportTopPx.toFloat(),
            right = (viewportLeftPx + widthPx).toFloat(),
            bottom = (viewportTopPx + sceneItemHeightPx).toFloat(),
        )
        val windowBounds = Rect(
            left = windowLeftPx.toFloat(),
            top = windowTopPx.toFloat(),
            right = (windowLeftPx + widthPx).toFloat(),
            bottom = (windowTopPx + sceneItemHeightPx).toFloat(),
        )
        physicalLayout[key(sceneId, elementId)] = LiveEntry(
            owner = synchronousSceneHeaderOwner,
            snapshot = SharedCoverSnapshot(
                sceneId = sceneId,
                elementId = elementId,
                boundsInWindow = windowBounds,
                coverKey = "",
                radiusDp = COLLECTION_HEADER_RADIUS_DP,
                sharedEligible = true,
                physicalViewportBounds = viewportBounds,
            ),
        )
    }

    internal fun unregisterPhysicalLayoutEndpoint(sceneId: String, elementId: String, owner: Any) {
        val endpointKey = key(sceneId, elementId)
        if (physicalLayout[endpointKey]?.owner === owner) physicalLayout.remove(endpointKey)
    }

    fun get(sceneId: String, elementId: String): SharedCoverSnapshot? {
        return live[key(sceneId, elementId)]?.snapshot
    }

    fun freeze(sceneId: String, elementId: String): Boolean {
        ephemeralPrepared = false
        // A list click starts a new holder-promotion transaction. Never let a missing or not-yet
        // measured holder silently reuse the previous collection session: that makes item B
        // return through item A's artwork. A detail-header freeze is the reverse half of the
        // existing transaction and therefore keeps its matching session.
        val session = collectionSession
        val isMatchingReturn = session != null &&
            sceneId == session.detailSceneId &&
            elementId == session.elementId
        // The settled VirtualList Node does not own one Compose modifier per row anymore. Its
        // pointer owner can promote the exact hit holder immediately before the page click callback
        // runs. Preserve that click-time source when the page then calls freeze(sceneId, elementId).
        val promotedSource = requestedSource?.takeIf {
            it.sceneId == sceneId && it.elementId == elementId
        }
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        requestedSource = null
        if (!isMatchingReturn) collectionSession = null
        val sourceKey = key(sceneId, elementId)
        val liveSource = physicalLayout[sourceKey]?.snapshot ?: live[sourceKey]?.snapshot
        requestedSource = if (isMatchingReturn) {
            eligibleReturnSource(sceneId, elementId)
        } else {
            promotedSource ?: liveSource
        }
        AppLogger.i(
            "SceneHandoff",
            "SHARED_FREEZE scene=$sceneId element=$elementId matchingReturn=$isMatchingReturn " +
                "promoted=${promotedSource != null} physical=${physicalLayout[sourceKey] != null} " +
                "live=${live[sourceKey] != null} sourceSlot=${requestedSource?.physicalSlotId ?: -999} " +
                "ok=${requestedSource != null}",
        )
        return requestedSource != null
    }

    /** Freeze the exact concrete source identity; physical promotion happens only after target prep. */
    fun freezeSnapshot(snapshot: SharedCoverSnapshot) {
        ephemeralPrepared = false
        if (snapshot.sceneId.isBlank() || snapshot.elementId.isBlank()) return
        // A hierarchy can start ItemToHeader for child C while folder B's migrated holder still
        // owns the settled B header. Keep that older concrete header in the retained
        // provider and migrates C independently. Preserve that same-scene identity instead of
        // treating it like an abandoned collection transition and restoring it to an obsolete row.
        val activeElement = physicalPromotedElementId
        val parentSession = collectionSession?.takeIf { session ->
            activeElement == session.elementId &&
                snapshot.sceneId == session.detailSceneId &&
                snapshot.elementId != session.elementId
        }
        val replacingSameSceneHeader = activeElement.isNotBlank() &&
            activeElement != snapshot.elementId &&
            physicalPromotedSceneId == snapshot.sceneId
        if (parentSession != null) {
            if (stashPhysicalPromotion(activeElement)) {
                nestedCollectionParents += parentSession
                AppLogger.i(
                    "SceneHandoff",
                    "SHARED_NESTED_STASH parent=${parentSession.elementId} child=${snapshot.elementId}",
                )
            } else {
                AppLogger.w(
                    "SceneHandoff",
                    "SHARED_NESTED_STASH_MISS parent=${parentSession.elementId} child=${snapshot.elementId}",
                )
                cancelPhysicalPromotion()
            }
        } else if (replacingSameSceneHeader) {
            if (!stashPhysicalPromotion(activeElement)) {
                cancelPhysicalPromotion()
            }
        } else {
            // Ordinary collection/detail remains single-owner: a new transaction first retires the
            // unfinished concrete actor exactly as before.
            cancelPhysicalPromotion()
            if (nestedCollectionParents.isNotEmpty()) {
                // This is a different route, not another child of the parked detail. A stale
                // ancestor may not survive as an invisible physical holder in the new scene.
                physicalPromotionStashClearer?.invoke()
                physicallyStashedElements.clear()
                nestedCollectionParents.clear()
            }
        }
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        collectionSession = null
        requestedSource = snapshot
        AppLogger.i(
            "SceneHandoff",
            "SHARED_FREEZE_SNAPSHOT scene=${snapshot.sceneId} element=${snapshot.elementId} " +
                "slot=${snapshot.physicalSlotId} eligible=${snapshot.sharedEligible} " +
                "item=${snapshot.itemIndex}",
        )
        // ItemToHeader does not detach the newly clicked source yet. Same-scene hierarchy may have
        // just parked an older header above, while ordinary collection navigation has already
        // retired its previous actor. The new child is promoted only after NEXT LayoutRes exists.
    }

    internal fun promotePreparedPhysicalSource(transitionKey: String): Boolean {
        val pair = getPreparedPair(transitionKey) ?: run {
            AppLogger.w("SceneHandoff", "SHARED_PROMOTE_MISS key=$transitionKey reason=no_pair")
            return false
        }
        val source = pair.first
        if (source.elementId.isBlank()) {
            AppLogger.w("SceneHandoff", "SHARED_PROMOTE_MISS key=$transitionKey reason=blank_element")
            return false
        }
        // HeaderToItem reuses the exact View that ItemToHeader already migrated into the header.
        // That header endpoint intentionally has no list physicalSlotId; requiring one here made
        // every reverse transaction drop the shared actor and snap the View back only at commit.
        if (isPhysicalPromotedElement(source.elementId)) {
            AppLogger.i("SceneHandoff", "SHARED_PROMOTE_REUSE key=$transitionKey element=${source.elementId}")
            return true
        }
        // A brand-new ItemToHeader promotion still requires the concrete source list slot.
        if (source.physicalSlotId < 0) {
            AppLogger.w(
                "SceneHandoff",
                "SHARED_PROMOTE_MISS key=$transitionKey reason=no_source_slot element=${source.elementId}",
            )
            return false
        }
        if (physicalPromotedElementId.isNotBlank()) {
            AppLogger.w(
                "SceneHandoff",
                "SHARED_PROMOTE_MISS key=$transitionKey reason=already_promoted current=$physicalPromotedElementId next=${source.elementId}",
            )
            return false
        }
        val promoted = physicalPromotionStarter?.invoke(source, pair.second, transitionKey) == true
        if (promoted) {
            physicalPromotedElementId = source.elementId
            physicalPromotedSceneId = source.sceneId
            physicalPromotionActivatedFromStash = false
        }
        AppLogger.i(
            "SceneHandoff",
            "SHARED_PROMOTE_ATOMIC key=$transitionKey promoted=$promoted element=${source.elementId} " +
                "slot=${source.physicalSlotId} targetScene=${pair.second.sceneId} " +
                "targetPhysical=${physicalLayout[key(pair.second.sceneId, pair.second.elementId)] != null} " +
                "targetLive=${live[key(pair.second.sceneId, pair.second.elementId)] != null}",
        )
        return promoted
    }

    internal fun stashPhysicalPromotion(elementId: String): Boolean {
        if (elementId.isBlank() || physicalPromotedElementId != elementId) return false
        val stashed = physicalPromotionStasher?.invoke(elementId) == true
        if (!stashed) return false
        physicallyStashedElements += elementId
        physicalPromotedElementId = ""
        physicalPromotedSceneId = ""
        physicalPromotionActivatedFromStash = false
        return true
    }

    internal fun activateStashedPhysicalPromotion(elementId: String): Boolean {
        if (elementId.isBlank()) return false
        if (isPhysicalPromotedElement(elementId)) return true
        if (elementId !in physicallyStashedElements || physicalPromotedElementId.isNotBlank()) return false
        val transitionKey = preparedTransitionKey
        val source = getPreparedPair(transitionKey)?.first ?: return false
        val activated = physicalPromotionActivator?.invoke(elementId, source, transitionKey) == true
        if (!activated) return false
        physicallyStashedElements.remove(elementId)
        physicalPromotedElementId = elementId
        physicalPromotedSceneId = source.sceneId
        physicalPromotionActivatedFromStash = true
        return true
    }

    internal fun isPhysicalStashedElement(elementId: String): Boolean =
        elementId.isNotBlank() && elementId in physicallyStashedElements

    private fun isPhysicalPromotionLive(elementId: String): Boolean =
        physicalPromotionLiveness?.invoke(elementId) ?: true

    fun isPhysicalPromotedElement(elementId: String): Boolean =
        elementId.isNotBlank() &&
            physicalPromotedElementId == elementId &&
            isPhysicalPromotionLive(elementId)

    fun isPhysicalPromotionActive(): Boolean =
        physicalPromotedElementId.isNotBlank() && isPhysicalPromotionLive(physicalPromotedElementId)

    internal fun canStartPhysicalSharedClick(sceneId: String): Boolean =
        !isPhysicalPromotionActive() ||
            (sceneId.isNotBlank() && sceneId == physicalPromotedSceneId) ||
            (sceneId.isNotBlank() &&
                collectionSession?.listSceneId == physicalPromotedSceneId &&
                collectionSession?.detailSceneId == sceneId)

    internal fun frozenSourceSnapshot(sceneId: String, elementId: String): SharedCoverSnapshot? =
        requestedSource?.takeIf { it.sceneId == sceneId && it.elementId == elementId }

    fun shouldSuppressPhysicalHero(elementId: String): Boolean =
        isPhysicalPromotedElement(elementId)

    internal fun collectionSessionSnapshot(): CollectionSessionSnapshot? {
        val session = collectionSession ?: return null
        return CollectionSessionSnapshot(
            elementId = session.elementId,
            listSceneId = session.listSceneId,
            detailSceneId = session.detailSceneId,
            listEndpoint = session.listEndpoint,
            headerEndpoint = session.headerEndpoint,
        )
    }

    /**
     * retained-view implementation HeaderToItem repositions the destination provider around the clicked item before it
     * captures the reverse shared target. Let the persistent library owner do the same synchronous
     * provider/state relayout and replace the remembered list endpoint before pair resolution.
     */
    internal fun prepareCollectionReturnTarget(
        fromSceneId: String,
        toSceneId: String,
    ): Boolean {
        val session = collectionSession ?: return false
        if (session.detailSceneId != fromSceneId || session.listSceneId != toSceneId) return false
        val preparer = collectionReturnTargetPreparer ?: return false
        val snapshot = CollectionSessionSnapshot(
            elementId = session.elementId,
            listSceneId = session.listSceneId,
            detailSceneId = session.detailSceneId,
            listEndpoint = session.listEndpoint,
            headerEndpoint = session.headerEndpoint,
        )
        val prepared = preparer(snapshot) ?: return false
        if (prepared.sceneId != session.listSceneId || prepared.elementId != session.elementId) return false
        collectionSession = session.copy(listEndpoint = prepared)
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        return true
    }

    internal fun liveSnapshot(sceneId: String, elementId: String): SharedCoverSnapshot? =
        physicalLayout[key(sceneId, elementId)]?.currentSnapshot() ?: live[key(sceneId, elementId)]?.snapshot

    internal fun completePhysicalReturn(elementId: String) {
        if (physicalPromotedElementId == elementId) {
            physicalPromotedElementId = ""
            physicalPromotedSceneId = ""
        }
        physicallyStashedElements.remove(elementId)
        physicalPromotionActivatedFromStash = false
        val session = collectionSession
        if (session != null && session.elementId == elementId) collectionSession = null
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        requestedSource = null
        if (session != null && session.elementId == elementId) {
            restoreNestedCollectionParent(session)
        }
    }

    private fun restoreNestedCollectionParent(returnedChild: CollectionSession) {
        val parent = nestedCollectionParents.lastOrNull() ?: return
        if (parent.detailSceneId != returnedChild.listSceneId ||
            parent.elementId !in physicallyStashedElements
        ) return
        nestedCollectionParents.removeAt(nestedCollectionParents.lastIndex)
        val header = liveSnapshot(parent.detailSceneId, parent.elementId)
            ?: parent.headerEndpoint
        val activated = physicalPromotionActivator?.invoke(
            parent.elementId,
            header,
            "nested-return:${returnedChild.detailSceneId}->${parent.detailSceneId}",
        ) == true
        if (activated) {
            physicallyStashedElements.remove(parent.elementId)
            physicalPromotedElementId = parent.elementId
            physicalPromotedSceneId = parent.listSceneId
            physicalPromotionActivatedFromStash = false
        } else {
            // The session still gives a valid remembered geometry fallback, but an unowned
            // parked View must never leak across later navigation.
            physicalPromotionStashClearer?.invoke()
            physicallyStashedElements.clear()
            nestedCollectionParents.clear()
            AppLogger.w(
                "SceneHandoff",
                "SHARED_NESTED_RESTORE_MISS parent=${parent.elementId} child=${returnedChild.elementId}",
            )
        }
        collectionSession = parent.copy(headerEndpoint = header)
        AppLogger.i(
            "SceneHandoff",
            "SHARED_NESTED_RESTORE parent=${parent.elementId} child=${returnedChild.elementId} physical=$activated",
        )
    }

    /**
     * Terminal HeaderToItem owner barrier. The concrete promoted View is returned (or discarded)
     * synchronously before the route commits, matching retained-view implementation's B.f(..., true) handoff. Cleanup
     * of registry/session ownership is unconditional so a missing/offscreen destination can never
     * leak the special artwork actor into the next category or HOME scene.
     */
    internal fun finishPhysicalReturn(elementId: String): Boolean {
        if (elementId.isBlank()) return false
        val finalized = if (physicalPromotedElementId == elementId) {
            physicalReturnFinalizer?.invoke(elementId) == true
        } else {
            true
        }
        if (!finalized) return false
        completePhysicalReturn(elementId)
        return true
    }

    /** A scene-only child return retires just that child, never its parked parent holder. */
    internal fun retireNestedChildForSceneOnlyReturn(fromSceneId: String, toSceneId: String): Boolean {
        val child = collectionSession ?: return false
        val parent = nestedCollectionParents.lastOrNull() ?: return false
        if (child.detailSceneId != fromSceneId || child.listSceneId != toSceneId ||
            parent.detailSceneId != toSceneId
        ) return false
        val active = physicalPromotedElementId
        if (active.isNotBlank()) {
            if (active != child.elementId) return false
            val retired = physicalPromotionCanceller?.invoke(active) == true || !isPhysicalPromotionLive(active)
            if (!retired) return false
            physicalPromotedElementId = ""
            physicalPromotedSceneId = ""
            physicalPromotionActivatedFromStash = false
        }
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        requestedSource = null
        AppLogger.i("SceneHandoff", "SHARED_NESTED_CHILD_SCENE_ONLY child=${child.elementId}")
        return true
    }

    /** Run only after a committed child->parent route; cancelled gestures retain the child. */
    internal fun restoreNestedParentAfterCommittedBack(fromSceneId: String, toSceneId: String): Boolean {
        val child = collectionSession ?: return false
        val parent = nestedCollectionParents.lastOrNull() ?: return false
        if (child.detailSceneId != fromSceneId || child.listSceneId != toSceneId ||
            parent.detailSceneId != toSceneId
        ) return false
        val active = physicalPromotedElementId
        if (active == child.elementId && !finishPhysicalReturn(child.elementId)) {
            if (physicalPromotionCanceller?.invoke(active) != true && isPhysicalPromotionLive(active)) {
                return false
            }
            completePhysicalReturn(child.elementId)
        } else if (active.isBlank()) {
            completePhysicalReturn(child.elementId)
        }
        return collectionSession?.elementId == parent.elementId
    }

    /**
     * Hard terminal barrier used when a non-detail scene becomes authoritative (or a shared
     * transition owner is found orphaned). Prefer the normal HeaderToItem destination restore;
     * if that endpoint no longer exists, fall back to the original ItemToHeader source slot.
     * Registry ownership is cleared only after the concrete Android holder is known to be retired,
     * so bookkeeping can never get ahead of the physical View again.
     */
    internal fun retireAnyPhysicalSharedOwnership(): Boolean {
        val elementId = physicalPromotedElementId
        if (elementId.isBlank()) {
            // No active actor may still leave parked hierarchy ancestors in the persistent host.
            // Retire those too when the scene engine reaches its hard ownership barrier.
            val hadStashed = physicallyStashedElements.isNotEmpty()
            if (hadStashed) physicalPromotionStashClearer?.invoke()
            physicallyStashedElements.clear()
            nestedCollectionParents.clear()
            collectionSession = null
            preparedTransitionKey = ""
            preparedPair = null
            overlayReadyTransitionKey = ""
            requestedSource = null
            ephemeralPrepared = false
            return hadStashed
        }

        var retired = physicalReturnFinalizer?.invoke(elementId) == true
        if (!retired) {
            retired = physicalPromotionCanceller?.invoke(elementId) == true
        }
        if (!retired && !isPhysicalPromotionLive(elementId)) {
            // The persistent Android host no longer owns the concrete View; retaining only the
            // registry token would itself be an orphaned shared actor.
            retired = true
        }
        if (!retired) return false

        physicalPromotedElementId = ""
        physicalPromotedSceneId = ""
        physicalPromotionActivatedFromStash = false
        if (physicallyStashedElements.isNotEmpty()) {
            physicalPromotionStashClearer?.invoke()
            physicallyStashedElements.clear()
        }
        nestedCollectionParents.clear()
        collectionSession = null
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        requestedSource = null
        ephemeralPrepared = false
        return true
    }

    /** retained-view implementation ItemToHeader.P(false) -> X(): return the special View to the original source holder. */
    internal fun cancelPhysicalPromotion(): Boolean {
        val elementId = physicalPromotedElementId
        if (elementId.isBlank()) return true
        val cancelled = physicalPromotionCanceller?.invoke(elementId) == true
        if (!cancelled) return false
        if (physicalPromotionActivatedFromStash) {
            physicallyStashedElements += elementId
        }
        physicalPromotedElementId = ""
        physicalPromotedSceneId = ""
        physicalPromotionActivatedFromStash = false
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        requestedSource = null
        collectionSession = null
        return true
    }

    /**
     * Hard geometry-generation barrier for app-window resize / density changes.
     *
     * Every stored Rect in this registry belongs to the previous window coordinate space.  A
     * normal transition clear intentionally preserves collection/session geometry for reverse
     * navigation, but doing that across a window resize can make the next shared actor fly through
     * a stale endpoint.  Retire the concrete actor when possible, then drop every cached endpoint;
     * attached holders/headers republish fresh geometry during the ensuing layout pass.
     */
    internal fun invalidateWindowGeometry() {
        val activeElementId = physicalPromotedElementId
        if (activeElementId.isNotBlank()) {
            val finalized = physicalReturnFinalizer?.invoke(activeElementId) == true
            if (!finalized) physicalPromotionCanceller?.invoke(activeElementId)
        }
        if (physicallyStashedElements.isNotEmpty()) {
            physicalPromotionStashClearer?.invoke()
        }

        live.clear()
        physicalLayout.clear()
        requestedSource = null
        collectionSession = null
        nestedCollectionParents.clear()
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        physicalPromotedElementId = ""
        physicalPromotedSceneId = ""
        physicallyStashedElements.clear()
        physicalPromotionActivatedFromStash = false
        ephemeralPrepared = false
    }

    fun clearFrozen() {
        // Same-scene provider navigation has its own lifetime. An idle outer scene must not
        // erase its prepared actor on an unrelated parent recomposition.
        if (ephemeralPrepared) return
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
    }

    fun prepareTransition(
        transitionKey: String,
        from: SharedCoverSnapshot,
        to: SharedCoverSnapshot
    ) {
        ephemeralPrepared = false
        val existing = collectionSession
        val isReverse = existing != null &&
            from.elementId == existing.elementId &&
            from.sceneId == existing.detailSceneId &&
            to.sceneId == existing.listSceneId

        val stableFrom: SharedCoverSnapshot
        val stableTo: SharedCoverSnapshot
        if (isReverse) {
            val reverseSession = checkNotNull(existing)
            stableFrom = from.copy(
                coverKey = reverseSession.headerEndpoint.coverKey.ifBlank { from.coverKey }
            )
            // HeaderToItem uses the destination provider's authoritative pre-layout record. For
            // collection returns [prepareCollectionReturnTarget] has already replaced listEndpoint
            // with the retained-view implementation-style centered LayoutRes. Ignore transient retained measurements
            // from spare/promoted slots so the overlay and the returning physical holder converge
            // on that same pre-layout endpoint without a second jump.
            stableTo = reverseSession.listEndpoint.copy(
                coverKey = to.coverKey.ifBlank { reverseSession.listEndpoint.coverKey },
                radiusDp = to.radiusDp,
            )
            collectionSession = reverseSession
        } else {
            stableFrom = from
            stableTo = to
            collectionSession = CollectionSession(
                elementId = from.elementId,
                listSceneId = from.sceneId,
                detailSceneId = to.sceneId,
                listEndpoint = from,
                headerEndpoint = to
            )
        }
        preparedTransitionKey = transitionKey
        preparedPair = stableFrom to stableTo
        overlayReadyTransitionKey = ""
        requestedSource = null
        AppLogger.i(
            "SceneHandoff",
            "SHARED_PREPARE key=$transitionKey reverse=$isReverse from=${stableFrom.sceneId}/${stableFrom.elementId} " +
                "fromSlot=${stableFrom.physicalSlotId} to=${stableTo.sceneId}/${stableTo.elementId} " +
                "toEligible=${stableTo.sharedEligible} toPhysical=${physicalLayout[key(stableTo.sceneId, stableTo.elementId)] != null} " +
                "toLive=${live[key(stableTo.sceneId, stableTo.elementId)] != null}",
        )
    }


    /**
     * Same-scene shared-element pair used by provider-to-provider navigation such as Folders
     * Hierarchy. Unlike [prepareTransition], this deliberately does not create/update the retained
     * collection list/detail session, so a local hierarchy animation cannot poison a later scene
     * shared-cover reverse transaction.
     */
    fun prepareEphemeralTransition(
        transitionKey: String,
        from: SharedCoverSnapshot,
        target: SharedCoverSnapshot
    ) {
        ephemeralPrepared = true
        preparedTransitionKey = transitionKey
        preparedPair = from to target
        overlayReadyTransitionKey = ""
        requestedSource = null
    }

    fun clearEphemeralTransition(transitionKey: String) {
        if (transitionKey.isBlank() || preparedTransitionKey != transitionKey) return
        ephemeralPrepared = false
        preparedTransitionKey = ""
        preparedPair = null
        overlayReadyTransitionKey = ""
        requestedSource = null
    }

    fun getPreparedPair(transitionKey: String): Pair<SharedCoverSnapshot, SharedCoverSnapshot>? {
        return preparedPair.takeIf { transitionKey.isNotBlank() && preparedTransitionKey == transitionKey }
    }

    fun markOverlayReady(transitionKey: String) {
        if (transitionKey.isBlank()) return
        if (preparedTransitionKey == transitionKey && preparedPair != null) {
            overlayReadyTransitionKey = transitionKey
        }
    }

    fun isOverlayReady(transitionKey: String): Boolean {
        return transitionKey.isNotBlank() &&
            preparedTransitionKey == transitionKey &&
            preparedPair != null &&
            overlayReadyTransitionKey == transitionKey
    }

    fun isPrepared(transitionKey: String): Boolean {
        return transitionKey.isNotBlank() &&
            preparedTransitionKey == transitionKey &&
            preparedPair != null
    }

    fun isPreparedElement(transitionKey: String, sceneId: String, elementId: String): Boolean {
        val pair = getPreparedPair(transitionKey) ?: return false
        return (pair.first.sceneId == sceneId && pair.first.elementId == elementId) ||
            (pair.second.sceneId == sceneId && pair.second.elementId == elementId)
    }

    // A physical endpoint is authoritative even when ineligible. Falling back after filtering
    // it would resurrect the offscreen header from a stale Compose measurement.
    private fun eligibleReturnSource(sceneId: String, elementId: String): SharedCoverSnapshot? =
        (physicalLayout[key(sceneId, elementId)]?.currentSnapshot()
            ?: live[key(sceneId, elementId)]?.snapshot)?.takeIf { it.sharedEligible }

    private fun hasPreparedReturn(fromSceneId: String, toSceneId: String): Boolean =
        preparedPair?.let { (from, to) ->
            from.sceneId == fromSceneId && to.sceneId == toSceneId && from.sharedEligible
        } == true

    fun captureLiveCollectionReturnSource(fromSceneId: String, toSceneId: String): Boolean {
        // The prepared actor owns its source until settle/cancel. Later layout publication
        // must not reclassify the same gesture when the finger is released.
        if (hasPreparedReturn(fromSceneId, toSceneId)) return true
        val session = collectionSession ?: return false
        if (session.detailSceneId != fromSceneId || session.listSceneId != toSceneId) return false
        val alreadyFrozen = requestedSource?.takeIf {
            it.sceneId == fromSceneId && it.elementId == session.elementId
        }
        if (alreadyFrozen != null) return true
        val current = eligibleReturnSource(fromSceneId, session.elementId) ?: return false
        // Capture the physical source before SceneTransitionHost starts moving either page. This is
        // the Compose equivalent of VirtualListSharedItemTransition retaining its concrete View/holder.
        requestedSource = current
        return true
    }

    /**
     * True while HeaderToItem still has a concrete/eligible header source to animate.
     *
     * retained-view implementation keeps the migrated shared View out of the destination list until P(..., true)
     * commits it back into holder child. Raw's non-shared fallback must therefore be disabled for this
     * interval; otherwise the category holder redraws its own artwork underneath the promoted
     * actor and the return no longer has a single visual owner.
     */
    internal fun hasSharedCollectionReturnSource(
        fromSceneId: String,
        toSceneId: String,
    ): Boolean {
        if (hasPreparedReturn(fromSceneId, toSceneId)) return true
        val session = collectionSession ?: return false
        if (session.detailSceneId != fromSceneId || session.listSceneId != toSceneId) return false
        val frozen = requestedSource?.takeIf {
            it.sceneId == fromSceneId &&
                it.elementId == session.elementId &&
                it.sharedEligible
        }
        return frozen != null || eligibleReturnSource(fromSceneId, session.elementId) != null
    }

    fun findPairs(
        fromSceneId: String,
        toSceneId: String,
        allowRememberedTarget: Boolean = false,
        requireLiveTarget: Boolean = false,
    ): List<Pair<SharedCoverSnapshot, SharedCoverSnapshot>> {
        val session = collectionSession
        if (allowRememberedTarget && session != null &&
            session.detailSceneId == fromSceneId && session.listSceneId == toSceneId
        ) {
            // Reverse shared navigation must start from a concrete current detail holder. the retained-view implementation's
            // VirtualListSharedItemTransition owns a View/holder record; it cannot resurrect a header that
            // has already scrolled completely offscreen. Prefer the explicit Back-time freeze so
            // later layout callbacks cannot move the source endpoint underneath the overlay.
            val frozenFrom = requestedSource?.takeIf {
                it.sceneId == fromSceneId && it.elementId == session.elementId
            }
            val liveFrom = eligibleReturnSource(fromSceneId, session.elementId)
            val from = frozenFrom ?: liveFrom ?: return emptyList()
            val liveTarget = physicalLayout[key(toSceneId, session.elementId)]?.snapshot
                ?: live[key(toSceneId, session.elementId)]?.snapshot
            if (requireLiveTarget && liveTarget == null) return emptyList()
            return listOf(from to (liveTarget ?: session.listEndpoint))
        }

        val from = requestedSource
            ?.takeIf { it.sceneId == fromSceneId }
            ?: run {
                AppLogger.w(
                    "SceneHandoff",
                    "SHARED_FIND_MISS from=$fromSceneId to=$toSceneId reason=no_requested_source " +
                        "requested=${requestedSource?.sceneId}/${requestedSource?.elementId}",
                )
                return emptyList()
            }
        val to = if (from.physicalSlotId >= 0) {
            physicalLayout[key(toSceneId, from.elementId)]?.snapshot
                ?: live[key(toSceneId, from.elementId)]?.snapshot
        } else {
            live[key(toSceneId, from.elementId)]?.snapshot
        } ?: run {
            AppLogger.w(
                "SceneHandoff",
                "SHARED_FIND_MISS from=$fromSceneId to=$toSceneId reason=no_target element=${from.elementId} " +
                    "sourceSlot=${from.physicalSlotId} targetPhysical=${physicalLayout[key(toSceneId, from.elementId)] != null} " +
                    "targetLive=${live[key(toSceneId, from.elementId)] != null}",
            )
            return emptyList()
        }
        AppLogger.i(
            "SceneHandoff",
            "SHARED_FIND_PAIR from=$fromSceneId to=$toSceneId element=${from.elementId} " +
                "sourceSlot=${from.physicalSlotId} targetEligible=${to.sharedEligible}",
        )
        return listOf(from to to)
    }
}

val LocalSharedCoverRegistry = staticCompositionLocalOf {
    SharedCoverRegistry()
}
