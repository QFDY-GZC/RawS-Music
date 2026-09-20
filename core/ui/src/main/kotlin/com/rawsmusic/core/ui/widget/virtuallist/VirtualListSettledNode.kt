package com.rawsmusic.core.ui.widget.virtuallist

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.core.ui.R
import com.rawsmusic.core.ui.perf.TransitionPerfTrace
import com.rawsmusic.core.ui.perf.TransitionPerfStage
import com.rawsmusic.core.ui.perf.TransitionPerfEvent
import com.rawsmusic.core.ui.scene.CoverTransitionTarget
import com.rawsmusic.core.ui.scene.RetainedSceneItemTransform
import com.rawsmusic.core.ui.scene.SharedCoverSnapshot
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkHandle
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkTier
import com.rawsmusic.core.ui.widget.bitmaps.ArtworkSurface
import com.rawsmusic.core.ui.widget.bitmaps.AlbumArtTiers
import com.rawsmusic.core.ui.widget.bitmaps.BitmapProvider
import com.rawsmusic.core.ui.widget.bitmaps.BitmapRequest
import com.rawsmusic.core.ui.widget.bitmaps.ReferenceArtworkAlphaByte
import com.rawsmusic.core.ui.widget.bitmaps.ReferenceInterruptedPreviousAlpha
import com.rawsmusic.core.ui.widget.bitmaps.Reference_AA_REPLACEMENT_MS
import com.rawsmusic.core.ui.widget.bitmaps.RawArtworkPolicy
import com.rawsmusic.core.ui.widget.bitmaps.decodeDefaultAlbumArtwork
import com.rawsmusic.core.ui.widget.text.LongTextMotionState
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Step 1 of the long-lived Reference VirtualList ownership migration.
 *
 * The important boundary is deliberately narrow: ordinary settled LIST/GRID rows are painted by
 * one Compose [Modifier.Node]. There is no item Composable and no per-row graphicsLayer on this
 * path. Existing geometry functions remain authoritative and feed immutable holder records into
 * this node. Zoom, retained-provider/custom rows, selection UI and shared-transition ownership stay
 * on the old renderer until later phases so this step cannot rewrite their geometry curves.
 */
internal data class VirtualListSettledNodeItem(
    val slotId: Int,
    val song: AudioFile,
    val index: Int,
    val position: ComposeItemPosition,
    val rects: ComposeTransitionRects,
    val deferBitmapLoad: Boolean,
    val drawContentEnabled: Boolean,
    val isPlaying: Boolean,
    val hideCover: Boolean,
    val interactionActive: Boolean,
    val artworkAnimationsEnabled: Boolean,
    val artworkPriority: BitmapRequest.Priority,
    val coverDecodeSide: Int,
    val hasCollectionMetaIcon: Boolean,
    val hasFolderMetaIcon: Boolean,
    val isGrid: Boolean,
    /** Stable shared child identity carried with the physical holder across provider roles. */
    val sharedCoverElementId: String = "",
)

internal data class VirtualListSettledNodeFrame(
    val items: List<VirtualListSettledNodeItem>,
    val density: Float,
    val textDensity: Float,
    val dark: Boolean,
    val configuredTypeface: Typeface?,
    val resources: Resources,
    val defaultArtworkEnabled: Boolean,
    val marqueeAllowed: Boolean,
    val inputEnabled: Boolean = true,
    val scrollRemainderProvider: () -> Int,
    val scrollInvalidationOwner: VirtualListScrollPositionOwner?,
    val viewportHeightPx: Int = 0,
    val virtualListEdgeOvershootProvider: () -> Float = { 0f },
    val onPlayingCoverBoundsChanged: (RectF?) -> Unit,
    val onPlayingCoverTargetChanged: (CoverTransitionTarget?) -> Unit,
    val onSongClick: (AudioFile, Int) -> Unit,
    val onSongLongClick: (AudioFile, Int) -> Unit,
    val onCopySongInfo: (AudioFile) -> Unit,
    val sharedCoverSceneId: String = "",
    val sharedCoverElementIdProvider: (AudioFile, Int) -> String = { _, _ -> "" },
    val onSharedCoverClickSnapshot: (SharedCoverSnapshot) -> Unit = {},
    val canStartSharedCoverTransition: () -> Boolean = { true },
)


/**
 * Step 4F outer HOME/category motion frame.
 *
 * One physical holder owns both endpoint LayoutRes records.  The endpoint child geometry is carried
 * beside those LayoutRes records so the holder can morph its own internal scene exactly like
 * reference player's C.H0()/C.O(f) contract instead of switching to a second renderer for LIST <-> GRID.
 */
internal enum class VirtualListOuterTransitionKind { SHARED, CURRENT_ONLY, RETAINED_ONLY }

internal data class VirtualListOuterTransitionNodeItem(
    val holderItem: VirtualListSettledNodeItem,
    val kind: VirtualListOuterTransitionKind,
    val currentPosition: ComposeItemPosition?,
    val retainedPosition: ComposeItemPosition?,
    val currentRects: ComposeTransitionRects?,
    val retainedRects: ComposeTransitionRects?,
)

/**
 * One provider/layout role publication for the persistent VirtualList runtime.
 *
 * This is Raw's holder slot endpoint binding: Compose calculates an endpoint layout only when provider/layout
 * state changes, then the runtime retains the item + LayoutRes until that role is replaced.  Active
 * GenericPivot never reconstructs the union from page composition.
 */
internal data class VirtualListPowerItemRolePublication(
    val item: VirtualListSettledNodeItem,
    val layout: VirtualListPhysicalLayoutRes,
)

/** Provider bind payload for a heterogeneous physical holder View. */
internal data class VirtualListPowerCustomBinding(
    val song: AudioFile,
    val index: Int,
    val provider: VirtualListCustomProvider,
)

internal data class VirtualListPowerCustomRolePublication(
    val slotId: Int,
    val binding: VirtualListPowerCustomBinding,
    val layout: VirtualListPhysicalLayoutRes,
)

internal data class VirtualListOuterTransitionNodeFrame(
    val holderFrame: VirtualListSettledNodeFrame,
    /** Already ordered RETAINED_ONLY -> SHARED -> CURRENT_ONLY. The draw loop is single-pass. */
    val items: List<VirtualListOuterTransitionNodeItem>,
    val currentFractionProvider: () -> Float,
    val currentTransform: RetainedSceneItemTransform?,
    val retainedTransform: RetainedSceneItemTransform?,
    /** Holder-local endpoint elasticity written directly onto the physical holder. */
    val holderScaleProvider: () -> Float = { 1f },
    /**
     * True only while this pair is still at its preparation endpoint and may bind cold holder
     * content. Once motion begins, provider/content work is forbidden from the frame hot path.
     */
    val contentBindAllowedProvider: () -> Boolean = { true },
    val onPopulationPublished: (() -> Unit)? = null,
)

/**
 * Immutable content/layout identity of one bound outer endpoint pair.
 *
 * Callback/provider lambdas are deliberately excluded: they are behavior rebinding and the latest
 * [VirtualListOuterTransitionNodeFrame] can replace them without asking every existing holder to
 * re-run content preparation. This mirrors VirtualList returning an already-active holder slot holder while
 * the same provider/layout state continues to own it.
 */
private data class VirtualListOuterHolderContentSignature(
    val slotId: Int,
    val song: AudioFile,
    val index: Int,
    val deferBitmapLoad: Boolean,
    val drawContentEnabled: Boolean,
    val isPlaying: Boolean,
    val hideCover: Boolean,
    val interactionActive: Boolean,
    val artworkAnimationsEnabled: Boolean,
    val artworkPriority: BitmapRequest.Priority,
    val coverDecodeSide: Int,
    val hasCollectionMetaIcon: Boolean,
    val hasFolderMetaIcon: Boolean,
    val isGrid: Boolean,
    val sharedCoverElementId: String,
)

private fun VirtualListSettledNodeItem.outerHolderContentSignature():
    VirtualListOuterHolderContentSignature = VirtualListOuterHolderContentSignature(
        slotId = slotId,
        song = song,
        index = index,
        deferBitmapLoad = deferBitmapLoad,
        drawContentEnabled = drawContentEnabled,
        isPlaying = isPlaying,
        hideCover = hideCover,
        interactionActive = interactionActive,
        artworkAnimationsEnabled = artworkAnimationsEnabled,
        artworkPriority = artworkPriority,
        coverDecodeSide = coverDecodeSide,
        hasCollectionMetaIcon = hasCollectionMetaIcon,
        hasFolderMetaIcon = hasFolderMetaIcon,
        isGrid = isGrid,
        sharedCoverElementId = sharedCoverElementId,
    )

private data class VirtualListOuterContentSignature(
    val items: List<VirtualListOuterHolderContentSignature>,
    val densityBits: Int,
    val textDensityBits: Int,
    val dark: Boolean,
    val configuredTypeface: Typeface?,
    val resources: Resources,
    val defaultArtworkEnabled: Boolean,
    val marqueeAllowed: Boolean,
    val promotedSharedElementId: String,
)

private fun VirtualListOuterTransitionNodeFrame.contentSignature(
    promotedSharedElementId: String,
): VirtualListOuterContentSignature {
    val metadata = holderFrame
    return VirtualListOuterContentSignature(
        // Content identity is intentionally independent from CURRENT/RETAINED role assignment and
        // endpoint geometry. Reverse/new transitions may swap those LayoutRes records while the
        // same resident item-holder content remains valid.
        items = items
            .map { it.holderItem.outerHolderContentSignature() }
            .sortedBy(VirtualListOuterHolderContentSignature::slotId),
        densityBits = metadata.density.toRawBits(),
        textDensityBits = metadata.textDensity.toRawBits(),
        dark = metadata.dark,
        configuredTypeface = metadata.configuredTypeface,
        resources = metadata.resources,
        defaultArtworkEnabled = metadata.defaultArtworkEnabled,
        marqueeAllowed = metadata.marqueeAllowed,
        promotedSharedElementId = promotedSharedElementId,
    )
}

private fun describeOuterContentSignatureChange(
    previous: VirtualListOuterContentSignature,
    next: VirtualListOuterContentSignature,
): String {
    val reasons = ArrayList<String>(6)
    if (previous.items.size != next.items.size) {
        reasons += "itemCount:${previous.items.size}->${next.items.size}"
    } else {
        val changedIndex = previous.items.indices.firstOrNull { previous.items[it] != next.items[it] }
        if (changedIndex != null) {
            val old = previous.items[changedIndex]
            val new = next.items[changedIndex]
            val itemReason = when {
                old.slotId != new.slotId -> "slot:${old.slotId}->${new.slotId}"
                old.song.id != new.song.id || old.song.path != new.song.path ->
                    "song:${old.song.id}->${new.song.id}"
                old.index != new.index -> "index:${old.index}->${new.index}"
                old.isPlaying != new.isPlaying -> "playing:${old.isPlaying}->${new.isPlaying}"
                old.hideCover != new.hideCover -> "hideCover:${old.hideCover}->${new.hideCover}"
                old.coverDecodeSide != new.coverDecodeSide ->
                    "decodeSide:${old.coverDecodeSide}->${new.coverDecodeSide}"
                old.drawContentEnabled != new.drawContentEnabled ->
                    "draw:${old.drawContentEnabled}->${new.drawContentEnabled}"
                old.deferBitmapLoad != new.deferBitmapLoad -> "deferBitmap"
                old.interactionActive != new.interactionActive -> "interaction"
                old.artworkAnimationsEnabled != new.artworkAnimationsEnabled -> "artworkAnimation"
                old.artworkPriority != new.artworkPriority -> "artworkPriority"
                old.hasCollectionMetaIcon != new.hasCollectionMetaIcon -> "collectionMeta"
                old.hasFolderMetaIcon != new.hasFolderMetaIcon -> "folderMeta"
                old.isGrid != new.isGrid -> "isGrid"
                old.sharedCoverElementId != new.sharedCoverElementId -> "sharedCoverElement"
                old.song != new.song -> "songMetadata"
                else -> "holderContent"
            }
            reasons += "item[$changedIndex]:$itemReason"
        }
    }
    if (previous.densityBits != next.densityBits) reasons += "density"
    if (previous.textDensityBits != next.textDensityBits) reasons += "textDensity"
    if (previous.dark != next.dark) reasons += "dark:${previous.dark}->${next.dark}"
    if (previous.configuredTypeface != next.configuredTypeface) reasons += "typeface"
    if (previous.resources !== next.resources) reasons += "resources"
    if (previous.defaultArtworkEnabled != next.defaultArtworkEnabled) reasons += "defaultArtwork"
    if (previous.marqueeAllowed != next.marqueeAllowed) reasons += "marquee"
    if (previous.promotedSharedElementId != next.promotedSharedElementId) reasons += "promotedShared"
    return reasons.joinToString(separator = ",").ifBlank { "unknown" }
}

/**
 * Reused primitive motion state. Reference keeps two mutable LayoutRes records on the physical holder
 * and lerps primitive fields directly into View properties; the GenericPivot draw hot path must not
 * allocate an immutable motion object every vsync.
 */
internal class VirtualListOuterTransitionMotionSample {
    var currentFraction: Float = 0f
    var currentScale: Float = 1f
    var currentAlpha: Float = 1f
    var currentPivotX: Float = 0f
    var currentPivotY: Float = 0f
    var retainedScale: Float = 1f
    var retainedAlpha: Float = 1f
    var retainedPivotX: Float = 0f
    var retainedPivotY: Float = 0f
    var holderScale: Float = 1f
}

private fun sampleOuterTransitionMotion(
    frame: VirtualListOuterTransitionNodeFrame,
    out: VirtualListOuterTransitionMotionSample,
) {
    val current = frame.currentTransform
    val retained = frame.retainedTransform
    out.currentFraction = frame.currentFractionProvider().coerceIn(0f, 1f)
    out.currentScale = current?.scaleProvider?.invoke()?.coerceIn(0.5f, 1.5f) ?: 1f
    out.currentAlpha = current?.alphaProvider?.invoke()?.coerceIn(0f, 1f) ?: 1f
    out.currentPivotX = current?.pivotX ?: 0f
    out.currentPivotY = current?.pivotY ?: 0f
    out.retainedScale = retained?.scaleProvider?.invoke()?.coerceIn(0.5f, 1.5f) ?: 1f
    out.retainedAlpha = retained?.alphaProvider?.invoke()?.coerceIn(0f, 1f) ?: 1f
    out.retainedPivotX = retained?.pivotX ?: 0f
    out.retainedPivotY = retained?.pivotY ?: 0f
    out.holderScale = frame.holderScaleProvider().coerceIn(0.5f, 1.5f)
}

/**
 * Mutable per-holder resolved transform. This is deliberately primitive-only and reused for the
 * entire holder lifetime so GenericPivot does not allocate ComposeItemPosition/IntRect objects.
 */
private class VirtualListResolvedOuterTransform {
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0
    var alpha: Float = 1f
    var scaleX: Float = 1f
    var scaleY: Float = 1f

    val width: Int get() = right - left
    val height: Int get() = bottom - top
}

/**
 * Reference-equivalent mutable LayoutRes owned by the physical holder.
 *
 * `reference list holder` keeps two reusable `s` records on the holder and the animation path
 * lerps primitive fields directly. Raw used to keep ComposeItemPosition references on the outer
 * frame and re-resolve them through a slot-id map on every vsync. This holder-local record is bound
 * only when the transition population changes; draw then touches primitives only.
 */
private class VirtualListMutableLayoutRes {
    var present: Boolean = false
    var left: Int = 0
    var top: Int = 0
    var right: Int = 0
    var bottom: Int = 0
    var alpha: Float = 1f
    var scaleX: Float = 1f
    var scaleY: Float = 1f

    fun bind(position: ComposeItemPosition?) {
        if (position == null) {
            present = false
            return
        }
        present = true
        left = position.bounds.left
        top = position.bounds.top
        right = position.bounds.right
        bottom = position.bounds.bottom
        alpha = position.alpha
        scaleX = position.scaleX
        scaleY = position.scaleY
    }
}

/**
 * Owner-token lease for the settled ListCover endpoint.
 *
 * Incoming/outgoing provider compositions can overlap for one Compose apply pass. The old controller
 * must not publish null after the incoming controller already claimed and published the new endpoint.
 */
internal class VirtualListCoverTargetPublicationOwner {
    private var owner: Any? = null
    private var activeEpoch = Long.MIN_VALUE
    private var nextEpoch = 0L
    private var clearCallback: (() -> Unit)? = null

    fun register(): Long {
        // Registration alone never steals the active endpoint. Only a controller with real root
        // coordinates may claim, but later-created controllers receive a strictly newer epoch so an
        // outgoing viewport can never reclaim ownership from an incoming one after handoff.
        nextEpoch += 1L
        return nextEpoch
    }

    fun claim(
        owner: Any,
        epoch: Long,
        onBoundsChanged: (RectF?) -> Unit,
        onTargetChanged: (CoverTransitionTarget?) -> Unit,
    ): Boolean {
        if (epoch < activeEpoch) return false
        this.owner = owner
        activeEpoch = epoch
        clearCallback = {
            onBoundsChanged(null)
            onTargetChanged(null)
        }
        return true
    }

    fun publish(
        owner: Any,
        epoch: Long,
        bounds: RectF?,
        target: CoverTransitionTarget?,
        onBoundsChanged: (RectF?) -> Unit,
        onTargetChanged: (CoverTransitionTarget?) -> Unit,
    ) {
        if (!claim(owner, epoch, onBoundsChanged, onTargetChanged)) return
        onBoundsChanged(bounds)
        onTargetChanged(target)
    }

    fun clear(owner: Any, epoch: Long) {
        if (this.owner !== owner || activeEpoch != epoch) return
        clearCallback?.invoke()
        this.owner = null
        // Block this outgoing epoch but keep an already-registered successor (epoch+1) eligible.
        // This is the critical attach-new / detach-old Compose ordering case.
        activeEpoch = epoch + 1L
        clearCallback = null
    }

    fun clearAll() {
        clearCallback?.invoke()
        owner = null
        // Create a barrier above every controller registered before the clear. A late layout/scroll
        // callback from an outgoing provider therefore cannot resurrect a stale ListCover target.
        nextEpoch += 1L
        activeEpoch = nextEpoch
        clearCallback = null
    }

    internal fun isOwnedBy(owner: Any): Boolean = this.owner === owner
}


/** Single input/geometry bridge shared by the one draw node and the one pointer-input host. */
internal class VirtualListSettledNodeController(
    private val coverTargetOwner: VirtualListCoverTargetPublicationOwner = VirtualListCoverTargetPublicationOwner(),
    private val artworkBitmapProvider: (Int) -> Bitmap? = { null },
) {
    private val coverTargetOwnerToken = Any()
    private val coverTargetOwnerEpoch = coverTargetOwner.register()
    private var frame: VirtualListSettledNodeFrame? = null
    private var rootLeft = 0f
    private var rootTop = 0f
    private var windowLeft = 0f
    private var windowTop = 0f
    private var hasRoot = false
    private var lastPlayingBounds: RectF? = null
    private var lastPlayingSongId = Long.MIN_VALUE

    internal fun updateFrame(next: VirtualListSettledNodeFrame) {
        frame = next
        reportPlayingTarget()
    }

    internal fun updateRoot(
        left: Float,
        top: Float,
        leftInWindow: Float = left,
        topInWindow: Float = top,
    ) {
        rootLeft = left
        rootTop = top
        windowLeft = leftInWindow
        windowTop = topInWindow
        hasRoot = true
        frame?.let { current ->
            coverTargetOwner.claim(
                owner = coverTargetOwnerToken,
                epoch = coverTargetOwnerEpoch,
                onBoundsChanged = current.onPlayingCoverBoundsChanged,
                onTargetChanged = current.onPlayingCoverTargetChanged,
            )
        }
        reportPlayingTarget()
    }

    internal fun clearRoot() {
        hasRoot = false
        coverTargetOwner.clear(coverTargetOwnerToken, coverTargetOwnerEpoch)
        lastPlayingBounds = null
        lastPlayingSongId = Long.MIN_VALUE
    }

    /**
     * Persistent HOME/category handoff: stop this controller from publishing new geometry without
     * clearing the last valid endpoint. The incoming controller has a newer epoch and atomically
     * replaces it once its real root coordinates exist. Reference keeps the attached holder target
     * alive across provider/controller replacement; it never publishes a transient null endpoint.
     */
    internal fun detachRootPreservingTarget() {
        hasRoot = false
        lastPlayingBounds = null
        lastPlayingSongId = Long.MIN_VALUE
    }

    internal fun handleTap(offset: Offset) {
        val current = frame ?: return
        if (!current.inputEnabled) return
        val item = hitItem(current, offset) ?: return
        handleItemTap(item)
    }

    internal fun handleItemTap(item: VirtualListSettledNodeItem) {
        val current = frame ?: return
        if (!current.inputEnabled || !item.drawContentEnabled) return
        val target = coverTarget(current, item)
        val sharedSnapshot = sharedCoverSnapshot(current, item)
        // Only collection rows start ItemToHeader. The detail header can keep its physical
        // artwork holder throughout the visit without disabling ordinary song-row input.
        if (sharedSnapshot != null) {
            if (!current.canStartSharedCoverTransition()) return
            current.onSharedCoverClickSnapshot(sharedSnapshot)
        }
        current.onPlayingCoverBoundsChanged(target?.bounds)
        current.onPlayingCoverTargetChanged(target)
        current.onSongClick(item.song, item.index)
    }

    internal fun handleLongPress(offset: Offset) {
        val current = frame ?: return
        if (!current.inputEnabled) return
        val item = hitItem(current, offset) ?: return
        val remainder = current.scrollRemainderProvider().toFloat()
        val localX = offset.x - item.position.bounds.left
        val localY = offset.y + remainder - item.position.bounds.top
        handleItemLongPress(item, localX, localY)
    }

    internal fun handleItemLongPress(
        item: VirtualListSettledNodeItem,
        localX: Float,
        localY: Float,
    ) {
        val current = frame ?: return
        if (!current.inputEnabled || !item.drawContentEnabled) return
        val copyText = !item.isGrid && !item.position.isEmpty() && containsAnyText(item.rects, localX, localY)
        if (copyText) current.onCopySongInfo(item.song)
        else current.onSongLongClick(item.song, item.index)
    }

    /** Exact/remainder scroll changes move the live playing-cover target without rebuilding holders. */
    internal fun handleScrollChanged() {
        reportPlayingTarget()
    }

    private fun reportPlayingTarget() {
        val current = frame ?: return
        // A newly composed provider does not own the endpoint until it has real root coordinates.
        // Keeping the previous owner's target here avoids an apply/layout gap during scene handoff.
        if (!hasRoot) return
        val playing = current.items.firstOrNull { it.isPlaying }
        val target = playing?.let { coverTarget(current, it) }
        val bounds = target?.bounds
        val sameSong = target?.songId == lastPlayingSongId
        val sameBounds = bounds == null && lastPlayingBounds == null ||
            (bounds != null && lastPlayingBounds?.nearlyEqualsNode(bounds, 0.75f) == true)
        if (sameSong && sameBounds) return
        lastPlayingSongId = target?.songId ?: Long.MIN_VALUE
        lastPlayingBounds = bounds?.let(::RectF)
        coverTargetOwner.publish(
            owner = coverTargetOwnerToken,
            epoch = coverTargetOwnerEpoch,
            bounds = bounds?.let(::RectF),
            target = target,
            onBoundsChanged = current.onPlayingCoverBoundsChanged,
            onTargetChanged = current.onPlayingCoverTargetChanged,
        )
    }

    internal fun coverTarget(
        frame: VirtualListSettledNodeFrame,
        item: VirtualListSettledNodeItem,
    ): CoverTransitionTarget? {
        if (!hasRoot) return null
        val remainder = frame.scrollRemainderProvider().toFloat()
        val cover = item.rects.cover
        val left = rootLeft + item.position.bounds.left + cover.left
        val top = rootTop + item.position.bounds.top - remainder + cover.top
        return CoverTransitionTarget(
            bounds = RectF(left, top, left + cover.width, top + cover.height),
            radiusDp = item.rects.coverRadiusDp,
            source = CoverTransitionTarget.Source.ListCover,
            songId = item.song.id,
            coverKey = item.song.coverKey,
        )
    }

    private fun sharedCoverSnapshot(
        frame: VirtualListSettledNodeFrame,
        item: VirtualListSettledNodeItem,
    ): SharedCoverSnapshot? {
        if (!hasRoot || frame.sharedCoverSceneId.isBlank()) return null
        val elementId = item.sharedCoverElementId.ifBlank {
            frame.sharedCoverElementIdProvider(item.song, item.index)
        }
        // Collection shared-item identities use the stable `cover:*` namespace. Detail song rows
        // also carry a scene-local element id for other transitions; promoting those on a normal
        // play tap would incorrectly replace the retained collection Header<->Item session.
        if (!elementId.startsWith("cover:")) return null
        val cover = item.rects.cover
        if (cover.width <= 0 || cover.height <= 0) return null
        val remainder = frame.scrollRemainderProvider().toFloat()
        val left = windowLeft + item.position.bounds.left + cover.left
        val top = windowTop + item.position.bounds.top - remainder + cover.top
        val holderTop = item.position.bounds.top - remainder
        val holderBottom = item.position.bounds.bottom - remainder
        return SharedCoverSnapshot(
            sceneId = frame.sharedCoverSceneId,
            elementId = elementId,
            boundsInWindow = androidx.compose.ui.geometry.Rect(
                left = left,
                top = top,
                right = left + cover.width,
                bottom = top + cover.height,
            ),
            coverKey = item.song.coverKey,
            radiusDp = item.rects.coverRadiusDp,
            itemIndex = item.index,
            itemViewportBounds = androidx.compose.ui.geometry.Rect(
                left = item.position.bounds.left.toFloat(),
                top = holderTop,
                right = item.position.bounds.right.toFloat(),
                bottom = holderBottom,
            ),
            physicalSlotId = item.slotId,
            promotedBitmap = artworkBitmapProvider(item.slotId),
        )
    }

    internal fun currentFrame(): VirtualListSettledNodeFrame? = frame

    private fun hitItem(frame: VirtualListSettledNodeFrame, offset: Offset): VirtualListSettledNodeItem? {
        val remainder = frame.scrollRemainderProvider().toFloat()
        val y = offset.y + remainder
        // Reverse order mirrors draw order if future layouts overlap during an elastic endpoint.
        for (i in frame.items.indices.reversed()) {
            val item = frame.items[i]
            if (!item.drawContentEnabled) continue
            val b = item.position.bounds
            if (offset.x >= b.left && offset.x < b.right && y >= b.top && y < b.bottom) return item
        }
        return null
    }

    private fun containsAnyText(rects: ComposeTransitionRects, x: Float, y: Float): Boolean {
        return contains(rects.title, x, y) || contains(rects.subtitle, x, y) || contains(rects.meta, x, y)
    }

    private fun contains(rect: ComposeItemRect, x: Float, y: Float): Boolean {
        return rect.alpha > 0f &&
            x >= rect.left && x < rect.left + rect.width &&
            y >= rect.top && y < rect.top + rect.height
    }
}

internal fun Modifier.virtualListSettledNode(
    frame: VirtualListSettledNodeFrame,
    controller: VirtualListSettledNodeController,
    runtime: VirtualListSettledNodeRuntime,
    retainRuntimeOnDetach: Boolean,
): Modifier = this then VirtualListSettledNodeElement(frame, controller, runtime, retainRuntimeOnDetach)

private data class VirtualListSettledNodeElement(
    val frame: VirtualListSettledNodeFrame,
    val controller: VirtualListSettledNodeController,
    val runtime: VirtualListSettledNodeRuntime,
    val retainRuntimeOnDetach: Boolean,
) : ModifierNodeElement<VirtualListSettledDrawNode>() {
    override fun create(): VirtualListSettledDrawNode =
        VirtualListSettledDrawNode(frame, controller, runtime, retainRuntimeOnDetach)
    override fun update(node: VirtualListSettledDrawNode) =
        node.update(frame, controller, runtime, retainRuntimeOnDetach)
    override fun InspectorInfo.inspectableProperties() {
        name = "virtualListSettledNode"
        properties["holderCount"] = frame.items.size
    }
}

private class VirtualListSettledDrawNode(
    initialFrame: VirtualListSettledNodeFrame,
    initialController: VirtualListSettledNodeController,
    initialRuntime: VirtualListSettledNodeRuntime,
    initialRetainRuntimeOnDetach: Boolean,
) : DelegatingNode() {
    private var frame = initialFrame
    private var controller = initialController
    private var runtime = initialRuntime
    private var retainRuntimeOnDetach = initialRetainRuntimeOnDetach
    // VirtualList input belongs to the concrete holder View, not to a transparent Compose gesture
    // layer sitting above the retained holder population. Keeping a second tap owner here prevents
    // heterogeneous ComposeView holders (HOME cards/carousel) from receiving their own child input
    // and also creates a competing gesture recognizer during scroll. The permanent Android holder
    // host now owns click/long-click dispatch directly on the physical item holder.
    private var lastLoggedPlayingSongId = Long.MIN_VALUE
    private var observedScrollOwner = initialFrame.scrollInvalidationOwner
    private val scrollInvalidator: () -> Unit = {
        if (isAttached) {
            controller.handleScrollChanged()
            runtime.invalidateSettledMotionPresentation()
        }
    }

    init {
        controller.updateFrame(initialFrame)
    }

    override fun onAttach() {
        super.onAttach()
        runtime.attachPhysicalInputController(controller)
        observedScrollOwner?.addRenderInvalidationListener(scrollInvalidator)
        applyFrame(frame)
        Log.i(VIRTUAL_LIST_NODE_TAG, "VIRTUAL_LIST_NODE INPUT_ATTACH holders=${runtime.holderCount}")
        AppLogger.i(HANDOFF_TRACE_TAG, "INPUT_ATTACH runtime=${System.identityHashCode(runtime)} holders=${runtime.holderCount}")
    }

    override fun onDetach() {
        observedScrollOwner?.removeRenderInvalidationListener(scrollInvalidator)
        runtime.detachPhysicalInputController(controller)
        if (!retainRuntimeOnDetach) {
            runtime.clearHoldersPreservingHost()
            controller.clearRoot()
        } else {
            // Never emit ListCover=null merely because the source page/controller leaves
            // composition. The next persistent controller claims with a newer epoch after layout.
            controller.detachRootPreservingTarget()
        }
        Log.i(VIRTUAL_LIST_NODE_TAG, "VIRTUAL_LIST_NODE INPUT_DETACH")
        AppLogger.i(HANDOFF_TRACE_TAG, "INPUT_DETACH runtime=${System.identityHashCode(runtime)} retain=$retainRuntimeOnDetach holders=${runtime.holderCount}")
        super.onDetach()
    }

    internal fun update(
        nextFrame: VirtualListSettledNodeFrame,
        nextController: VirtualListSettledNodeController,
        nextRuntime: VirtualListSettledNodeRuntime,
        nextRetainRuntimeOnDetach: Boolean,
    ) {
        val runtimeChanged = runtime !== nextRuntime
        val controllerChanged = controller !== nextController
        if (runtimeChanged || controllerChanged) {
            if (isAttached) runtime.detachPhysicalInputController(controller)
        }
        if (runtimeChanged) {
            if (!retainRuntimeOnDetach) runtime.clearHoldersPreservingHost()
            runtime = nextRuntime
        }
        retainRuntimeOnDetach = nextRetainRuntimeOnDetach
        if (observedScrollOwner !== nextFrame.scrollInvalidationOwner) {
            if (isAttached) observedScrollOwner?.removeRenderInvalidationListener(scrollInvalidator)
            observedScrollOwner = nextFrame.scrollInvalidationOwner
            if (isAttached) observedScrollOwner?.addRenderInvalidationListener(scrollInvalidator)
        }
        frame = nextFrame
        controller = nextController
        if (isAttached && (runtimeChanged || controllerChanged)) {
            runtime.attachPhysicalInputController(controller)
        }
        controller.updateFrame(nextFrame)
        if (isAttached) applyFrame(nextFrame)
    }

    private fun applyFrame(next: VirtualListSettledNodeFrame) {
        runtime.publishSettledFrame(next)
        val playingSongId = next.items.firstOrNull { it.isPlaying }?.song?.id ?: -1L
        if (playingSongId != lastLoggedPlayingSongId) {
            lastLoggedPlayingSongId = playingSongId
            Log.i(VIRTUAL_LIST_NODE_TAG, "VIRTUAL_LIST_NODE PLAYING songId=$playingSongId holders=${runtime.holderCount}")
        }
    }
}

/**
 * One visual owner for the complete VirtualList viewport.
 *
 * Settled and GenericPivot publishers only mutate the host-owned runtime/LayoutRes records. The
 * presentation node stays attached in one stable Compose slot and is the only object allowed to
 * submit retained holder RenderNodes or own their invalidation lease. This removes the final
 * settled<->transition renderer-shell handoff which could expose the scene background for one frame.
 */
internal fun Modifier.virtualListPresentationHost(
    runtime: VirtualListSettledNodeRuntime,
    externalTransform: RetainedSceneItemTransform? = null,
    presentationVisibleProvider: () -> Boolean = { true },
): Modifier = this then VirtualListPresentationHostElement(
    runtime = runtime,
    externalTransform = externalTransform,
    presentationVisibleProvider = presentationVisibleProvider,
)

private data class VirtualListPresentationHostElement(
    val runtime: VirtualListSettledNodeRuntime,
    val externalTransform: RetainedSceneItemTransform?,
    val presentationVisibleProvider: () -> Boolean,
) : ModifierNodeElement<VirtualListPresentationHostDrawNode>() {
    override fun create(): VirtualListPresentationHostDrawNode =
        VirtualListPresentationHostDrawNode(
            initialRuntime = runtime,
            initialExternalTransform = externalTransform,
            initialPresentationVisibleProvider = presentationVisibleProvider,
        )
    override fun update(node: VirtualListPresentationHostDrawNode) =
        node.update(runtime, externalTransform, presentationVisibleProvider)
    override fun InspectorInfo.inspectableProperties() {
        name = "virtualListPresentationHost"
        properties["holderCount"] = runtime.holderCount
    }
}

private class VirtualListPresentationHostDrawNode(
    initialRuntime: VirtualListSettledNodeRuntime,
    initialExternalTransform: RetainedSceneItemTransform?,
    initialPresentationVisibleProvider: () -> Boolean,
) : Modifier.Node(), DrawModifierNode, Choreographer.FrameCallback {
    override val shouldAutoInvalidate: Boolean
        get() = false

    private var runtime = initialRuntime
    private var externalTransform = initialExternalTransform
    private var presentationVisibleProvider = initialPresentationVisibleProvider
    private val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var noteResources: Resources? = null
    private var noteDrawable: android.graphics.drawable.Drawable? = null
    private var framePosted = false
    private var marqueeAcquired = false

    override fun onAttach() {
        super.onAttach()
        runtime.attachInvalidator(this, ::invalidateFromRuntime)
        Log.i(
            VIRTUAL_LIST_NODE_TAG,
            "VIRTUAL_LIST_PRESENTATION_HOST ATTACH host=${System.identityHashCode(this)} runtime=${System.identityHashCode(runtime)} holders=${runtime.holderCount}",
        )
        AppLogger.i(HANDOFF_TRACE_TAG, "HOST_ATTACH host=${System.identityHashCode(this)} runtime=${System.identityHashCode(runtime)} holders=${runtime.holderCount}")
        updateMarqueeOwnership(isPresentationVisible() && runtime.presentationHasMarquee())
        invalidateDraw()
        if (isPresentationVisible() && runtime.presentationNeedsFrameCallback()) postFrame()
    }

    override fun onDetach() {
        Log.i(
            VIRTUAL_LIST_NODE_TAG,
            "VIRTUAL_LIST_PRESENTATION_HOST DETACH host=${System.identityHashCode(this)} runtime=${System.identityHashCode(runtime)} holders=${runtime.holderCount}",
        )
        AppLogger.i(HANDOFF_TRACE_TAG, "HOST_DETACH host=${System.identityHashCode(this)} runtime=${System.identityHashCode(runtime)} holders=${runtime.holderCount}")
        if (framePosted) {
            Choreographer.getInstance().removeFrameCallback(this)
            framePosted = false
        }
        updateMarqueeOwnership(false)
        runtime.detachInvalidator(this)
        // Do not clear holder/LayoutRes state here. HOME/category provider replacement may detach an
        // old viewport host after the incoming host has already attached to the same persistent
        // runtime. Owner-token detach prevents the outgoing host from stealing invalidation.
        super.onDetach()
    }

    fun update(
        nextRuntime: VirtualListSettledNodeRuntime,
        nextExternalTransform: RetainedSceneItemTransform?,
        nextPresentationVisibleProvider: () -> Boolean,
    ) {
        if (runtime !== nextRuntime) {
            runtime.detachInvalidator(this)
            runtime = nextRuntime
            noteResources = null
            noteDrawable = null
            if (isAttached) runtime.attachInvalidator(this, ::invalidateFromRuntime)
        }
        externalTransform = nextExternalTransform
        presentationVisibleProvider = nextPresentationVisibleProvider
        if (isAttached) {
            val visible = isPresentationVisible()
            updateMarqueeOwnership(visible && runtime.presentationHasMarquee())
            if (!visible) cancelFrame()
            invalidateDraw()
            if (visible && runtime.presentationNeedsFrameCallback()) postFrame()
        }
    }

    override fun ContentDrawScope.draw() {
        if (!isPresentationVisible()) {
            updateMarqueeOwnership(false)
            cancelFrame()
            return
        }
        val resources = runtime.presentationResources()
        if (resources !== noteResources) {
            noteResources = resources
            noteDrawable = resources?.getDrawable(R.drawable.ic_music_note, null)?.mutate()
        }
        runtime.drawPresentation(
            canvas = drawContext.canvas.nativeCanvas,
            rowPaint = rowPaint,
            noteDrawable = noteDrawable,
            externalTransform = externalTransform,
        )
        updateMarqueeOwnership(runtime.presentationHasMarquee())
        if (runtime.presentationNeedsFrameCallback()) postFrame()
    }

    private fun invalidateFromRuntime(@Suppress("UNUSED_PARAMETER") signal: VirtualListRenderInvalidation) {
        if (!isAttached) return
        val visible = isPresentationVisible()
        updateMarqueeOwnership(visible && runtime.presentationHasMarquee())
        if (!visible) {
            cancelFrame()
            return
        }
        invalidateDraw()
        if (runtime.presentationNeedsFrameCallback()) postFrame()
    }

    private fun isPresentationVisible(): Boolean = presentationVisibleProvider()

    private fun updateMarqueeOwnership(needed: Boolean) {
        if (needed == marqueeAcquired) return
        marqueeAcquired = needed
        if (needed) LongTextMotionState.acquireMarquee() else LongTextMotionState.releaseMarquee()
    }

    private fun postFrame() {
        if (!isAttached || framePosted || !isPresentationVisible()) return
        framePosted = true
        Choreographer.getInstance().postFrameCallback(this)
    }

    private fun cancelFrame() {
        if (!framePosted) return
        Choreographer.getInstance().removeFrameCallback(this)
        framePosted = false
    }

    override fun doFrame(frameTimeNanos: Long) {
        framePosted = false
        if (!isAttached) return
        if (!isPresentationVisible()) {
            updateMarqueeOwnership(false)
            return
        }
        runtime.advancePresentation(frameTimeNanos)
        invalidateDraw()
        if (runtime.presentationNeedsFrameCallback()) postFrame()
    }
}


internal fun Modifier.virtualListOuterTransitionNode(
    frame: VirtualListOuterTransitionNodeFrame,
    runtime: VirtualListSettledNodeRuntime,
): Modifier = this then VirtualListOuterTransitionNodeElement(frame, runtime)

private data class VirtualListOuterTransitionNodeElement(
    val frame: VirtualListOuterTransitionNodeFrame,
    val runtime: VirtualListSettledNodeRuntime,
) : ModifierNodeElement<VirtualListOuterTransitionDrawNode>() {
    override fun create(): VirtualListOuterTransitionDrawNode =
        VirtualListOuterTransitionDrawNode(frame, runtime)

    override fun update(node: VirtualListOuterTransitionDrawNode) = node.update(frame, runtime)

    override fun InspectorInfo.inspectableProperties() {
        name = "virtualListOuterTransitionNode"
        properties["holderCount"] = frame.items.size
    }
}

private class VirtualListOuterTransitionDrawNode(
    initialFrame: VirtualListOuterTransitionNodeFrame,
    initialRuntime: VirtualListSettledNodeRuntime,
) : Modifier.Node() {
    private var frame = initialFrame
    private var runtime = initialRuntime

    override fun onAttach() {
        super.onAttach()
        applyFrame(frame)
    }

    override fun onDetach() {
        // Keep the last valid GenericPivot endpoint in the persistent runtime until the incoming
        // settled publisher replaces it. Clearing here recreates the one-frame background flash
        // Reference avoids by moving one attached holder population between LayoutRes states.
        // The permanent presentation host owns the only transition Choreographer callback.
        super.onDetach()
    }

    fun update(
        nextFrame: VirtualListOuterTransitionNodeFrame,
        nextRuntime: VirtualListSettledNodeRuntime,
    ) {
        runtime = nextRuntime
        frame = nextFrame
        if (isAttached) applyFrame(nextFrame)
    }

    private fun applyFrame(next: VirtualListOuterTransitionNodeFrame) {
        runtime.publishOuterFrame(next)
    }
}


private fun interpolateOuterLayoutInto(
    source: VirtualListMutableLayoutRes,
    target: VirtualListMutableLayoutRes,
    progress: Float,
    out: VirtualListResolvedOuterTransform,
) {
    if (!source.present || !target.present) {
        out.alpha = 0f
        out.left = 0
        out.top = 0
        out.right = 0
        out.bottom = 0
        return
    }
    val p = progress.coerceIn(0f, 1f)
    fun lerpInt(a: Int, b: Int): Int = (a + (b - a) * p).roundToInt()
    fun lerpFloat(a: Float, b: Float): Float = a + (b - a) * p
    out.left = lerpInt(source.left, target.left)
    out.top = lerpInt(source.top, target.top)
    // The retained-layout interpolation path updates LayoutRes width/height independently from
    // translationX/Y and remeasures the physical View with those exact dimensions every tick.
    // Interpolating right/bottom separately can differ by one pixel after rounding, so preserve
    // the same width/height arithmetic explicitly.
    out.right = out.left + lerpInt(source.right - source.left, target.right - target.left)
    out.bottom = out.top + lerpInt(source.bottom - source.top, target.bottom - target.top)
    out.alpha = lerpFloat(source.alpha, target.alpha)
    out.scaleX = lerpFloat(source.scaleX, target.scaleX)
    out.scaleY = lerpFloat(source.scaleY, target.scaleY)
}

private fun sceneTransformedLayoutInto(
    base: VirtualListMutableLayoutRes,
    scale: Float,
    alpha: Float,
    pivotX: Float,
    pivotY: Float,
    out: VirtualListResolvedOuterTransform,
) {
    if (!base.present) {
        out.alpha = 0f
        out.left = 0
        out.top = 0
        out.right = 0
        out.bottom = 0
        return
    }
    // reference player GenericPivot mode=4 keeps the View's measured bounds unchanged, shifts its
    // translation by the holder-center distance from the common pivot, then applies the common
    // scale around Android's normal View-center pivot. This is intentionally not equivalentized
    // through scaled left/right bounds: the integer translation landing and View pivot semantics
    // are part of the reference HWUI path.
    val width = base.right - base.left
    val height = base.bottom - base.top
    val centerX = (base.left + base.right) * 0.5f
    val centerY = (base.top + base.bottom) * 0.5f
    val scaleDelta = scale - 1f
    out.left = (base.left + ((centerX - pivotX) * scaleDelta)).toInt()
    out.top = (base.top + ((centerY - pivotY) * scaleDelta)).toInt()
    out.right = out.left + width
    out.bottom = out.top + height
    out.alpha = base.alpha * alpha
    out.scaleX = base.scaleX * scale
    out.scaleY = base.scaleY * scale
}

/**
 * Plain holder/artwork runtime which may outlive a temporary Modifier.Node detach.
 *
 * HOME/category provider switches replace controller/page composition scopes, but Reference keeps the
 * attached ArtworkItemNode pool.  The host-owned instance therefore preserves prepared text and provider
 * ArtworkHandle leases across the short library-motion handoff; a new draw Node simply reattaches an
 * invalidator and republishes the target frame.
 */
/**
 * Physical presentation ownership only.
 *
 * VirtualList has one attached View population. GenericPivot is not another presentation mode; it is
 * a second LayoutRes role attached to those same Views. [outerPresentationFrame] below therefore
 * represents optional motion state while this enum answers only whether the physical owner exists.
 */
private enum class VirtualListPresentationMode { NONE, ATTACHED }

/**
 * Mutable, allocation-stable projection consumed by the Android ViewGroup presentation host.
 *
 * The holder content itself remains a retained HWUI RenderNode, while geometry is applied by a
 * real child View (translation/scale/alpha) instead of by Raw's synthetic presentation RenderNode.
 * This is intentionally close to VirtualList's View + LayoutRes split without duplicating provider,
 * artwork, selection or transition ownership.
 */
internal class VirtualListViewGroupHolderState {
    var slotId: Int = -1
    var drawOrderGroup: Int = 1
    var drawOrderIndex: Int = 0
    var width: Int = 1
    var height: Int = 1
    var left: Float = 0f
    var top: Float = 0f
    var pivotX: Float = 0f
    var pivotY: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var alpha: Float = 1f
    var coverLeft: Int = 0
    var coverTop: Int = 0
    var coverWidth: Int = 0
    var coverHeight: Int = 0
    var coverRadiusPx: Float = 0f
    var artworkCurrentBitmap: Bitmap? = null
    var artworkPreviousBitmap: Bitmap? = null
    var artworkFallbackBitmap: Bitmap? = null
    var artworkCurrentAlpha: Float = 1f
    var artworkPreviousAlpha: Float = 0f
    var artworkHidden: Boolean = false
    // Low-frequency transition diagnostics. These fields never participate in layout/render
    // equality; they only let the Android View host prove whether a render re-entry kept the same
    // physical Bitmap/provider lease or rebound/cancelled it while alpha was zero.
    var artworkDebugHandleValid: Boolean = false
    var artworkDebugTier: String = "-"
    var artworkDebugRequestActive: Boolean = false
    var artworkDebugRequestSide: Int = 0
    var artworkDebugLastRequestEndReason: String = "none"
    var artworkDebugKeyTag: String = "-"
    var contentNode: RenderNode? = null
    val titleText = VirtualListTextChildState()
    val subtitleText = VirtualListTextChildState()
    val metaText = VirtualListTextChildState()
}

internal const val VIRTUAL_LIST_META_ICON_NONE = 0
internal const val VIRTUAL_LIST_META_ICON_NOTE = 1
internal const val VIRTUAL_LIST_META_ICON_FOLDER = 2

/**
 * Mutable scene state for one concrete retained text child of the physical holder.
 *
 * The View itself stays attached for the holder lifetime. Scene changes mutate this local rect,
 * font size, alpha and marquee offset in place, matching item-holder child-scene ownership rather
 * than rebuilding text through a transition renderer.
 */
internal class VirtualListTextChildState {
    var marqueeEnabled = false
    var marqueeSpeed = 0f
    var left: Int = 0
    var top: Int = 0
    var width: Int = 1
    var height: Int = 1
    var alpha: Float = 0f
    var textSizePx: Float = 0f
    var sourceTextSizePx: Float = 0f
    var targetTextSizePx: Float = 0f
    var text: String = ""
    var typeface: Typeface = Typeface.DEFAULT
    var color: Int = 0
    var leftInsetPx: Float = 0f
    var horizontalOffsetPx: Float = 0f
    var ellipsize: Boolean = false
    var metaIconKind: Int = VIRTUAL_LIST_META_ICON_NONE
    var metaIconSizePx: Int = 0
}

/*
 * LayoutRes dirty bits used by the retained-layout commit path:
 *   1  -> scaleX
 *   2  -> scaleY
 *   32 -> alpha
 *   64 -> measured size/layout
 *
 * Translation is intentionally not represented by a dirty bit. reference player commits the LayoutRes
 * left/top to View.translationX/Y on every LayoutRes application, then gates only the remaining
 * properties with K. Keep those exact semantics on Raw's GenericPivot hot path.
 */
internal const val VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_X = 1
internal const val VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_Y = 1 shl 1
internal const val VIRTUAL_LIST_LAYOUT_DIRTY_ALPHA = 1 shl 5
internal const val VIRTUAL_LIST_LAYOUT_DIRTY_SIZE = 1 shl 6

/**
 * Allocation-stable projection of the mutable LayoutRes currently owned by one physical holder.
 * This is deliberately smaller than [VirtualListViewGroupHolderState]: GenericPivot must not walk
 * content/artwork/structure state on every vsync. It carries only the fields VirtualList applies from
 * the resolved transform to the already attached physical holder, plus the artwork fade alpha owned by the attached image
 * artwork child.
 */
internal class VirtualListViewGroupMotionState {
    var slotId: Int = -1
    var width: Int = 1
    var height: Int = 1
    var left: Float = 0f
    var top: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var alpha: Float = 1f
    var dirtyBits: Int = 0
    /** Both LayoutRes records are valid; all interpolated properties are committed to the holder. */
    var dualLayout: Boolean = false
    // Internal item-holder/retained-layout child geometry. Artwork is a concrete child
    // View, so LIST <-> GRID morph changes its bounds/radius without rebinding the bitmap actor.
    var coverLeft: Int = 0
    var coverTop: Int = 0
    var coverWidth: Int = 0
    var coverHeight: Int = 0
    var coverRadiusPx: Float = 0f
    var artworkCurrentAlpha: Float = 1f
    var artworkPreviousAlpha: Float = 0f
    /** Settled scroll moves the holder only; outer scene morph also updates holder-local children. */
    var updateInternalChildren: Boolean = true
    val titleText = VirtualListTextChildState()
    val subtitleText = VirtualListTextChildState()
    val metaText = VirtualListTextChildState()
}

/** Physical state for one heterogeneous provider holder. */
internal class VirtualListCustomViewGroupHolderState {
    var slotId: Int = -1
    var drawOrderGroup: Int = 1
    var drawOrderIndex: Int = 0
    var width: Int = 1
    var height: Int = 1
    var left: Float = 0f
    var top: Float = 0f
    var scaleX: Float = 1f
    var scaleY: Float = 1f
    var alpha: Float = 1f
    var dirtyBits: Int = 0
    var dualLayout: Boolean = false
    var binding: VirtualListPowerCustomBinding? = null
}

/**
 * Raw counterpart of reference player's mutable `s` LayoutRes property state.
 *
 * Endpoint LayoutRes records remain separate; this object is the currently committed physical
 * View state. Mutators compare before setting and produce the same scale/alpha/size dirty bits as
 * reference behavior. Left/top are always committed by the host's retained-layout commit path.
 */
private class VirtualListMutablePresentationLayoutRes {
    private var initialized = false
    private var width = 1
    private var height = 1
    private var scaleX = 1f
    private var scaleY = 1f
    private var alpha = 1f

    fun seed(width: Int, height: Int, scaleX: Float, scaleY: Float, alpha: Float) {
        this.width = width.coerceAtLeast(1)
        this.height = height.coerceAtLeast(1)
        this.scaleX = scaleX
        this.scaleY = scaleY
        this.alpha = alpha
        initialized = true
    }

    fun update(width: Int, height: Int, scaleX: Float, scaleY: Float, alpha: Float): Int {
        val nextWidth = width.coerceAtLeast(1)
        val nextHeight = height.coerceAtLeast(1)
        var dirty = 0
        if (!initialized || this.width != nextWidth || this.height != nextHeight) {
            dirty = dirty or VIRTUAL_LIST_LAYOUT_DIRTY_SIZE
        }
        if (!initialized || this.scaleX != scaleX) dirty = dirty or VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_X
        if (!initialized || this.scaleY != scaleY) dirty = dirty or VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_Y
        if (!initialized || this.alpha != alpha) dirty = dirty or VIRTUAL_LIST_LAYOUT_DIRTY_ALPHA
        this.width = nextWidth
        this.height = nextHeight
        this.scaleX = scaleX
        this.scaleY = scaleY
        this.alpha = alpha
        initialized = true
        return dirty
    }

    fun reset() {
        initialized = false
    }
}

internal class VirtualListViewGroupPresentationFrame {
    var structureGeneration: Long = Long.MIN_VALUE
    var parentPivotX: Float = 0f
    var parentPivotY: Float = 0f
    var parentScale: Float = 1f
    var parentAlpha: Float = 1f
    var clipEnabled: Boolean = false
    var clipLeft: Float = 0f
    var clipTop: Float = 0f
    var clipRight: Float = 0f
    var clipBottom: Float = 0f
    val holders = ArrayList<VirtualListViewGroupHolderState>()
    val customHolders = ArrayList<VirtualListCustomViewGroupHolderState>()

    fun reset() {
        holders.clear()
        customHolders.clear()
        parentPivotX = 0f
        parentPivotY = 0f
        parentScale = 1f
        parentAlpha = 1f
        clipEnabled = false
        clipLeft = 0f
        clipTop = 0f
        clipRight = 0f
        clipBottom = 0f
    }
}

/**
 * Bridge to the permanent Android ViewGroup which owns the actual attached holder Views.
 *
 * The ItemToHeader transition moves the concrete holder view into the transition artwork host and later moves the
 * same View back. The runtime pins the matching NodeHolder while this bridge pins that concrete
 * child View; no second artwork actor is created.
 */
internal interface VirtualListPhysicalSharedHost {
    fun promoteSlot(slotId: Int, elementId: String): Boolean

    fun updatePromotedGeometry(
        transitionKey: String,
        target: SharedCoverSnapshot,
        progressProvider: (() -> Float)?,
        animateFromCurrent: Boolean,
    )

    fun restorePromotedSlot(slotId: Int)

    /** @return true when the Android host actually removed a promoted concrete View. */
    fun clearPromotedSlot(): Boolean
}

internal class VirtualListSettledNodeRuntime {
    /**
     * Raw equivalent of reference player holder slot: one physical slot keeps both endpoint bindings.  The record is
     * owned by the persistent list runtime, never by a transition Composable.
     */
    private data class PowerItemRecord(
        var currentItem: VirtualListSettledNodeItem? = null,
        var retainedItem: VirtualListSettledNodeItem? = null,
        var currentLayout: VirtualListPhysicalLayoutRes? = null,
        var retainedLayout: VirtualListPhysicalLayoutRes? = null,
        var currentSeenFrame: Int = Int.MIN_VALUE,
        var retainedSeenFrame: Int = Int.MIN_VALUE,
    )

    private class PowerCustomRecord {
        var currentBinding: VirtualListPowerCustomBinding? = null
        var retainedBinding: VirtualListPowerCustomBinding? = null
        var currentLayout: VirtualListPhysicalLayoutRes? = null
        var retainedLayout: VirtualListPhysicalLayoutRes? = null
        var currentSeenFrame: Int = Int.MIN_VALUE
        var retainedSeenFrame: Int = Int.MIN_VALUE
        val currentMutableLayout = VirtualListMutableLayoutRes()
        val retainedMutableLayout = VirtualListMutableLayoutRes()
        val resolved = VirtualListResolvedOuterTransform()
        val presentation = VirtualListMutablePresentationLayoutRes()
        val state = VirtualListCustomViewGroupHolderState()
    }

    private val holders = LinkedHashMap<Int, NodeHolder>()
    private val powerItems = LinkedHashMap<Int, PowerItemRecord>()
    private val powerCustomItems = LinkedHashMap<Int, PowerCustomRecord>()
    private val outerCustomMotionStates = ArrayList<VirtualListCustomViewGroupHolderState>()
    private val lastPowerCustomActiveSlots = LinkedHashSet<Int>()
    private var powerCustomPublicationChanged = false
    private var powerItemFrameId = Int.MIN_VALUE
    private var powerCurrentFrame: VirtualListSettledNodeFrame? = null
    private var powerRetainedFrame: VirtualListSettledNodeFrame? = null
    private val settledDrawOrder = ArrayList<NodeHolder>()
    private val outerDrawOrder = ArrayList<NodeHolder>()
    private val settledMotionViewStates = ArrayList<VirtualListViewGroupMotionState>()
    private val settledCustomMotionStates = ArrayList<VirtualListCustomViewGroupHolderState>()
    private val outerMotionViewStates = ArrayList<VirtualListViewGroupMotionState>()
    private val detachedPrewarmDrawOrder = ArrayList<NodeHolder>()
    private val detachedPrewarmViewStates = ArrayList<VirtualListViewGroupHolderState>()
    private val detachedPrewarmCustomViewStates = ArrayList<VirtualListCustomViewGroupHolderState>()
    private val ordinaryPrewarmReadiness = VirtualListPrewarmReadiness()
    private val customPrewarmReadiness = VirtualListPrewarmReadiness()
    private var pendingOrdinaryPrewarmMaterializedKey: VirtualListPrewarmKey? = null
    private var pendingOrdinaryPrewarmMaterializedCallback: (() -> Unit)? = null
    private var lastMaterializedOrdinaryPrewarmKey: VirtualListPrewarmKey? = null
    private val outerContentPublicationGate =
        VirtualListContentPublicationGate<VirtualListOuterContentSignature>()
    private var lastOuterContentSignature: VirtualListOuterContentSignature? = null
    private var detachedPrewarmFrame: VirtualListSettledNodeFrame? = null
    private var physicalInputController: VirtualListSettledNodeController? = null
    private var settledMotionInvalidationPending = false
    private val invalidationOwner = VirtualListRenderInvalidationOwner()
    internal val coverTargetOwner = VirtualListCoverTargetPublicationOwner()
    private val presentationMotionSample = VirtualListOuterTransitionMotionSample()
    private var settledPresentationFrame: VirtualListSettledNodeFrame? = null
    private var outerPresentationFrame: VirtualListOuterTransitionNodeFrame? = null
    private var presentationMode = VirtualListPresentationMode.NONE
    private var cachedMarqueeOverflow = false
    private var attachmentGeneration = 0
    private var presentationStructureGeneration = 0L
    private var api29PopulationDisplayList: Api29PopulationDisplayList? = null
    private var presentationEpoch = 0L
    private var pendingFirstDrawTrace = false
    // reference player promotes the retained LayoutRes slot to CURRENT by swapping references inside the
    // same VirtualList. Keep Raw's already-recorded OUTER population root across the first SETTLED
    // draw and do not force a second post-endpoint structure mutation. Stale source-only children
    // are already alpha=0 at the transition endpoint and are compacted by the next natural holder
    // structure change instead of by a dedicated extra vsync.
    private val viewGroupPresentationFrame = VirtualListViewGroupPresentationFrame()
    private var viewGroupPresentationSuspended = false
    private var physicalSharedHost: VirtualListPhysicalSharedHost? = null
    private var promotedSharedHolder: NodeHolder? = null
    private var promotedSharedSlotId: Int = -1
    private var promotedSharedElementId: String = ""

    // baseline implementation keeps one VirtualList View attached to one parent. For the persistent library host,
    // holder coordinates are still published in the active VirtualList viewport's local space, so the
    // fixed presentation node only needs one imperative viewport translation/clip. These values change
    // on layout/scene commit, never on the GenericPivot vsync hot path.
    private var fixedPresentationHost = false
    private var presentationHostRootLeft = 0f
    private var presentationHostRootTop = 0f
    private var presentationViewportRootLeft = 0f
    private var presentationViewportRootTop = 0f
    private var presentationViewportWidth = 0f
    private var presentationViewportHeight = 0f
    private var presentationViewportValid = false
    private var presentationViewportClipEnabled = true
    private var presentationViewportClipTopInset = 0f

    val holderCount: Int
        get() = holders.size + powerCustomItems.size + detachedPrewarmCustomViewStates.size

    /** Start one CURRENT/RETAINED endpoint publication transaction. */
    internal fun beginPowerItemFrame(frameId: Int) {
        if (powerItemFrameId == frameId) return
        powerItemFrameId = frameId
        powerCurrentFrame = null
        powerRetainedFrame = null
        powerCustomPublicationChanged = false
    }

    /**
     * Publish one complete endpoint population.  This corresponds to VirtualList binding a provider
     * into one of u.B/u.f4539.  It does not mutate the visible presentation and is never called from
     * the animation tick.
     */
    internal fun publishPowerItemRole(
        frameId: Int,
        role: VirtualListPhysicalLayoutRole,
        frame: VirtualListSettledNodeFrame,
        publications: List<VirtualListPowerItemRolePublication>,
    ) {
        if (frameId != powerItemFrameId) beginPowerItemFrame(frameId)
        when (role) {
            VirtualListPhysicalLayoutRole.CURRENT -> powerCurrentFrame = frame
            VirtualListPhysicalLayoutRole.RETAINED -> powerRetainedFrame = frame
        }
        for (publication in publications) {
            val slotId = publication.item.slotId
            if (slotId < 0) continue
            val record = powerItems.getOrPut(slotId) { PowerItemRecord() }
            when (role) {
                VirtualListPhysicalLayoutRole.CURRENT -> {
                    record.currentItem = publication.item
                    record.currentLayout = publication.layout
                    record.currentSeenFrame = frameId
                }
                VirtualListPhysicalLayoutRole.RETAINED -> {
                    record.retainedItem = publication.item
                    record.retainedLayout = publication.layout
                    record.retainedSeenFrame = frameId
                }
            }
        }
    }

    internal fun publishPowerCustomRole(
        frameId: Int,
        role: VirtualListPhysicalLayoutRole,
        publications: List<VirtualListPowerCustomRolePublication>,
    ) {
        if (frameId != powerItemFrameId) beginPowerItemFrame(frameId)
        for (publication in publications) {
            if (publication.slotId < 0) continue
            val record = powerCustomItems.getOrPut(publication.slotId) { PowerCustomRecord() }
            when (role) {
                VirtualListPhysicalLayoutRole.CURRENT -> {
                    if (record.currentBinding != publication.binding || record.currentLayout != publication.layout) {
                        powerCustomPublicationChanged = true
                    }
                    record.currentBinding = publication.binding
                    record.currentLayout = publication.layout
                    record.currentMutableLayout.bind(publication.layout.exactViewportPosition())
                    record.currentSeenFrame = frameId
                }
                VirtualListPhysicalLayoutRole.RETAINED -> {
                    if (record.retainedBinding != publication.binding || record.retainedLayout != publication.layout) {
                        powerCustomPublicationChanged = true
                    }
                    record.retainedBinding = publication.binding
                    record.retainedLayout = publication.layout
                    record.retainedMutableLayout.bind(publication.layout.exactViewportPosition())
                    record.retainedSeenFrame = frameId
                }
            }
        }
    }

    /** Close both layout roles exactly once after the provider pair has published. */
    internal fun endPowerItemFrame(frameId: Int) {
        if (frameId != powerItemFrameId) return
        val iterator = powerItems.entries.iterator()
        while (iterator.hasNext()) {
            val record = iterator.next().value
            if (record.currentSeenFrame != frameId) {
                record.currentItem = null
                record.currentLayout = null
            }
            if (record.retainedSeenFrame != frameId) {
                record.retainedItem = null
                record.retainedLayout = null
            }
            if (record.currentItem == null && record.retainedItem == null) iterator.remove()
        }
        val customIterator = powerCustomItems.entries.iterator()
        while (customIterator.hasNext()) {
            val record = customIterator.next().value
            if (record.currentSeenFrame != frameId) {
                if (record.currentBinding != null || record.currentLayout != null) powerCustomPublicationChanged = true
                record.currentBinding = null
                record.currentLayout = null
                record.currentMutableLayout.bind(null)
            }
            if (record.retainedSeenFrame != frameId) {
                if (record.retainedBinding != null || record.retainedLayout != null) powerCustomPublicationChanged = true
                record.retainedBinding = null
                record.retainedLayout = null
                record.retainedMutableLayout.bind(null)
            }
            if (record.currentBinding == null && record.retainedBinding == null) {
                customIterator.remove()
            }
        }
        val activeSlots = LinkedHashSet<Int>(powerCustomItems.size)
        powerCustomItems.forEach { (slotId, record) ->
            if (record.currentBinding != null || record.retainedBinding != null) activeSlots += slotId
        }
        if (activeSlots != lastPowerCustomActiveSlots) {
            lastPowerCustomActiveSlots.clear()
            lastPowerCustomActiveSlots.addAll(activeSlots)
            presentationStructureGeneration += 1L
            powerCustomPublicationChanged = true
        }
        if (powerCustomPublicationChanged) invalidationOwner.invalidate()
    }

    /**
     * Bind the already-published endpoint roles to the one attached physical holder population.
     * No provider lookup, slot allocation or Compose item renderer participates here.
     */
    internal fun publishPowerItemOuterFrame(
        currentFractionProvider: () -> Float,
        currentTransform: RetainedSceneItemTransform?,
        retainedTransform: RetainedSceneItemTransform?,
        onPopulationPublished: (() -> Unit)? = null,
    ) {
        val metadataFrame = powerCurrentFrame ?: powerRetainedFrame ?: run {
            onPopulationPublished?.invoke()
            return
        }
        val retainedOnly = ArrayList<VirtualListOuterTransitionNodeItem>()
        val shared = ArrayList<VirtualListOuterTransitionNodeItem>()
        val currentOnly = ArrayList<VirtualListOuterTransitionNodeItem>()
        for ((_, record) in powerItems) {
            val currentItem = record.currentItem
            val retainedItem = record.retainedItem
            val currentLayout = record.currentLayout
            val retainedLayout = record.retainedLayout
            val holderItem = currentItem ?: retainedItem ?: continue
            val kind = when {
                currentLayout != null && retainedLayout != null -> VirtualListOuterTransitionKind.SHARED
                currentLayout != null -> VirtualListOuterTransitionKind.CURRENT_ONLY
                retainedLayout != null -> VirtualListOuterTransitionKind.RETAINED_ONLY
                else -> continue
            }
            val motion = VirtualListOuterTransitionNodeItem(
                holderItem = holderItem,
                kind = kind,
                currentPosition = currentLayout?.exactViewportPosition(),
                retainedPosition = retainedLayout?.exactViewportPosition(),
                currentRects = currentItem?.rects,
                retainedRects = retainedItem?.rects,
            )
            when (kind) {
                VirtualListOuterTransitionKind.RETAINED_ONLY -> retainedOnly += motion
                VirtualListOuterTransitionKind.SHARED -> shared += motion
                VirtualListOuterTransitionKind.CURRENT_ONLY -> currentOnly += motion
            }
        }
        val ordered = ArrayList<VirtualListOuterTransitionNodeItem>(
            retainedOnly.size + shared.size + currentOnly.size
        ).apply {
            addAll(retainedOnly)
            addAll(shared)
            addAll(currentOnly)
        }
        val holderFrame = metadataFrame.copy(items = ordered.map { it.holderItem })
        // PREPARED custom Views are already attached by this point. OUTER now owns the same slots;
        // drop only the preparation role so the host does not keep stale alpha-zero children after
        // the transition commits.
        detachedPrewarmCustomViewStates.clear()
        publishOuterFrame(
            VirtualListOuterTransitionNodeFrame(
                holderFrame = holderFrame,
                items = ordered,
                currentFractionProvider = currentFractionProvider,
                currentTransform = currentTransform,
                retainedTransform = retainedTransform,
                contentBindAllowedProvider = { false },
                onPopulationPublished = onPopulationPublished,
            )
        )
    }

    internal fun attachPhysicalSharedHost(host: VirtualListPhysicalSharedHost) {
        if (physicalSharedHost !== host) {
            // Node/RenderNode preparation alone does not prove that this concrete Android host
            // already owns NEXT's child Views. A host replacement must re-materialize and re-ACK.
            lastMaterializedOrdinaryPrewarmKey = null
        }
        physicalSharedHost = host
    }

    internal fun detachPhysicalSharedHost(host: VirtualListPhysicalSharedHost) {
        if (physicalSharedHost === host) {
            physicalSharedHost = null
            lastMaterializedOrdinaryPrewarmKey = null
        }
    }

    internal fun attachPhysicalInputController(controller: VirtualListSettledNodeController) {
        physicalInputController = controller
    }

    internal fun detachPhysicalInputController(controller: VirtualListSettledNodeController) {
        if (physicalInputController === controller) physicalInputController = null
    }

    internal fun onPhysicalHolderClick(slotId: Int) {
        val controller = physicalInputController ?: return
        val item = holders[slotId]?.boundItem() ?: return
        controller.handleItemTap(item)
    }

    internal fun onPhysicalHolderLongClick(slotId: Int, localX: Float, localY: Float) {
        val controller = physicalInputController ?: return
        val item = holders[slotId]?.boundItem() ?: return
        controller.handleItemLongPress(item, localX, localY)
    }

    internal fun onPhysicalHolderRealDetach(slotId: Int) {
        holders[slotId]?.onPhysicalRealDetach()
    }

    /**
     * Slot-local artwork-view projection. Bitmap/provider completion uses this narrow
     * path instead of rebuilding the holder population or preparing text/layout content.
     */
    internal fun prepareArtworkViewGroupState(slotId: Int): VirtualListViewGroupHolderState? =
        holders[slotId]?.prepareArtworkViewGroupState()

    /** A physical slot type replacement invalidates any detached NEXT residency proof for it. */
    internal fun invalidatePrewarmResidencyForSlot(slotId: Int) {
        if (detachedPrewarmDrawOrder.any { it.boundItem()?.slotId == slotId }) {
            ordinaryPrewarmReadiness.invalidateSlot(slotId)
        }
        if (detachedPrewarmCustomViewStates.any { it.slotId == slotId }) {
            customPrewarmReadiness.invalidate()
        }
    }

    /** Move a VirtualList holder artwork child into the transition artwork host. */
    internal fun beginSharedHolderPromotion(slotId: Int, elementId: String): Boolean {
        if (slotId < 0 || elementId.isBlank()) return false
        if (promotedSharedHolder != null) {
            if (promotedSharedSlotId == slotId && promotedSharedElementId == elementId) return true
            restoreSharedHolderPromotion(promotedSharedSlotId)
        }
        val host = physicalSharedHost ?: return false
        val holder = holders[slotId] ?: return false

        // Detach the artwork view only after the source item has a replacement child owner.
        // Snapshot the artwork while the source holder is still fully visible, then suppress only
        // that holder's artwork lane before asking the Android View host to promote the concrete
        // child. promoteSlot() synchronously materializes the replacement row from this suppressed
        // runtime state, so title/subtitle/meta never disappear for one vsync.
        holder.beginSharedArtworkPromotion(
            presentationViewportWidth.roundToInt().coerceAtLeast(1)
        )
        holder.setSharedArtworkSuppressed(true)
        promotedSharedHolder = holder
        promotedSharedSlotId = slotId
        promotedSharedElementId = elementId
        presentationStructureGeneration += 1L
        if (!host.promoteSlot(slotId, elementId)) {
            promotedSharedHolder = null
            promotedSharedSlotId = -1
            promotedSharedElementId = ""
            holder.setSharedArtworkSuppressed(false)
            holder.endSharedArtworkPromotion()
            presentationStructureGeneration += 1L
            invalidationOwner.invalidate()
            return false
        }
        invalidationOwner.invalidate()
        return true
    }

    internal fun updateSharedHolderGeometry(
        transitionKey: String,
        target: SharedCoverSnapshot,
        progressProvider: (() -> Float)?,
        animateFromCurrent: Boolean,
    ) {
        val promotedHolder = promotedSharedHolder ?: return
        if (target.elementId != promotedSharedElementId) return
        val targetSide = target.physicalViewportBounds?.let { bounds ->
            maxOf(bounds.width, bounds.height).roundToInt()
        } ?: maxOf(target.boundsInWindow.width, target.boundsInWindow.height).roundToInt()
        promotedHolder.beginSharedArtworkPromotion(targetSide.coerceAtLeast(1))
        physicalSharedHost?.updatePromotedGeometry(
            transitionKey = transitionKey,
            target = target,
            progressProvider = progressProvider,
            animateFromCurrent = animateFromCurrent,
        )
        invalidationOwner.invalidate()
    }

    /** Move the transition artwork child back into the destination VirtualList holder. */
    internal fun restoreSharedHolderPromotion(destinationSlotId: Int): String? {
        val holder = promotedSharedHolder ?: return null
        val elementId = promotedSharedElementId
        if (destinationSlotId < 0) return null
        val replacement = holders[destinationSlotId]
        if (replacement !== holder) {
            replacement?.let { stale ->
                for (index in settledDrawOrder.indices) {
                    if (settledDrawOrder[index] === stale) settledDrawOrder[index] = holder
                }
                for (index in outerDrawOrder.indices) {
                    if (outerDrawOrder[index] === stale) outerDrawOrder[index] = holder
                }
                stale.release()
            }
        }
        holders[destinationSlotId] = holder
        when (presentationMode) {
            VirtualListPresentationMode.ATTACHED -> {
                val outer = outerPresentationFrame
                if (outer != null) {
                val motion = outer.items.firstOrNull { it.holderItem.slotId == destinationSlotId }
                if (motion != null) {
                    // HeaderToItem commits this same concrete View as the normal destination owner.
                    // The published transition endpoint still has hideCover=true until registry
                    // cleanup later in this call stack; do not rebind that transaction-only hidden
                    // flag onto the View we are about to restore into the destination holder.
                    holder.update(motion.holderItem.copy(hideCover = false), outer.holderFrame)
                    holder.setSharedArtworkSuppressed(false)
                    holder.bindOuterLayout(motion)
                }
                } else {
                    settledPresentationFrame?.let { settled ->
                        // Rebind the promoted physical holder to the destination LayoutRes before
                        // returning the concrete View to the ordinary holder map.
                        applyFrame(settled)
                        settled.items.firstOrNull { it.slotId == destinationSlotId }?.let { destination ->
                            holder.update(destination.copy(hideCover = false), settled)
                            holder.setSharedArtworkSuppressed(false)
                        }
                    }
                }
            }

            VirtualListPresentationMode.NONE -> Unit
        }

        // The Android presentation host synchronously asks this runtime for the destination child
        // state inside restorePromotedSlot(). Do not let that sync observe the old promoted/suppressed
        // ownership: doing so materializes a blank destination artwork lane and then immediately
        // drops the promoted raster, which is exactly the one-frame (and on some devices multi-frame)
        // hole seen on HeaderToItem when the detail header is already offscreen.
        //
        // reference player's the endpoint commit step first makes destination holder slot authoritative and
        // reattaches the same concrete View; only then is the transition owner released. Keep the
        // promoted raster/lease alive through RawVirtualListPresentationView.restorePromotedSlot().
        // Clearing it before the synchronous destination bind created a real one-frame hole at the
        // reverse endpoint whenever ordinary destination artwork was still transition-hidden.
        promotedSharedElementId = ""
        holder.setSharedArtworkSuppressed(false)
        presentationStructureGeneration += 1L
        physicalSharedHost?.restorePromotedSlot(destinationSlotId)
        holder.endSharedArtworkPromotion()
        promotedSharedHolder = null
        promotedSharedSlotId = -1
        invalidationOwner.invalidate()
        return elementId
    }

    internal fun hasPromotedSharedHolder(elementId: String): Boolean =
        promotedSharedHolder != null && promotedSharedElementId == elementId

    /** Abort forward shared-holder migration back into the original source holder. */
    internal fun cancelSharedHolderPromotion(elementId: String): Boolean {
        if (elementId.isBlank() || promotedSharedElementId != elementId) return false
        val sourceSlot = promotedSharedSlotId
        if (sourceSlot < 0) return false
        return restoreSharedHolderPromotion(sourceSlot) == elementId
    }

    internal fun displayedPromotedSharedArtworkBitmap(): Bitmap? =
        promotedSharedHolder?.displayedSharedArtworkBitmap()

    internal fun resolveSharedHolderReturnSlot(): Int? {
        val elementId = promotedSharedElementId
        if (elementId.isBlank()) return null
        return when (presentationMode) {
            VirtualListPresentationMode.ATTACHED -> {
                val outer = outerPresentationFrame
                if (outer == null) {
                    val frame = settledPresentationFrame ?: return null
                    return frame.items.firstOrNull { item ->
                        item.sharedCoverElementId.ifBlank {
                            frame.sharedCoverElementIdProvider(item.song, item.index)
                        } == elementId
                    }?.slotId
                }
                // HeaderToItem ends with the destination collection/list in RETAINED LayoutRes.
                // At that endpoint CURRENT alpha is exactly zero, so the shared holder already
                // occupies the retained/list geometry. The physical presentation owner has not
                // changed; only its active LayoutRes role is about to commit.
                if (outer.currentFractionProvider().coerceIn(0f, 1f) > 0.0001f) return null
                outer.items.firstOrNull { motion ->
                    motion.retainedPosition != null &&
                        motion.holderItem.sharedCoverElementId.ifBlank {
                            outer.holderFrame.sharedCoverElementIdProvider(
                                motion.holderItem.song,
                                motion.holderItem.index,
                            )
                        } == elementId
                }?.holderItem?.slotId
            }

            VirtualListPresentationMode.NONE -> null
        }
    }

    internal fun clearSharedHolderPromotion() {
        val holder = promotedSharedHolder
        if (holder != null) {
            holder.setSharedArtworkSuppressed(false)
            holder.endSharedArtworkPromotion()
            val stillOwnedByPool = holders.values.any { it === holder }
            if (!stillOwnedByPool) holder.release()
        }
        promotedSharedHolder = null
        promotedSharedSlotId = -1
        promotedSharedElementId = ""
        physicalSharedHost?.clearPromotedSlot()
        presentationStructureGeneration += 1L
        invalidationOwner.invalidate()
    }

    /**
     * reference player HeaderToItem completes as one synchronous physical-owner transaction. Mirror that
     * here: restore the concrete promoted holder into the destination slot if one exists; otherwise
     * destroy the special promoted owner immediately so it can never leak into another scene.
     */
    internal fun finishSharedHolderReturn(elementId: String): Boolean {
        if (elementId.isBlank() || promotedSharedElementId != elementId) return false
        val destinationSlot = resolveSharedHolderReturnSlot()
        if (destinationSlot != null) {
            return restoreSharedHolderPromotion(destinationSlot) == elementId
        }
        return false
    }

    /**
     * Returns the exact bitmap currently presented by the retained physical holder. This does not
     * start a provider lookup or a decode; it is the shared-element handoff equivalent of
     * VirtualListItemToHeaderTransition moving the already-attached artwork View itself.
     */
    internal fun displayedArtworkBitmap(slotId: Int): Bitmap? =
        holders[slotId]?.displayedArtworkBitmap()

    internal fun resolveViewportLocalBoundsInHost(
        bounds: androidx.compose.ui.geometry.Rect,
    ): androidx.compose.ui.geometry.Rect {
        val dx = if (fixedPresentationHost) presentationViewportRootLeft - presentationHostRootLeft else 0f
        val dy = if (fixedPresentationHost) presentationViewportRootTop - presentationHostRootTop else 0f
        return androidx.compose.ui.geometry.Rect(
            left = bounds.left + dx,
            top = bounds.top + dy,
            right = bounds.right + dx,
            bottom = bounds.bottom + dy,
        )
    }

    fun updatePresentationHostBoundsInRoot(left: Float, top: Float) {
        val changed = !fixedPresentationHost ||
            presentationHostRootLeft != left || presentationHostRootTop != top
        fixedPresentationHost = true
        presentationHostRootLeft = left
        presentationHostRootTop = top
        if (changed) invalidationOwner.invalidate()
    }

    fun updatePresentationViewportBoundsInRoot(
        left: Float,
        top: Float,
        width: Float,
        height: Float,
    ) {
        val normalizedWidth = width.coerceAtLeast(0f)
        val normalizedHeight = height.coerceAtLeast(0f)
        val changed = !presentationViewportValid ||
            presentationViewportRootLeft != left || presentationViewportRootTop != top ||
            presentationViewportWidth != normalizedWidth || presentationViewportHeight != normalizedHeight
        presentationViewportRootLeft = left
        presentationViewportRootTop = top
        presentationViewportWidth = normalizedWidth
        presentationViewportHeight = normalizedHeight
        presentationViewportValid = normalizedWidth > 0f && normalizedHeight > 0f
        if (changed) invalidationOwner.invalidate()
    }

    fun updatePresentationViewportClipEnabled(enabled: Boolean) {
        if (presentationViewportClipEnabled == enabled) return
        presentationViewportClipEnabled = enabled
        invalidationOwner.invalidate()
    }

    fun updatePresentationViewportClipTopInset(topInsetPx: Float) {
        val normalized = topInsetPx.coerceAtLeast(0f)
        if (presentationViewportClipTopInset == normalized) return
        presentationViewportClipTopInset = normalized
        invalidationOwner.invalidate()
    }

    fun attachInvalidator(owner: Any, invalidator: (VirtualListRenderInvalidation) -> Unit) {
        invalidationOwner.attach(owner, invalidator)
    }

    fun detachInvalidator(owner: Any) {
        invalidationOwner.detach(owner)
    }

    /**
     * Bind current holder content without treating every content mutation as a parent-structure
     * change. Android ViewGroup does not re-record its display list merely because a child View's
     * pixels, alpha or translation changed; only the attached child identity/order matters.
     */
    private fun applyFrame(frame: VirtualListSettledNodeFrame): Boolean {
        detachPromotedHolderFromPoolIfNeeded(frame)
        val previousSize = settledDrawOrder.size
        var settledOrderChanged = previousSize != frame.items.size
        settledDrawOrder.ensureCapacity(frame.items.size)
        attachmentGeneration += 1
        if (attachmentGeneration == Int.MAX_VALUE) {
            holders.values.forEach { it.attachmentGeneration = 0 }
            attachmentGeneration = 1
        }
        val generation = attachmentGeneration
        var marqueeOverflow = false
        var diagnosticExisting = 0
        var diagnosticSameIdentity = 0
        var diagnosticReboundIdentity = 0
        var diagnosticNew = 0
        var diagnosticBitmap = 0
        var diagnosticRecordedSizeMatch = 0
        var diagnosticRecordedSizeMismatch = 0
        var diagnosticArtworkDisplayListPresent = 0
        var diagnosticArtworkDisplayListMissing = 0
        for ((drawIndex, item) in frame.items.withIndex()) {
            val existing = holders[item.slotId]
            if (TransitionPerfTrace.isActive()) {
                if (existing == null) {
                    diagnosticNew += 1
                } else {
                    diagnosticExisting += 1
                    if (existing.matchesPromotedIdentity(item.song)) {
                        diagnosticSameIdentity += 1
                    } else {
                        diagnosticReboundIdentity += 1
                    }
                    if (existing.hasArtworkBitmap()) diagnosticBitmap += 1
                    if (existing.debugRecordedSizeMatches(item)) {
                        diagnosticRecordedSizeMatch += 1
                    } else {
                        diagnosticRecordedSizeMismatch += 1
                    }
                    if (existing.debugArtworkDisplayListPresent()) {
                        diagnosticArtworkDisplayListPresent += 1
                    } else {
                        diagnosticArtworkDisplayListMissing += 1
                    }
                }
            }
            val holder = existing ?: NodeHolder(
                slotId = item.slotId,
                invalidateContent = { invalidationOwner.invalidate(VirtualListRenderInvalidation.content()) },
                invalidateArtwork = { slotId ->
                    invalidationOwner.invalidate(VirtualListRenderInvalidation.artwork(slotId))
                },
            ).also {
                holders[item.slotId] = it
            }
            if (!settledOrderChanged && drawIndex < previousSize && settledDrawOrder[drawIndex] !== holder) {
                settledOrderChanged = true
            }
            if (drawIndex < settledDrawOrder.size) {
                settledDrawOrder[drawIndex] = holder
            } else {
                settledDrawOrder.add(holder)
            }
            // Visibility changes alter which children are recorded in the population root,
            // even when all physical slots and their order stay unchanged.
            if (holder.drawContentEnabled != item.drawContentEnabled) settledOrderChanged = true
            holder.attachmentGeneration = generation
            holder.setSharedArtworkSuppressed(
                promotedSharedElementId.isNotBlank() &&
                    item.sharedCoverElementId == promotedSharedElementId
            )
            holder.update(item, frame)
            holder.onPhysicalAttach()
            marqueeOverflow = marqueeOverflow || holder.hasMarqueeOverflow()
        }
        if (TransitionPerfTrace.isActive()) {
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} APPLY_FRAME mode=$presentationMode items=${frame.items.size} existing=$diagnosticExisting " +
                    "sameIdentity=$diagnosticSameIdentity reboundIdentity=$diagnosticReboundIdentity " +
                    "new=$diagnosticNew bitmap=$diagnosticBitmap temporaryReattach=0 " +
                    "recordedSizeMatch=$diagnosticRecordedSizeMatch recordedSizeMismatch=$diagnosticRecordedSizeMismatch " +
                    "artworkDisplayListPresent=$diagnosticArtworkDisplayListPresent " +
                    "artworkDisplayListMissing=$diagnosticArtworkDisplayListMissing " +
                    "holders=${holders.size}",
            )
        }
        while (settledDrawOrder.size > frame.items.size) {
            settledDrawOrder.removeAt(settledDrawOrder.lastIndex)
        }
        // baseline implementation ArtworkImageNode distinguishes RecyclerView/VirtualList temporary detach from a real
        // window detach: onStartTemporaryDetach() arms a flag and onDetachedFromWindow() skips
        // bitmap/provider-wrapper cleanup while that flag is set. Keep the physical NodeHolder for
        // every allocated slot for the same reason. VirtualListPhysicalSlotPool is bounded by the
        // attached/recycled holder population; when a slot is reused for another song, holder.update()
        // changes the provider identity and NodeArtworkActor releases the old wrapper there. A real
        // runtime teardown still releases every holder in clearHoldersPreservingHost().
        //
        // Releasing unseen holders here used to turn an ordinary scroll-off into a permanent detach:
        // the accepted bitmap lease vanished, then a reverse scroll depended on provider-cache
        // residency/source work to recreate pixels. That is exactly the lifecycle Reference avoids.
        cachedMarqueeOverflow = marqueeOverflow
        return settledOrderChanged
    }

    private fun detachPromotedHolderFromPoolIfNeeded(frame: VirtualListSettledNodeFrame) {
        val holder = promotedSharedHolder ?: return
        val slotId = promotedSharedSlotId
        if (slotId < 0 || holders[slotId] !== holder) return
        val slotItem = frame.items.firstOrNull { it.slotId == slotId }
        val stillOwnsSourceRow = slotItem != null &&
            slotItem.sharedCoverElementId == promotedSharedElementId
        if (stillOwnsSourceRow) return

        // The source provider no longer publishes this physical slot. Pin the original holder out
        // of the recycle map before that slot can be rebound to a detail-row item; its artwork actor
        // and provider leases now belong exclusively to the promoted header View until return.
        holders.remove(slotId)
        settledDrawOrder.remove(holder)
        outerDrawOrder.remove(holder)
    }

    fun publishSettledFrame(frame: VirtualListSettledNodeFrame) {
        settledMotionInvalidationPending = false
        viewGroupPresentationSuspended = false
        val previousMode = presentationMode
        val committingOuterLayoutRole = outerPresentationFrame != null
        // Completing/cancelling GenericPivot ends only the CURRENT/RETAINED LayoutRes transaction.
        // Keep the last resident holder-content signature across VirtualList transactions:
        // pool survives the transition object, so a reverse/new transition can bind fresh LayoutRes
        // without asking the provider to bind the same item-holder content again.
        val settledOrderChanged = applyFrame(frame)
        val outerPopulationCoversSettled = committingOuterLayoutRole && settledDrawOrder.all { settledHolder ->
            outerDrawOrder.any { outerHolder -> outerHolder === settledHolder }
        }
        outerDrawOrder.clear()
        settledPresentationFrame = frame
        outerPresentationFrame = null
        presentationMode = VirtualListPresentationMode.ATTACHED
        if (previousMode == VirtualListPresentationMode.NONE) {
            presentationEpoch += 1L
            pendingFirstDrawTrace = true
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} " +
                    presentationEdgeSummary("ATTACH_SETTLED", previousMode, settledDrawOrder, frame.items.size),
            )
        } else if (committingOuterLayoutRole && TransitionPerfTrace.isActive()) {
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} " +
                    presentationEdgeSummary("COMMIT_LAYOUT_ROLE", previousMode, settledDrawOrder, frame.items.size),
            )
        }
        if (
            !outerPopulationCoversSettled &&
            (previousMode == VirtualListPresentationMode.NONE || settledOrderChanged)
        ) {
            presentationStructureGeneration += 1L
        }
        invalidationOwner.invalidate()
    }

    /**
     * Bind the two Reference-style LayoutRes records once to the existing physical holder population.
     * GenericPivot is motion state on that population, not a second presentation owner.
     */
    fun publishOuterFrame(frame: VirtualListOuterTransitionNodeFrame) {
        val bindStarted = if (TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        settledMotionInvalidationPending = false
        viewGroupPresentationSuspended = false
        val previousMode = presentationMode
        val outerWasAlreadyBound = outerPresentationFrame != null
        val contentSignature = frame.contentSignature(promotedSharedElementId)
        val previousContentSignature = lastOuterContentSignature
        val contentChanged = outerContentPublicationGate.differsFromPublished(contentSignature)
        val residentContentReady = frame.items.all { motion ->
            holders[motion.holderItem.slotId]
                ?.boundItem()
                ?.outerHolderContentSignature() == motion.holderItem.outerHolderContentSignature()
        }
        val publicationAction = resolveVirtualListOuterPublicationAction(
            outerAlreadyBound = outerWasAlreadyBound,
            contentChanged = contentChanged,
            residentContentReady = residentContentReady,
            motionActive = !frame.contentBindAllowedProvider(),
        )
        if (publicationAction == VirtualListOuterPublicationAction.DEFER_UNTIL_PREPARED) {
            // A VirtualList GenericPivot tick is never allowed to turn into provider/content bind.
            // Keep the currently attached LayoutRes/content owner untouched; the preparation
            // barrier will republish this frame after the required resident content exists.
            if (TransitionPerfTrace.isActive()) {
                TransitionPerfTrace.mark(
                    "outer_publication_deferred",
                    "items=${frame.items.size} holders=${holders.size} outerBound=$outerWasAlreadyBound",
                )
            }
            return
        }
        if (publicationAction == VirtualListOuterPublicationAction.SHELL_ONLY_REUSE) {
            // Unrelated Compose snapshot changes may revisit the endpoint publication call site
            // while the gesture is still using the exact same holder/LayoutRes population. Keep the
            // already-bound holder content and only replace the frame shell so the latest callbacks
            // and motion-provider lambdas remain authoritative.
            outerPresentationFrame = frame
            presentationMode = VirtualListPresentationMode.ATTACHED
            if (TransitionPerfTrace.isActive()) {
                TransitionPerfTrace.mark(
                    "outer_publication_reuse",
                    "items=${frame.items.size} holders=${holders.size}",
                )
            }
            frame.onPopulationPublished?.invoke()
            return
        }
        if (
            publicationAction == VirtualListOuterPublicationAction.FULL_CONTENT_BIND &&
            previousContentSignature != null &&
            TransitionPerfTrace.isActive()
        ) {
            TransitionPerfTrace.mark(
                "outer_publication_rebind",
                describeOuterContentSignatureChange(previousContentSignature, contentSignature) +
                    " residentReady=$residentContentReady outerBound=$outerWasAlreadyBound",
            )
        }
        lastOuterContentSignature = contentSignature
        val holderVisibilityChanged = if (
            publicationAction == VirtualListOuterPublicationAction.FULL_CONTENT_BIND
        ) {
            applyFrame(frame.holderFrame)
        } else {
            false
        }
        val previousSize = outerDrawOrder.size
        var outerOrderChanged = previousSize != frame.items.size || holderVisibilityChanged
        outerDrawOrder.ensureCapacity(frame.items.size)
        var marqueeOverflow = false
        var drawIndex = 0
        for (motion in frame.items) {
            val holder = holders[motion.holderItem.slotId] ?: continue
            if (!outerOrderChanged && drawIndex < previousSize && outerDrawOrder[drawIndex] !== holder) {
                outerOrderChanged = true
            }
            if (drawIndex < outerDrawOrder.size) {
                outerDrawOrder[drawIndex] = holder
            } else {
                outerDrawOrder.add(holder)
            }
            drawIndex += 1
            holder.bindOuterLayout(motion)
            marqueeOverflow = marqueeOverflow || holder.hasMarqueeOverflow()
        }
        if (drawIndex != frame.items.size) outerOrderChanged = true
        while (outerDrawOrder.size > drawIndex) {
            outerDrawOrder.removeAt(outerDrawOrder.lastIndex)
        }
        cachedMarqueeOverflow = marqueeOverflow
        if (
            publicationAction == VirtualListOuterPublicationAction.LAYOUT_ONLY_REBIND &&
            TransitionPerfTrace.isActive()
        ) {
            TransitionPerfTrace.mark(
                "outer_publication_layout_only",
                "items=${frame.items.size} holders=${holders.size}",
            )
        }
        // Keep the last settled frame as the stable owner snapshot. `outerPresentationFrame` is
        // only the second LayoutRes role/progress attached to the same holder population.
        outerPresentationFrame = frame
        presentationMode = VirtualListPresentationMode.ATTACHED
        // Commit only after the LayoutRes/holder population is actually bound. In particular,
        // DEFER_UNTIL_PREPARED above must leave this signature pending so the prepared republish
        // still sees contentChanged=true instead of falling through to SHELL_ONLY_REUSE.
        outerContentPublicationGate.commit(contentSignature)
        // Bind and prepare before signalling readiness. The first animated draw must not also
        // compile a cold population's text/artwork display lists. Shared holders remain live;
        // this only prepares their already-bound content, never replaces the presentation owner.
        if (
            publicationAction == VirtualListOuterPublicationAction.FULL_CONTENT_BIND &&
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        ) {
            TransitionPerfTrace.measure(TransitionPerfStage.NODE_PREFLIGHT) {
                val paint = Paint(Paint.ANTI_ALIAS_FLAG)
                val note = frame.holderFrame.resources.getDrawable(R.drawable.ic_music_note, null)?.mutate()
                for (holder in outerDrawOrder) {
                    holder.prepareViewGroupContent(frame.holderFrame, paint, note, 0L, 0f)
                }
            }
        }
        if (bindStarted != 0L) TransitionPerfTrace.recordDuration(
            TransitionPerfStage.POPULATION_BIND, System.nanoTime() - bindStarted)
        frame.onPopulationPublished?.invoke()
        if (previousMode == VirtualListPresentationMode.NONE) {
            presentationEpoch += 1L
            pendingFirstDrawTrace = true
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} " +
                    presentationEdgeSummary("ATTACH_LAYOUT_ROLES", previousMode, outerDrawOrder, frame.items.size),
            )
        } else if (!outerWasAlreadyBound && TransitionPerfTrace.isActive()) {
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} " +
                    presentationEdgeSummary("BIND_LAYOUT_ROLES", previousMode, outerDrawOrder, frame.items.size),
            )
        }
        if (!outerWasAlreadyBound || outerOrderChanged) {
            presentationStructureGeneration += 1L
        }
        invalidationOwner.invalidate()
    }

    fun invalidatePresentation() {
        settledMotionInvalidationPending = false
        invalidationOwner.invalidate(VirtualListRenderInvalidation.content())
    }

    fun invalidateParentTransform() {
        invalidationOwner.invalidate(VirtualListRenderInvalidation.parentTransform())
    }

    fun invalidateSettledMotionPresentation() {
        settledMotionInvalidationPending = true
        invalidationOwner.invalidate(VirtualListRenderInvalidation.motion())
    }

    fun consumeSettledMotionInvalidation(): Boolean {
        val pending = settledMotionInvalidationPending
        settledMotionInvalidationPending = false
        return pending
    }

    /**
     * Hide the real child-View population while a compatibility renderer owns internal pinch/zoom,
     * but preserve the current holder/LayoutRes/artwork state.  The next settled/outer publication
     * atomically resumes this same population instead of CLEAR -> NONE -> rebuild.
     */
    fun suspendViewGroupPresentationPreservingHolders() {
        if (viewGroupPresentationSuspended) return
        viewGroupPresentationSuspended = true
        invalidationOwner.invalidate()
    }

    /**
     * Prepare the exact current holder population for a real Android ViewGroup host.
     *
     * Unlike [drawPresentation], this method never submits the population through a synthetic root
     * RenderNode. Each returned holder contains a local content RenderNode and the resolved View
     * properties for the current frame. The caller keeps one real child View per slot and applies
     * these primitive properties directly.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareViewGroupContent(rowPaint: Paint, noteDrawable: android.graphics.drawable.Drawable?) {
        if (viewGroupPresentationSuspended || presentationMode == VirtualListPresentationMode.NONE) return
        val outer = outerPresentationFrame
        val frame = outer?.holderFrame ?: settledPresentationFrame ?: return
        val order = if (outer != null) outerDrawOrder else settledDrawOrder
        val elapsed = if (frame.marqueeAllowed && cachedMarqueeOverflow) LongTextMotionState.marqueeElapsedMs else 0L
        for (holder in order) {
            holder.prepareViewGroupContent(frame, rowPaint, noteDrawable, elapsed, 42.5f * frame.density)
        }
    }

    private fun prepareSettledCustomViewGroupState(
        slotId: Int,
        record: PowerCustomRecord,
        remainder: Float,
        offsetX: Float,
        offsetY: Float,
    ): VirtualListCustomViewGroupHolderState? {
        val binding = record.currentBinding ?: return null
        val layout = record.currentLayout ?: return null
        if (!layout.drawContentEnabled) return null
        val position = layout.position
        if (position.isEmpty()) return null
        val width = position.width.coerceAtLeast(1)
        val height = position.height.coerceAtLeast(1)
        return record.state.apply {
            this.slotId = slotId
            drawOrderGroup = 1
            drawOrderIndex = binding.index
            this.width = width
            this.height = height
            left = offsetX + position.bounds.left
            top = offsetY + position.bounds.top - remainder
            scaleX = position.scaleX
            scaleY = position.scaleY
            alpha = position.alpha.coerceIn(0f, 1f)
            dualLayout = false
            dirtyBits = record.presentation.update(
                width = width,
                height = height,
                scaleX = scaleX,
                scaleY = scaleY,
                alpha = alpha,
            )
            this.binding = binding
        }
    }

    /**
     * Resolve one heterogeneous physical holder from its two endpoint LayoutRes records. The content binding
     * is stable; only the outer View properties change while GenericPivot is running.
     */
    private fun prepareOuterCustomViewGroupState(
        slotId: Int,
        record: PowerCustomRecord,
        motionSample: VirtualListOuterTransitionMotionSample,
        offsetX: Float,
        offsetY: Float,
    ): VirtualListCustomViewGroupHolderState? {
        val currentLayout = record.currentLayout
        val retainedLayout = record.retainedLayout
        val binding = when {
            currentLayout != null -> record.currentBinding
            retainedLayout != null -> record.retainedBinding
            else -> null
        } ?: return null
        if (currentLayout?.drawContentEnabled != true && retainedLayout?.drawContentEnabled != true) return null

        val dual = currentLayout != null && retainedLayout != null
        when {
            dual -> interpolateOuterLayoutInto(
                source = record.retainedMutableLayout,
                target = record.currentMutableLayout,
                progress = motionSample.currentFraction,
                out = record.resolved,
            )
            currentLayout != null -> sceneTransformedLayoutInto(
                base = record.currentMutableLayout,
                scale = motionSample.currentScale,
                alpha = motionSample.currentAlpha,
                pivotX = motionSample.currentPivotX,
                pivotY = motionSample.currentPivotY,
                out = record.resolved,
            )
            retainedLayout != null -> sceneTransformedLayoutInto(
                base = record.retainedMutableLayout,
                scale = motionSample.retainedScale,
                alpha = motionSample.retainedAlpha,
                pivotX = motionSample.retainedPivotX,
                pivotY = motionSample.retainedPivotY,
                out = record.resolved,
            )
            else -> return null
        }
        val resolved = record.resolved
        val resolvedWidth = (resolved.right - resolved.left).coerceAtLeast(1)
        val resolvedHeight = (resolved.bottom - resolved.top).coerceAtLeast(1)
        val baseLayout = currentLayout ?: retainedLayout ?: return null
        val basePosition = baseLayout.exactViewportPosition()
        val baseWidth = basePosition.width.coerceAtLeast(1)
        val baseHeight = basePosition.height.coerceAtLeast(1)
        val committedWidth = if (dual) resolvedWidth else baseWidth
        val committedHeight = if (dual) resolvedHeight else baseHeight
        val committedScaleX = if (dual) {
            resolved.scaleX
        } else {
            (resolvedWidth.toFloat() / baseWidth) * resolved.scaleX
        }
        val committedScaleY = if (dual) {
            resolved.scaleY
        } else {
            (resolvedHeight.toFloat() / baseHeight) * resolved.scaleY
        }
        val committedAlpha = resolved.alpha.coerceIn(0f, 1f)
        return record.state.apply {
            this.slotId = slotId
            drawOrderGroup = when {
                currentLayout != null && retainedLayout != null -> 1
                currentLayout != null -> 2
                else -> 0
            }
            drawOrderIndex = binding.index
            width = committedWidth
            height = committedHeight
            left = offsetX + resolved.left
            top = offsetY + resolved.top
            scaleX = committedScaleX
            scaleY = committedScaleY
            alpha = committedAlpha
            dualLayout = dual
            dirtyBits = if (dual) {
                VIRTUAL_LIST_LAYOUT_DIRTY_SIZE or
                    VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_X or
                    VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_Y or
                    VIRTUAL_LIST_LAYOUT_DIRTY_ALPHA
            } else {
                record.presentation.update(
                    width = committedWidth,
                    height = committedHeight,
                    scaleX = committedScaleX,
                    scaleY = committedScaleY,
                    alpha = committedAlpha,
                )
            }
            this.binding = binding
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareViewGroupPresentation(
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        externalTransform: RetainedSceneItemTransform? = null,
    ): VirtualListViewGroupPresentationFrame {
        val out = viewGroupPresentationFrame
        out.reset()
        out.structureGeneration = presentationStructureGeneration

        if (viewGroupPresentationSuspended) return out

        if (pendingFirstDrawTrace) {
            pendingFirstDrawTrace = false
            val order = if (presentationMode == VirtualListPresentationMode.NONE) {
                emptyList()
            } else if (outerPresentationFrame != null) {
                outerDrawOrder
            } else {
                settledDrawOrder
            }
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} " +
                    presentationEdgeSummary("FIRST_VIEWGROUP_FRAME", presentationMode, order, order.size) +
                    " fixed=$fixedPresentationHost viewportValid=$presentationViewportValid" +
                    " viewport=${presentationViewportWidth.toInt()}x${presentationViewportHeight.toInt()}",
            )
        }

        if (fixedPresentationHost && !presentationViewportValid) return out

        val dx = if (fixedPresentationHost) presentationViewportRootLeft - presentationHostRootLeft else 0f
        val dy = if (fixedPresentationHost) presentationViewportRootTop - presentationHostRootTop else 0f
        val externalScale = externalTransform?.scaleProvider?.invoke()?.coerceIn(0.5f, 1.5f) ?: 1f
        val externalAlpha = externalTransform?.alphaProvider?.invoke()?.coerceIn(0f, 1f) ?: 1f
        val externalPresentationActive = externalTransform != null &&
            (kotlin.math.abs(externalScale - 1f) > 0.0001f || externalAlpha < 0.9999f)
        if (
            fixedPresentationHost &&
            presentationViewportClipEnabled &&
            !externalPresentationActive
        ) {
            out.clipEnabled = true
            out.clipLeft = dx
            out.clipTop = dy + presentationViewportClipTopInset.coerceAtMost(presentationViewportHeight)
            out.clipRight = dx + presentationViewportWidth
            out.clipBottom = dy + presentationViewportHeight
        }

        if (presentationMode == VirtualListPresentationMode.ATTACHED) {
            val outer = outerPresentationFrame
            if (outer == null) {
                val frame = settledPresentationFrame ?: return out
                val remainder = frame.scrollRemainderProvider().toFloat()
                val marqueeElapsedMs = if (frame.marqueeAllowed && cachedMarqueeOverflow) {
                    LongTextMotionState.marqueeElapsedMs
                } else {
                    0L
                }
                val marqueeSpeedPxPerSecond = 42.5f * frame.density
                for (holder in settledDrawOrder) {
                    holder.prepareSettledViewGroupState(
                        frame = frame,
                        remainder = remainder,
                        offsetX = dx,
                        offsetY = dy,
                        rowPaint = rowPaint,
                        noteDrawable = noteDrawable,
                        marqueeElapsedMs = marqueeElapsedMs,
                        marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                    )?.let(out.holders::add)
                }
                for ((slotId, record) in powerCustomItems) {
                    prepareSettledCustomViewGroupState(
                        slotId = slotId,
                        record = record,
                        remainder = remainder,
                        offsetX = dx,
                        offsetY = dy,
                    )?.let(out.customHolders::add)
                }
                out.parentPivotX = externalTransform?.pivotX ?: presentationViewportWidth * 0.5f
                out.parentPivotY = externalTransform?.pivotY ?: presentationViewportHeight * 0.5f
                out.parentScale = externalScale
                out.parentAlpha = externalAlpha
            } else {
                sampleOuterTransitionMotion(outer, presentationMotionSample)
                val holderFrame = outer.holderFrame
                val marqueeElapsedMs = if (holderFrame.marqueeAllowed && cachedMarqueeOverflow) {
                    LongTextMotionState.marqueeElapsedMs
                } else {
                    0L
                }
                val marqueeSpeedPxPerSecond = 42.5f * holderFrame.density
                for (holder in outerDrawOrder) {
                    holder.prepareOuterViewGroupState(
                        frame = holderFrame,
                        motionSample = presentationMotionSample,
                        offsetX = dx,
                        offsetY = dy,
                        rowPaint = rowPaint,
                        noteDrawable = noteDrawable,
                        marqueeElapsedMs = marqueeElapsedMs,
                        marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                    )?.let(out.holders::add)
                }
                for ((slotId, record) in powerCustomItems) {
                    prepareOuterCustomViewGroupState(
                        slotId = slotId,
                        record = record,
                        motionSample = presentationMotionSample,
                        offsetX = dx,
                        offsetY = dy,
                    )?.let(out.customHolders::add)
                }
            }
        }
        return out
    }

    /**
     * VirtualList GenericPivot hot path: mutate the two already-bound LayoutRes records and expose
     * only the primitive properties needed by the already-attached physical Views.
     *
     * This deliberately does not prepare content, inspect detached prewarm holders, reconcile child
     * order, allocate a live-slot set, or touch provider identity. Those operations belong to the
     * publication/layout path, not the animation tick.
     */
    fun prepareOuterMotionViewGroupStates(): List<VirtualListViewGroupMotionState>? {
        if (viewGroupPresentationSuspended || presentationMode != VirtualListPresentationMode.ATTACHED) return null
        val frame = outerPresentationFrame ?: return null
        if (fixedPresentationHost && !presentationViewportValid) return null

        sampleOuterTransitionMotion(frame, presentationMotionSample)
        val dx = if (fixedPresentationHost) presentationViewportRootLeft - presentationHostRootLeft else 0f
        val dy = if (fixedPresentationHost) presentationViewportRootTop - presentationHostRootTop else 0f
        val holderFrame = frame.holderFrame
        val marqueeElapsedMs = if (holderFrame.marqueeAllowed && cachedMarqueeOverflow) {
            LongTextMotionState.marqueeElapsedMs
        } else {
            0L
        }
        val marqueeSpeedPxPerSecond = 42.5f * holderFrame.density
        outerMotionViewStates.clear()
        for (holder in outerDrawOrder) {
            val state = holder.prepareOuterViewGroupMotionState(
                frame = holderFrame,
                motionSample = presentationMotionSample,
                offsetX = dx,
                offsetY = dy,
                marqueeElapsedMs = marqueeElapsedMs,
                marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
            )
            if (state != null) outerMotionViewStates.add(state)
        }
        return outerMotionViewStates
    }

    fun prepareOuterCustomMotionViewGroupStates(): List<VirtualListCustomViewGroupHolderState>? {
        if (viewGroupPresentationSuspended || presentationMode != VirtualListPresentationMode.ATTACHED) return null
        val frame = outerPresentationFrame ?: return null
        if (fixedPresentationHost && !presentationViewportValid) return null

        sampleOuterTransitionMotion(frame, presentationMotionSample)
        val dx = if (fixedPresentationHost) presentationViewportRootLeft - presentationHostRootLeft else 0f
        val dy = if (fixedPresentationHost) presentationViewportRootTop - presentationHostRootTop else 0f
        outerCustomMotionStates.clear()
        for ((slotId, record) in powerCustomItems) {
            prepareOuterCustomViewGroupState(
                slotId = slotId,
                record = record,
                motionSample = presentationMotionSample,
                offsetX = dx,
                offsetY = dy,
            )?.let(outerCustomMotionStates::add)
        }
        return outerCustomMotionStates
    }

    fun prepareSettledMotionViewGroupStates(): List<VirtualListViewGroupMotionState>? {
        if (viewGroupPresentationSuspended || presentationMode != VirtualListPresentationMode.ATTACHED) return null
        if (outerPresentationFrame != null) return null
        if (fixedPresentationHost && !presentationViewportValid) return null
        val frame = settledPresentationFrame ?: return null
        val dx = if (fixedPresentationHost) presentationViewportRootLeft - presentationHostRootLeft else 0f
        val dy = if (fixedPresentationHost) presentationViewportRootTop - presentationHostRootTop else 0f
        val remainder = frame.scrollRemainderProvider().toFloat()
        val marqueeElapsedMs = if (frame.marqueeAllowed && cachedMarqueeOverflow) {
            LongTextMotionState.marqueeElapsedMs
        } else {
            0L
        }
        val marqueeSpeedPxPerSecond = 42.5f * frame.density
        settledMotionViewStates.clear()
        for (holder in settledDrawOrder) {
            holder.prepareSettledViewGroupMotionState(
                frame = frame,
                remainder = remainder,
                offsetX = dx,
                offsetY = dy,
                marqueeElapsedMs = marqueeElapsedMs,
                marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
            )?.let(settledMotionViewStates::add)
        }
        return settledMotionViewStates
    }

    fun prepareSettledCustomMotionViewGroupStates(): List<VirtualListCustomViewGroupHolderState>? {
        if (viewGroupPresentationSuspended || presentationMode != VirtualListPresentationMode.ATTACHED) return null
        if (outerPresentationFrame != null) return null
        if (fixedPresentationHost && !presentationViewportValid) return null
        val frame = settledPresentationFrame ?: return null
        val dx = if (fixedPresentationHost) presentationViewportRootLeft - presentationHostRootLeft else 0f
        val dy = if (fixedPresentationHost) presentationViewportRootTop - presentationHostRootTop else 0f
        val remainder = frame.scrollRemainderProvider().toFloat()
        settledCustomMotionStates.clear()
        for ((slotId, record) in powerCustomItems) {
            prepareSettledCustomViewGroupState(
                slotId = slotId,
                record = record,
                remainder = remainder,
                offsetX = dx,
                offsetY = dy,
            )?.let(settledCustomMotionStates::add)
        }
        return settledCustomMotionStates
    }

    fun settledMotionOwnsPhysicalPresentation(): Boolean =
        !viewGroupPresentationSuspended &&
            presentationMode == VirtualListPresentationMode.ATTACHED &&
            outerPresentationFrame == null &&
            settledPresentationFrame != null

    fun outerMotionOwnsPhysicalPresentation(): Boolean =
        !viewGroupPresentationSuspended &&
            presentationMode == VirtualListPresentationMode.ATTACHED &&
            outerPresentationFrame != null

    /**
     * Warm destination holders without changing the currently visible presentation owner.
     *
     * The retained-list transition model binds/records the next holder population before GenericPivot starts. Raw's
     * provider-only preflight used to leave ordinary destination rows cold, so the first 250 ms
     * transition paid text preparation, artwork binding and child RenderNode recording on its first
     * animated frames. This method prepares only slots which are not part of the currently presented
     * population and deliberately does not touch presentationMode/drawOrder/structureGeneration.
     */
    fun prewarmDetachedFrame(
        frame: VirtualListSettledNodeFrame,
        prewarmKey: VirtualListPrewarmKey,
        onMaterialized: (() -> Unit)? = null,
    ) {
        if (frame.items.isEmpty()) {
            ordinaryPrewarmReadiness.invalidate()
            detachedPrewarmDrawOrder.clear()
            detachedPrewarmFrame = null
            pendingOrdinaryPrewarmMaterializedKey = null
            pendingOrdinaryPrewarmMaterializedCallback = null
            // An empty ordinary population has no physical children left to attach. Treat that as an
            // immediate physical bind ACK so an empty detail/header can still complete endpoint-
            // equivalent preflight instead of waiting for the navigation timeout.
            onMaterialized?.invoke()
            return
        }
        pendingOrdinaryPrewarmMaterializedKey = prewarmKey
        pendingOrdinaryPrewarmMaterializedCallback = onMaterialized
        val expectedOwnership = LinkedHashMap<Int, String>(frame.items.size)
        val residentOwnership = LinkedHashMap<Int, String>(frame.items.size)
        for (item in frame.items) {
            val identity = prewarmIdentity(item.song)
            expectedOwnership[item.slotId] = identity
            holders[item.slotId]
                ?.takeIf { it.matchesPromotedIdentity(item.song) }
                ?.let { residentOwnership[item.slotId] = identity }
        }
        val readySlotIds = ordinaryPrewarmReadiness.readySlotIds(prewarmKey, residentOwnership)
        val previousPreparedKey = ordinaryPrewarmReadiness.debugPreparedKey()
        if (ordinaryPrewarmReadiness.isReady(prewarmKey, residentOwnership)) {
            if (TransitionPerfTrace.isActive()) {
                TransitionPerfTrace.mark(
                    "prewarm_resident_hit",
                    "items=${frame.items.size} holders=${holders.size}",
                )
            }
            if (lastMaterializedOrdinaryPrewarmKey == prewarmKey) {
                val callback = pendingOrdinaryPrewarmMaterializedCallback
                pendingOrdinaryPrewarmMaterializedKey = null
                pendingOrdinaryPrewarmMaterializedCallback = null
                callback?.invoke()
            }
            return
        }
        detachedPrewarmDrawOrder.clear()
        detachedPrewarmFrame = frame
        val rowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        val noteDrawable = frame.resources.getDrawable(R.drawable.ic_music_note, null)?.mutate()
        var diagnosticExisting = 0
        var diagnosticSameIdentity = 0
        var diagnosticReboundIdentity = 0
        var diagnosticNew = 0
        var diagnosticBitmap = 0
        var diagnosticSkippedVisible = 0
        var diagnosticPreparedReuse = 0
        val diagnosticContentDiffs = LinkedHashMap<String, Int>()
        for (item in frame.items) {
            val existing = holders[item.slotId]
            if (existing != null &&
                (settledDrawOrder.contains(existing) || outerDrawOrder.contains(existing))
            ) {
                // Shared stable keys are already hot in the visible population. Mutating their
                // settled LayoutRes during preflight would move current pixels before transition.
                if (TransitionPerfTrace.isActive()) diagnosticSkippedVisible += 1
                continue
            }
            if (TransitionPerfTrace.isActive()) {
                if (existing == null) {
                    diagnosticNew += 1
                } else {
                    diagnosticExisting += 1
                    if (existing.matchesPromotedIdentity(item.song)) {
                        diagnosticSameIdentity += 1
                        val diff = existing.debugPrewarmContentDiff(item, frame)
                        diagnosticContentDiffs[diff] = (diagnosticContentDiffs[diff] ?: 0) + 1
                    } else {
                        diagnosticReboundIdentity += 1
                    }
                    if (existing.hasArtworkBitmap()) diagnosticBitmap += 1
                }
            }
            if (existing != null && item.slotId in readySlotIds) {
                // The endpoint key is identical and this exact slot still owns the content that was
                // already prepared for it. VirtualList keeps that inactive item holder resident;
                // another edge slot rotating to a new adapter position must not make this holder
                // re-run text/artwork RenderNode recording.
                detachedPrewarmDrawOrder += existing
                diagnosticPreparedReuse += 1
                continue
            }
            val holder = existing ?: NodeHolder(
                slotId = item.slotId,
                invalidateContent = { invalidationOwner.invalidate(VirtualListRenderInvalidation.content()) },
                invalidateArtwork = { slotId ->
                    invalidationOwner.invalidate(VirtualListRenderInvalidation.artwork(slotId))
                },
            ).also {
                holders[item.slotId] = it
            }
            holder.update(item, frame)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                holder.prepareSettledRenderNode(
                    frame = frame,
                    remainder = 0f,
                    rowPaint = rowPaint,
                    noteDrawable = noteDrawable,
                    marqueeElapsedMs = 0L,
                    marqueeSpeedPxPerSecond = 0f,
                )
            }
            detachedPrewarmDrawOrder += holder
        }
        // Reference behavior binds the NEXT holder into the same VirtualList before GenericPivot starts. Wake the
        // permanent Android presentation host now so these prepared child Views are attached while
        // the destination is still an inactive layout role, not batch-added on the first OUTER frame.
        ordinaryPrewarmReadiness.markPrepared(prewarmKey, expectedOwnership)
        presentationStructureGeneration += 1L
        invalidationOwner.invalidate(VirtualListRenderInvalidation.content())
        if (TransitionPerfTrace.isActive()) {
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} PREWARM_DETACHED items=${frame.items.size} existing=$diagnosticExisting " +
                    "sameIdentity=$diagnosticSameIdentity reboundIdentity=$diagnosticReboundIdentity " +
                    "new=$diagnosticNew bitmap=$diagnosticBitmap preparedReuse=$diagnosticPreparedReuse " +
                    "skippedVisible=$diagnosticSkippedVisible keyMatch=${previousPreparedKey == prewarmKey} " +
                    "key=$prewarmKey previousKey=$previousPreparedKey " +
                    "contentDiffs=${diagnosticContentDiffs.entries.joinToString(separator = ";") { (diff, count) -> "$count*$diff" }} " +
                    "holders=${holders.size}",
            )
        }
    }

    /**
     * Complete the NEXT-preflight transaction only after the permanent Android host owns all of
     * the prepared ordinary child Views. This is the physical-holder bind boundary; using a temporary
     * Compose draw as the ACK can disappear during a fast gesture release and strand navigation.
     */
    internal fun acknowledgeDetachedOrdinaryPrewarmMaterialized() {
        val key = pendingOrdinaryPrewarmMaterializedKey ?: return
        lastMaterializedOrdinaryPrewarmKey = key
        pendingOrdinaryPrewarmMaterializedKey = null
        val callback = pendingOrdinaryPrewarmMaterializedCallback
        pendingOrdinaryPrewarmMaterializedCallback = null
        callback?.invoke()
    }

    /**
     * Bind heterogeneous NEXT views during provider preflight without composing a second page/list
     * population. The permanent Android host creates these exact holder Views at alpha zero.
     */
    fun prewarmDetachedCustomPopulation(
        publications: List<VirtualListPowerCustomRolePublication>,
        prewarmKey: VirtualListPrewarmKey,
    ) {
        val expectedOwnership = LinkedHashMap<Int, String>(publications.size)
        for (publication in publications) {
            expectedOwnership[publication.slotId] = prewarmCustomIdentity(publication.binding)
        }
        val residentOwnership = LinkedHashMap<Int, String>(detachedPrewarmCustomViewStates.size)
        for (state in detachedPrewarmCustomViewStates) {
            val binding = state.binding ?: continue
            residentOwnership[state.slotId] = prewarmCustomIdentity(binding)
        }
        if (customPrewarmReadiness.isReady(prewarmKey, residentOwnership)) return
        detachedPrewarmCustomViewStates.clear()
        for (publication in publications) {
            val position = publication.layout.exactViewportPosition()
            if (position.isEmpty()) continue
            detachedPrewarmCustomViewStates += VirtualListCustomViewGroupHolderState().apply {
                slotId = publication.slotId
                drawOrderGroup = 3
                drawOrderIndex = publication.binding.index
                width = position.width.coerceAtLeast(1)
                height = position.height.coerceAtLeast(1)
                left = 0f
                top = 0f
                scaleX = 1f
                scaleY = 1f
                alpha = 0f
                dirtyBits = VIRTUAL_LIST_LAYOUT_DIRTY_SIZE or
                    VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_X or
                    VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_Y or
                    VIRTUAL_LIST_LAYOUT_DIRTY_ALPHA
                dualLayout = false
                binding = publication.binding
            }
        }
        customPrewarmReadiness.markPrepared(prewarmKey, expectedOwnership)
        presentationStructureGeneration += 1L
        invalidationOwner.invalidate(VirtualListRenderInvalidation.content())
    }

    private fun prewarmIdentity(song: AudioFile): String =
        if (song.id != 0L) "id:${song.id}" else "path:${song.path}"

    private fun prewarmCustomIdentity(binding: VirtualListPowerCustomBinding): String =
        "${prewarmIdentity(binding.song)}|${binding.index}|provider:${System.identityHashCode(binding.provider)}"

    /**
     * Preflighted NEXT holders which must already be real children of the permanent presentation
     * ViewGroup before GenericPivot starts. They stay alpha-zero until OUTER publishes the live
     * transition role; the same child object is then updated in place instead of being added there.
     */
    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareDetachedPrewarmViewGroupStates(
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
    ): List<VirtualListViewGroupHolderState> {
        detachedPrewarmViewStates.clear()
        val frame = detachedPrewarmFrame ?: return detachedPrewarmViewStates
        if (detachedPrewarmDrawOrder.isEmpty()) return detachedPrewarmViewStates
        if (fixedPresentationHost && !presentationViewportValid) return detachedPrewarmViewStates

        val dx = if (fixedPresentationHost) presentationViewportRootLeft - presentationHostRootLeft else 0f
        val dy = if (fixedPresentationHost) presentationViewportRootTop - presentationHostRootTop else 0f
        for (holder in detachedPrewarmDrawOrder) {
            holder.prepareSettledViewGroupState(
                frame = frame,
                remainder = 0f,
                offsetX = dx,
                offsetY = dy,
                rowPaint = rowPaint,
                noteDrawable = noteDrawable,
                marqueeElapsedMs = 0L,
                marqueeSpeedPxPerSecond = 0f,
            )?.let { state ->
                state.alpha = 0f
                detachedPrewarmViewStates += state
            }
        }
        return detachedPrewarmViewStates
    }

    fun prepareDetachedPrewarmCustomViewGroupStates(): List<VirtualListCustomViewGroupHolderState> =
        detachedPrewarmCustomViewStates

    fun presentationResources(): Resources? = if (presentationMode == VirtualListPresentationMode.NONE) {
        null
    } else {
        outerPresentationFrame?.holderFrame?.resources ?: settledPresentationFrame?.resources
    }

    fun presentationHasMarquee(): Boolean {
        if (viewGroupPresentationSuspended) return false
        if (!cachedMarqueeOverflow) return false
        if (presentationMode == VirtualListPresentationMode.NONE) return false
        return outerPresentationFrame?.holderFrame?.marqueeAllowed
            ?: (settledPresentationFrame?.marqueeAllowed == true)
    }

    fun presentationNeedsFrameCallback(): Boolean {
        if (presentationMode == VirtualListPresentationMode.NONE || viewGroupPresentationSuspended) return false
        // GenericPivot is an attached LayoutRes role, so the same permanent host owns its vsync.
        if (outerPresentationFrame != null) return true
        return hasAnimatingArtwork() || presentationHasMarquee()
    }

    /** True only for direct user interaction on the settled population (scroll/drag/etc.). */
    fun presentationInteractionActive(): Boolean =
        settledPresentationFrame?.items?.any { it.interactionActive } == true

    fun drawPresentation(
        canvas: android.graphics.Canvas,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        externalTransform: RetainedSceneItemTransform? = null,
    ) {
        if (pendingFirstDrawTrace) {
            pendingFirstDrawTrace = false
            val order = if (presentationMode == VirtualListPresentationMode.NONE) {
                emptyList()
            } else if (outerPresentationFrame != null) {
                outerDrawOrder
            } else {
                settledDrawOrder
            }
            AppLogger.i(
                HANDOFF_TRACE_TAG,
                "${TransitionPerfTrace.exportContext()} " +
                    presentationEdgeSummary("FIRST_DRAW", presentationMode, order, order.size) +
                    " fixed=$fixedPresentationHost viewportValid=$presentationViewportValid" +
                    " viewport=${presentationViewportWidth.toInt()}x${presentationViewportHeight.toInt()}",
            )
        }
        if (!fixedPresentationHost) {
            drawPresentationLocal(canvas, rowPaint, noteDrawable, externalTransform)
            return
        }
        if (!presentationViewportValid) return

        val dx = presentationViewportRootLeft - presentationHostRootLeft
        val dy = presentationViewportRootTop - presentationHostRootTop
        canvas.save()
        if (presentationViewportClipEnabled) {
            val clipTop = dy + presentationViewportClipTopInset.coerceAtMost(presentationViewportHeight)
            canvas.clipRect(
                dx,
                clipTop,
                dx + presentationViewportWidth,
                dy + presentationViewportHeight,
            )
        }
        canvas.translate(dx, dy)
        drawPresentationLocal(canvas, rowPaint, noteDrawable, externalTransform)
        canvas.restore()
    }

    private fun drawPresentationLocal(
        canvas: android.graphics.Canvas,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        externalTransform: RetainedSceneItemTransform?,
    ) {
        val start = if (TransitionPerfTrace.isActive()) System.nanoTime() else 0L
        try {
            drawPreparedPresentation(canvas, rowPaint, noteDrawable, externalTransform)
        } finally {
            if (start != 0L) TransitionPerfTrace.recordDuration(TransitionPerfStage.LIST_DRAW, System.nanoTime() - start)
        }
    }

    private fun drawPreparedPresentation(
        canvas: android.graphics.Canvas,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        externalTransform: RetainedSceneItemTransform?,
    ) {
        if (presentationMode == VirtualListPresentationMode.NONE) return
        val usePopulationRenderNode = VirtualListHolderDisplayListPolicy.shouldUseRenderNode(
            apiLevel = Build.VERSION.SDK_INT,
            hardwareAccelerated = canvas.isHardwareAccelerated,
        )
        val outer = outerPresentationFrame
        if (outer == null) {
                val frame = settledPresentationFrame ?: return
                val remainder = frame.scrollRemainderProvider().toFloat()
                val marqueeElapsedMs = if (frame.marqueeAllowed && cachedMarqueeOverflow) {
                    LongTextMotionState.marqueeElapsedMs
                } else {
                    0L
                }
                val marqueeSpeedPxPerSecond = 42.5f * frame.density
                if (usePopulationRenderNode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val population = api29PopulationDisplayList
                        ?: Api29PopulationDisplayList().also { api29PopulationDisplayList = it }
                    population.drawSettled(
                        canvas = canvas,
                        structureGeneration = presentationStructureGeneration,
                        holders = settledDrawOrder,
                        frame = frame,
                        remainder = remainder,
                        rowPaint = rowPaint,
                        noteDrawable = noteDrawable,
                        marqueeElapsedMs = marqueeElapsedMs,
                        marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                        externalTransform = externalTransform,
                    )
                    return
                }
                for (holder in settledDrawOrder) {
                    holder.drawSettled(
                        canvas = canvas,
                        frame = frame,
                        remainder = remainder,
                        rowPaint = rowPaint,
                        noteDrawable = noteDrawable,
                        marqueeElapsedMs = marqueeElapsedMs,
                        marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                    )
                }
        } else {
                sampleOuterTransitionMotion(outer, presentationMotionSample)
                val holderFrame = outer.holderFrame
                val marqueeElapsedMs = if (holderFrame.marqueeAllowed && cachedMarqueeOverflow) {
                    LongTextMotionState.marqueeElapsedMs
                } else {
                    0L
                }
                val marqueeSpeedPxPerSecond = 42.5f * holderFrame.density
                if (usePopulationRenderNode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val population = api29PopulationDisplayList
                        ?: Api29PopulationDisplayList().also { api29PopulationDisplayList = it }
                    population.drawOuter(
                        canvas = canvas,
                        structureGeneration = presentationStructureGeneration,
                        holders = outerDrawOrder,
                        frame = holderFrame,
                        motionSample = presentationMotionSample,
                        rowPaint = rowPaint,
                        noteDrawable = noteDrawable,
                        marqueeElapsedMs = marqueeElapsedMs,
                        marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                    )
                    return
                }
                for (holder in outerDrawOrder) {
                    holder.drawOuterTransition(
                        canvas = canvas,
                        frame = holderFrame,
                        motionSample = presentationMotionSample,
                        rowPaint = rowPaint,
                        noteDrawable = noteDrawable,
                        marqueeElapsedMs = marqueeElapsedMs,
                        marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                    )
                }
        }
    }

    fun advancePresentation(frameTimeNanos: Long): Boolean {
        if (presentationMode == VirtualListPresentationMode.NONE) return false
        return if (outerPresentationFrame != null) {
            // The transition holder is the same live holder used by the settled list.  Keep its
            // artwork reveal/replacement clock moving while GenericPivot owns the second LayoutRes;
            // otherwise
            // a bitmap accepted during the transition remains recorded at alpha=0 and only starts to
            // appear after the endpoint role commit. Keep the artwork view attached (temporary
            // detach semantics), so both the pivot transform and its drawable animation advance on
            // every frame.
            advanceArtwork(frameTimeNanos)
            true
        } else {
            advanceArtwork(frameTimeNanos)
        }
    }

    fun clearPresentation() {
        val previousMode = presentationMode
        settledPresentationFrame = null
        outerPresentationFrame = null
        presentationMode = VirtualListPresentationMode.NONE
        if (previousMode != VirtualListPresentationMode.NONE) {
            presentationEpoch += 1L
            pendingFirstDrawTrace = true
            AppLogger.i(HANDOFF_TRACE_TAG, "CLEAR epoch=$presentationEpoch from=$previousMode holders=${holders.size}")
        }
        cachedMarqueeOverflow = false
        invalidationOwner.invalidate()
    }

    fun clearHoldersPreservingHost() {
        holders.values.forEach(NodeHolder::release)
        holders.clear()
        powerItems.clear()
        powerCustomItems.clear()
        lastPowerCustomActiveSlots.clear()
        detachedPrewarmDrawOrder.clear()
        detachedPrewarmViewStates.clear()
        detachedPrewarmCustomViewStates.clear()
        detachedPrewarmFrame = null
        ordinaryPrewarmReadiness.invalidate()
        customPrewarmReadiness.invalidate()
        outerContentPublicationGate.invalidate()
        lastOuterContentSignature = null
        outerCustomMotionStates.clear()
        powerItemFrameId = Int.MIN_VALUE
        powerCurrentFrame = null
        powerRetainedFrame = null
        promotedSharedHolder?.release()
        promotedSharedHolder = null
        promotedSharedSlotId = -1
        promotedSharedElementId = ""
        physicalSharedHost?.clearPromotedSlot()
        settledDrawOrder.clear()
        outerDrawOrder.clear()
        settledPresentationFrame = null
        outerPresentationFrame = null
        presentationMode = VirtualListPresentationMode.NONE
        cachedMarqueeOverflow = false
        coverTargetOwner.clearAll()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            api29PopulationDisplayList?.release()
        }
        api29PopulationDisplayList = null
        presentationStructureGeneration += 1L
        invalidationOwner.invalidate(VirtualListRenderInvalidation.content())
    }

    fun hasAnimatingArtwork(): Boolean = holders.values.any(NodeHolder::isAnimatingArtwork)

    fun advanceArtwork(frameTimeNanos: Long): Boolean {
        var stillAnimating = false
        for (holder in holders.values) {
            stillAnimating = holder.advanceArtwork(frameTimeNanos) || stillAnimating
        }
        return stillAnimating
    }

    fun clear() {
        clearHoldersPreservingHost()
        invalidationOwner.clear()
    }

    private fun presentationEdgeSummary(
        event: String,
        from: VirtualListPresentationMode,
        order: List<NodeHolder>,
        requestedItems: Int,
    ): String {
        var bitmapCount = 0
        var requestCount = 0
        var animatingCount = 0
        order.forEach { holder ->
            if (holder.hasArtworkBitmap()) bitmapCount += 1
            if (holder.hasArtworkRequest()) requestCount += 1
            if (holder.isAnimatingArtwork()) animatingCount += 1
        }
        return "$event epoch=$presentationEpoch from=$from to=$presentationMode requested=$requestedItems" +
            " order=${order.size} holders=${holders.size} bitmap=$bitmapCount request=$requestCount" +
            " animating=$animatingCount structure=$presentationStructureGeneration"
    }
}


/**
 * API29+ retained parent display list for the complete physical VirtualList holder population.
 *
 * Android ViewGroup/HWUI records child RenderNode references once; property animation mutates the
 * child nodes without making the parent issue N drawRenderNode commands on every vsync. Raw's v45
 * holder nodes were retained individually but the Compose host still replayed one draw command per
 * holder per frame. This root closes that architectural gap: population/order changes re-record the
 * parent once, while scroll/marquee/GenericPivot frames only update child properties and submit one
 * root RenderNode.
 */
@android.annotation.TargetApi(Build.VERSION_CODES.Q)
private class Api29PopulationDisplayList {
    private val rootNode = RenderNode("raw-virtuallist-population")
    private val children = ArrayList<RenderNode>()
    private var recordedStructureGeneration = Long.MIN_VALUE
    private var recordedWidth = -1
    private var recordedHeight = -1

    fun drawSettled(
        canvas: android.graphics.Canvas,
        structureGeneration: Long,
        holders: List<NodeHolder>,
        frame: VirtualListSettledNodeFrame,
        remainder: Float,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
        externalTransform: RetainedSceneItemTransform?,
    ) {
        val structureChanged = needsStructureRecord(canvas, structureGeneration)
        if (structureChanged) children.clear()
        for (holder in holders) {
            val node = holder.prepareSettledRenderNode(
                frame = frame,
                remainder = remainder,
                rowPaint = rowPaint,
                noteDrawable = noteDrawable,
                marqueeElapsedMs = marqueeElapsedMs,
                marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
            )
            if (structureChanged && node != null) children.add(node)
        }
        drawRoot(canvas, structureGeneration, structureChanged, externalTransform)
    }

    fun drawOuter(
        canvas: android.graphics.Canvas,
        structureGeneration: Long,
        holders: List<NodeHolder>,
        frame: VirtualListSettledNodeFrame,
        motionSample: VirtualListOuterTransitionMotionSample,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ) {
        val structureChanged = needsStructureRecord(canvas, structureGeneration)
        if (structureChanged) children.clear()
        for (holder in holders) {
            val node = holder.prepareOuterRenderNode(
                frame = frame,
                motionSample = motionSample,
                rowPaint = rowPaint,
                noteDrawable = noteDrawable,
                marqueeElapsedMs = marqueeElapsedMs,
                marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
            )
            // Keep alpha-zero incoming children referenced by the parent. Their alpha changes on the
            // next vsync without a parent display-list rebuild, exactly like an attached child View.
            if (structureChanged && node != null) children.add(node)
        }
        drawRoot(canvas, structureGeneration, structureChanged, null)
    }

    private fun needsStructureRecord(canvas: android.graphics.Canvas, structureGeneration: Long): Boolean {
        return recordedStructureGeneration != structureGeneration ||
            recordedWidth != canvas.width || recordedHeight != canvas.height ||
            !rootNode.hasDisplayList()
    }

    private fun drawRoot(
        canvas: android.graphics.Canvas,
        structureGeneration: Long,
        structureChanged: Boolean,
        externalTransform: RetainedSceneItemTransform?,
    ) {
        if (structureChanged) {
            val width = canvas.width.coerceAtLeast(1)
            val height = canvas.height.coerceAtLeast(1)
            rootNode.setPosition(0, 0, width, height)
            val recording = rootNode.beginRecording(width, height)
            for (child in children) recording.drawRenderNode(child)
            rootNode.endRecording()
            recordedWidth = width
            recordedHeight = height
            recordedStructureGeneration = structureGeneration
        }
        val scale = externalTransform?.scaleProvider?.invoke()?.coerceIn(0.5f, 1.5f) ?: 1f
        val alpha = externalTransform?.alphaProvider?.invoke()?.coerceIn(0f, 1f) ?: 1f
        rootNode.setPivotX(externalTransform?.pivotX ?: canvas.width * 0.5f)
        rootNode.setPivotY(externalTransform?.pivotY ?: canvas.height * 0.5f)
        rootNode.setScaleX(scale)
        rootNode.setScaleY(scale)
        rootNode.setAlpha(alpha)
        canvas.drawRenderNode(rootNode)
    }

    fun release() {
        rootNode.discardDisplayList()
        children.clear()
        recordedStructureGeneration = Long.MIN_VALUE
        recordedWidth = -1
        recordedHeight = -1
    }
}

/** Plain Kotlin physical holder. It is intentionally not a Composable and not a Modifier.Node. */
private class NodeHolder(
    private val slotId: Int,
    private val invalidateContent: () -> Unit,
    private val invalidateArtwork: (Int) -> Unit,
) {
    internal var attachmentGeneration: Int = 0
    private var item: VirtualListSettledNodeItem? = null
    val drawContentEnabled: Boolean
        get() = item?.drawContentEnabled == true
    private var titleText = PreparedVirtualListText("", Typeface.DEFAULT, 0f, 0f)
    private var subtitleText = PreparedVirtualListText("", Typeface.DEFAULT, 0f, 0f)
    private var metaText = PreparedVirtualListText("", Typeface.DEFAULT, 0f, 0f)
    // Provider/content values are immutable for one holder bind. Keep them off the GenericPivot
    // tick: subtitle()/metaText() build strings and virtualListColors() allocates a value object,
    // none of which belongs in a 120 Hz LayoutRes commit loop.
    private var boundTitleRawText: String = ""
    private var boundSubtitleRawText: String = ""
    private var boundMetaRawText: String = ""
    private var boundTitleColor: Int = 0
    private var boundSecondaryColor: Int = 0
    private var boundMetaColor: Int = 0
    private var boundMetaIconKind: Int = VIRTUAL_LIST_META_ICON_NONE
    private var preparedSignature: PreparedSignature? = null
    private var renderSignature: NodeHolderRenderSignature? = null
    private var baseGeneration = 1L
    private var artworkGeneration = 1L
    private var textGeneration = 1L
    private var rootGeneration = 1L
    private var api29DisplayList: Api29DisplayList? = null
    private var folderMetaDrawable: android.graphics.drawable.Drawable? = null
    private val artwork = NodeArtworkActor {
        artworkGeneration += 1L
        invalidateArtwork(slotId)
    }
    private val settledLayout = VirtualListMutableLayoutRes()
    private val outerTransform = VirtualListResolvedOuterTransform()
    private val outerCurrentLayout = VirtualListMutableLayoutRes()
    private val outerRetainedLayout = VirtualListMutableLayoutRes()
    private var outerCurrentRects: ComposeTransitionRects? = null
    private var outerRetainedRects: ComposeTransitionRects? = null
    private var outerKind: VirtualListOuterTransitionKind? = null
    private var holderDensity = 1f
    private val presentationLayout = VirtualListMutablePresentationLayoutRes()
    private val viewGroupMotionState = VirtualListViewGroupMotionState()
    // Item-to-header promotion detaches only the shared artwork child from the source item. Raw's
    // retained holder is flattened into one RenderNode, so keep the row holder attached and mask
    // only its artwork lane while the concrete Android presentation child is promoted. Text/meta
    // therefore keep participating in the source/destination LayoutRes instead of disappearing
    // with the shared cover.
    private var sharedArtworkSuppressed = false
    private val viewGroupState = VirtualListViewGroupHolderState()

    fun setSharedArtworkSuppressed(suppressed: Boolean) {
        if (sharedArtworkSuppressed == suppressed) return
        sharedArtworkSuppressed = suppressed
        artworkGeneration += 1L
        invalidateContent()
    }

    fun update(next: VirtualListSettledNodeItem, frame: VirtualListSettledNodeFrame) {
        holderDensity = frame.density
        val previousItem = item
        val identityChanged = previousItem?.let { previous ->
            if (previous.song.id != 0L && next.song.id != 0L) {
                previous.song.id != next.song.id
            } else {
                previous.song.path != next.song.path
            }
        } ?: true
        val artworkVisibilityChanged = previousItem?.hideCover != next.hideCover
        val artworkBecameVisible = shouldRefreshVirtualListArtworkOnVisualReentry(
            wasDeferred = previousItem?.deferBitmapLoad == true,
            isDeferred = next.deferBitmapLoad,
        )
        item = next
        boundTitleRawText = next.song.displayName
        boundSubtitleRawText = next.song.subtitle()
        boundMetaRawText = next.song.metaText()
        val boundColors = virtualListColors(frame.dark, next.isPlaying, selected = false)
        boundTitleColor = boundColors.title.toArgbNode()
        boundSecondaryColor = boundColors.secondary.toArgbNode()
        boundMetaColor = boundColors.meta.toArgbNode()
        boundMetaIconKind = when {
            !next.hasCollectionMetaIcon -> VIRTUAL_LIST_META_ICON_NONE
            next.hasFolderMetaIcon -> VIRTUAL_LIST_META_ICON_FOLDER
            else -> VIRTUAL_LIST_META_ICON_NOTE
        }
        if (identityChanged) presentationLayout.reset()
        if (next.hasFolderMetaIcon && folderMetaDrawable == null) {
            folderMetaDrawable = frame.resources.getDrawable(R.drawable.ic_folder, null)?.mutate()
        }
        settledLayout.bind(next.position)
        val nextRenderSignature = NodeHolderRenderSignature(
            songId = next.song.id,
            songPath = next.song.path,
            artworkKey = next.song.coverKey,
            width = next.position.width,
            height = next.position.height,
            rects = next.rects,
            isPlaying = next.isPlaying,
            hideCover = next.hideCover,
            hasCollectionMetaIcon = next.hasCollectionMetaIcon,
            hasFolderMetaIcon = next.hasFolderMetaIcon,
            dark = frame.dark,
            density = frame.density,
            textDensity = frame.textDensity,
            defaultArtworkEnabled = frame.defaultArtworkEnabled,
        )
        if (nextRenderSignature != renderSignature) {
            renderSignature = nextRenderSignature
            baseGeneration += 1L
            textGeneration += 1L
            rootGeneration += 1L
        }
        if (artworkVisibilityChanged) {
            // The artwork child owns its bitmap/shader lifecycle independently from the
            // surrounding row holder. A row/base invalidation (text, playing state, meta icon,
            // parent geometry, etc.) must therefore not force the artwork display list to be
            // rebuilt as long as the artwork lane itself is still present. Keep only the true
            // container-visibility edge here; bitmap/replacement changes already arrive through
            // NodeArtworkActor's invalidation callback above, and shared-element suppression has
            // its own artworkGeneration edge in setSharedArtworkSuppressed().
            artworkGeneration += 1L
        }

        val leftInset = if (next.hasCollectionMetaIcon) 16f * frame.density else 0f
        val animateText = frame.marqueeAllowed
        val signature = PreparedSignature(
            title = boundTitleRawText,
            subtitle = boundSubtitleRawText,
            meta = boundMetaRawText,
            titleRect = next.rects.title,
            subtitleRect = next.rects.subtitle,
            metaRect = next.rects.meta,
            textDensity = frame.textDensity,
            configuredTypeface = frame.configuredTypeface,
            leftInset = leftInset,
            animateText = animateText,
        )
        if (signature != preparedSignature) {
            titleText = prepareVirtualListText(
                text = signature.title,
                rect = signature.titleRect,
                density = frame.textDensity,
                bold = true,
                animate = animateText,
                configuredTypeface = frame.configuredTypeface,
            )
            subtitleText = prepareVirtualListText(
                text = signature.subtitle,
                rect = signature.subtitleRect,
                density = frame.textDensity,
                bold = false,
                animate = animateText,
                configuredTypeface = frame.configuredTypeface,
            )
            metaText = prepareVirtualListText(
                text = signature.meta,
                rect = signature.metaRect,
                density = frame.textDensity,
                bold = false,
                leftInsetPx = leftInset,
                animate = animateText,
                configuredTypeface = frame.configuredTypeface,
            )
            preparedSignature = signature
            textGeneration += 1L
        }

        artwork.update(
            NodeArtworkBinding(
                key = next.song.coverKey.trim(),
                externalArtworkPath = next.song.albumArtPath,
                decodeSide = next.coverDecodeSide,
                priority = next.artworkPriority,
                deferLoad = next.deferBitmapLoad,
                // Physical ItemToHeader promotion is a visual ownership mask, not provider
                // lifecycle. Keep the artwork actor/provider request attached while its pixels are
                // drawn by the promoted child; only ordinary list policies may mark it hidden.
                hidden = next.hideCover,
                cornerRadiusPx = next.rects.coverRadiusDp * frame.density,
                animateChanges = next.artworkAnimationsEnabled,
                defaultArtworkEnabled = frame.defaultArtworkEnabled,
                resources = frame.resources,
            )
        )
        if (artworkBecameVisible) {
            // A real baseline implementation ArtworkImageNode re-enters the visible View tree and is redrawn with its
            // current measured bounds even when its provider identity and wrapper did not change.
            // Raw retains one HWUI RenderNode across the buffered offscreen row instead; without a
            // fresh recording, the holder can keep an older empty artwork display list until pinch
            // changes the cover pixels. Re-record the base layer exactly on visual re-entry.
            baseGeneration += 1L
            invalidateContent()
        }
    }

    fun onPhysicalAttach() {
        artwork.onPhysicalAttach()
    }

    fun onPhysicalRealDetach() {
        artwork.onPhysicalRealDetach()
    }

    fun hasMarqueeOverflow(): Boolean {
        return titleText.overflowPx > 0.5f || subtitleText.overflowPx > 0.5f || metaText.overflowPx > 0.5f
    }

    fun isAnimatingArtwork(): Boolean = artwork.isAnimating()

    fun hasArtworkBitmap(): Boolean = artwork.hasCurrentBitmap()

    fun debugRecordedSizeMatches(item: VirtualListSettledNodeItem): Boolean {
        val displayList = api29DisplayList ?: return false
        return displayList.debugRecordedSizeMatches(
            width = item.position.width,
            height = item.position.height,
        )
    }

    fun debugArtworkDisplayListPresent(): Boolean =
        api29DisplayList?.debugArtworkDisplayListPresent() == true

    /**
     * Trace-only comparison used to explain why a resident same-identity holder would be rebound
     * during destination prewarm. Keep this side-effect free: VirtualList's active-holder fast path is
     * exactly the behavior we are trying to verify before changing ownership semantics again.
     */
    fun debugPrewarmContentDiff(
        next: VirtualListSettledNodeItem,
        frame: VirtualListSettledNodeFrame,
    ): String {
        val reasons = ArrayList<String>(16)
        val render = renderSignature
        if (render == null) {
            reasons += "render:null"
        } else {
            if (render.songId != next.song.id) reasons += "render:songId"
            if (render.songPath != next.song.path) reasons += "render:path"
            if (render.artworkKey != next.song.coverKey) reasons += "render:artworkKey"
            if (render.width != next.position.width) reasons += "render:width"
            if (render.height != next.position.height) reasons += "render:height"
            if (render.rects != next.rects) reasons += "render:rects"
            if (render.isPlaying != next.isPlaying) reasons += "render:playing"
            if (render.hideCover != next.hideCover) reasons += "render:hideCover"
            if (render.hasCollectionMetaIcon != next.hasCollectionMetaIcon) reasons += "render:collectionMeta"
            if (render.hasFolderMetaIcon != next.hasFolderMetaIcon) reasons += "render:folderMeta"
            if (render.dark != frame.dark) reasons += "render:dark"
            if (render.density != frame.density) reasons += "render:density"
            if (render.textDensity != frame.textDensity) reasons += "render:textDensity"
            if (render.defaultArtworkEnabled != frame.defaultArtworkEnabled) reasons += "render:defaultArtwork"
        }

        val prepared = preparedSignature
        val expectedLeftInset = if (next.hasCollectionMetaIcon) 16f * frame.density else 0f
        if (prepared == null) {
            reasons += "text:null"
        } else {
            if (prepared.title != next.song.displayName) reasons += "text:title"
            if (prepared.subtitle != next.song.subtitle()) reasons += "text:subtitle"
            if (prepared.meta != next.song.metaText()) reasons += "text:meta"
            if (prepared.titleRect != next.rects.title) reasons += "text:titleRect"
            if (prepared.subtitleRect != next.rects.subtitle) reasons += "text:subtitleRect"
            if (prepared.metaRect != next.rects.meta) reasons += "text:metaRect"
            if (prepared.textDensity != frame.textDensity) reasons += "text:density"
            if (prepared.configuredTypeface != frame.configuredTypeface) reasons += "text:typeface"
            if (prepared.leftInset != expectedLeftInset) reasons += "text:leftInset"
            if (prepared.animateText != frame.marqueeAllowed) reasons += "text:marquee"
        }
        return if (reasons.isEmpty()) "none" else reasons.joinToString(",")
    }

    fun displayedArtworkBitmap(): Bitmap? = artwork.snapshotDisplayedBitmap()

    fun displayedSharedArtworkBitmap(): Bitmap? = artwork.snapshotSharedPromotionBitmap()

    fun boundItem(): VirtualListSettledNodeItem? = item

    fun prepareArtworkViewGroupState(): VirtualListViewGroupHolderState? {
        val current = item ?: return null
        return viewGroupState.apply {
            slotId = current.slotId
            coverRadiusPx = current.rects.coverRadiusDp * holderDensity
            artwork.fillViewGroupArtworkState(this)
            // Shared-element promotion moves the concrete row holder out of the source slot. The
            // replacement source holder remains responsible for text/meta only, so its artwork
            // lane must be hidden while the promoted concrete child owns the pixels. Raw's HWUI
            // path previously hid only Node drawing; Android presentation state still showed the
            // adopted artwork view, leaving a duplicate cover under/behind the promoted actor.
            artworkHidden = artworkHidden || sharedArtworkSuppressed
        }
    }

    fun beginSharedArtworkPromotion(targetSide: Int) {
        artwork.beginSharedPromotion(targetSide)
    }

    fun endSharedArtworkPromotion() {
        artwork.endSharedPromotion()
    }

    fun matchesPromotedIdentity(song: AudioFile): Boolean {
        val current = item?.song ?: return false
        return if (current.id != 0L && song.id != 0L) {
            current.id == song.id
        } else {
            current.path.isNotBlank() && current.path == song.path
        }
    }

    fun hasArtworkRequest(): Boolean = artwork.hasActiveRequest()

    fun advanceArtwork(frameTimeNanos: Long): Boolean {
        // Hardware artwork planes retain their pixels; fade advances alpha properties only.
        return artwork.advance(frameTimeNanos)
    }


    fun bindOuterLayout(motion: VirtualListOuterTransitionNodeItem) {
        outerKind = motion.kind
        outerCurrentLayout.bind(motion.currentPosition)
        outerRetainedLayout.bind(motion.retainedPosition)
        outerCurrentRects = motion.currentRects
        outerRetainedRects = motion.retainedRects
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareViewGroupContent(
        frame: VirtualListSettledNodeFrame,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ) {
        val current = item ?: return
        if (!current.drawContentEnabled) return
        val displayList = api29DisplayList ?: Api29DisplayList().also { api29DisplayList = it }
        displayList.prepareLocalViewGroupBaseNode(
            current = current,
            frame = frame,
            rowPaint = rowPaint,
        )
    }

    private fun populateTextChildState(
        out: VirtualListTextChildState,
        rect: ComposeItemRect,
        rawText: String,
        prepared: PreparedVirtualListText,
        color: Int,
        frame: VirtualListSettledNodeFrame,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
        ellipsize: Boolean,
        metaIconKind: Int = VIRTUAL_LIST_META_ICON_NONE,
    ) {
        out.left = rect.left
        out.top = rect.top
        out.width = rect.width.coerceAtLeast(1)
        out.height = rect.height.coerceAtLeast(1)
        out.alpha = rect.alpha.coerceIn(0f, 1f)
        out.textSizePx = (rect.fontSizeSp.takeIf { it > 0f } ?: 14f) * frame.textDensity
        out.sourceTextSizePx = out.textSizePx
        out.targetTextSizePx = out.textSizePx
        out.text = rawText
        out.typeface = prepared.typeface
        out.color = color
        out.leftInsetPx = if (metaIconKind != VIRTUAL_LIST_META_ICON_NONE) 16f * frame.density else 0f
        out.horizontalOffsetPx = prepared.marqueeOffset(
            marqueeElapsedMs,
            marqueeSpeedPxPerSecond,
            frame.marqueeAllowed,
        )
        out.ellipsize = ellipsize
        out.marqueeEnabled = frame.marqueeAllowed
        out.marqueeSpeed = marqueeSpeedPxPerSecond
        out.metaIconKind = metaIconKind
        out.metaIconSizePx = when (metaIconKind) {
            VIRTUAL_LIST_META_ICON_FOLDER -> minOf(out.height, (14f * frame.density).roundToInt()).coerceAtLeast(1)
            VIRTUAL_LIST_META_ICON_NOTE -> minOf(out.height, (12f * frame.density).roundToInt()).coerceAtLeast(1)
            else -> 0
        }
    }

    private fun populateOuterTextChildState(
        out: VirtualListTextChildState,
        currentRect: ComposeItemRect?,
        retainedRect: ComposeItemRect?,
        fallbackRect: ComposeItemRect,
        rawText: String,
        prepared: PreparedVirtualListText,
        color: Int,
        frame: VirtualListSettledNodeFrame,
        motionSample: VirtualListOuterTransitionMotionSample,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
        metaIconKind: Int = VIRTUAL_LIST_META_ICON_NONE,
    ) {
        val source = retainedRect ?: fallbackRect
        val target = currentRect ?: fallbackRect
        out.sourceTextSizePx = (source.fontSizeSp.takeIf { it > 0f } ?: 14f) * frame.textDensity
        out.targetTextSizePx = (target.fontSizeSp.takeIf { it > 0f } ?: 14f) * frame.textDensity
        when (outerKind) {
            VirtualListOuterTransitionKind.SHARED -> {
                val p = motionSample.currentFraction.coerceIn(0f, 1f)
                out.left = (source.left + (target.left - source.left) * p).roundToInt()
                out.top = (source.top + (target.top - source.top) * p).roundToInt()
                out.width = (source.width + (target.width - source.width) * p).roundToInt().coerceAtLeast(1)
                out.height = (source.height + (target.height - source.height) * p).roundToInt().coerceAtLeast(1)
                out.alpha = (source.alpha + (target.alpha - source.alpha) * p).coerceIn(0f, 1f)
                val sourceSp = source.fontSizeSp.takeIf { it > 0f } ?: 14f
                val targetSp = target.fontSizeSp.takeIf { it > 0f } ?: 14f
                out.textSizePx = (sourceSp + (targetSp - sourceSp) * p) * frame.textDensity
            }
            VirtualListOuterTransitionKind.CURRENT_ONLY -> {
                out.left = target.left
                out.top = target.top
                out.width = target.width.coerceAtLeast(1)
                out.height = target.height.coerceAtLeast(1)
                out.alpha = target.alpha.coerceIn(0f, 1f)
                out.textSizePx = (target.fontSizeSp.takeIf { it > 0f } ?: 14f) * frame.textDensity
            }
            VirtualListOuterTransitionKind.RETAINED_ONLY -> {
                out.left = source.left
                out.top = source.top
                out.width = source.width.coerceAtLeast(1)
                out.height = source.height.coerceAtLeast(1)
                out.alpha = source.alpha.coerceIn(0f, 1f)
                out.textSizePx = (source.fontSizeSp.takeIf { it > 0f } ?: 14f) * frame.textDensity
            }
            null -> {
                out.left = fallbackRect.left
                out.top = fallbackRect.top
                out.width = fallbackRect.width.coerceAtLeast(1)
                out.height = fallbackRect.height.coerceAtLeast(1)
                out.alpha = fallbackRect.alpha.coerceIn(0f, 1f)
                out.textSizePx = (fallbackRect.fontSizeSp.takeIf { it > 0f } ?: 14f) * frame.textDensity
            }
        }
        out.text = rawText
        out.typeface = prepared.typeface
        out.color = color
        out.leftInsetPx = if (metaIconKind != VIRTUAL_LIST_META_ICON_NONE) 16f * frame.density else 0f
        out.horizontalOffsetPx = prepared.marqueeOffset(
            marqueeElapsedMs,
            marqueeSpeedPxPerSecond,
            frame.marqueeAllowed,
        )
        out.ellipsize = !frame.marqueeAllowed && outerKind != VirtualListOuterTransitionKind.SHARED
        out.marqueeEnabled = frame.marqueeAllowed
        out.marqueeSpeed = marqueeSpeedPxPerSecond
        out.metaIconKind = metaIconKind
        out.metaIconSizePx = when (metaIconKind) {
            VIRTUAL_LIST_META_ICON_FOLDER -> minOf(out.height, (14f * frame.density).roundToInt()).coerceAtLeast(1)
            VIRTUAL_LIST_META_ICON_NOTE -> minOf(out.height, (12f * frame.density).roundToInt()).coerceAtLeast(1)
            else -> 0
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareSettledViewGroupState(
        frame: VirtualListSettledNodeFrame,
        remainder: Float,
        offsetX: Float,
        offsetY: Float,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ): VirtualListViewGroupHolderState? {
        val current = item ?: return null
        if (!current.drawContentEnabled || !settledLayout.present ||
            settledLayout.right <= settledLayout.left || settledLayout.bottom <= settledLayout.top
        ) return null
        val contentNode = api29DisplayList?.localViewGroupBaseNode ?: return null
        val colors = virtualListColors(frame.dark, current.isPlaying, selected = false)
        val metaIconKind = when {
            !current.hasCollectionMetaIcon -> VIRTUAL_LIST_META_ICON_NONE
            current.hasFolderMetaIcon -> VIRTUAL_LIST_META_ICON_FOLDER
            else -> VIRTUAL_LIST_META_ICON_NOTE
        }
        return viewGroupState.apply {
            slotId = current.slotId
            drawOrderGroup = 1
            drawOrderIndex = current.index
            width = current.position.width.coerceAtLeast(1)
            height = current.position.height.coerceAtLeast(1)
            left = offsetX + settledLayout.left.toFloat()
            top = offsetY + settledLayout.top.toFloat() - remainder
            pivotX = width * 0.5f
            pivotY = height * 0.5f
            scaleX = 1f
            scaleY = 1f
            alpha = settledLayout.alpha.coerceIn(0f, 1f)
            coverLeft = current.rects.cover.left
            coverTop = current.rects.cover.top
            coverWidth = current.rects.cover.width
            coverHeight = current.rects.cover.height
            coverRadiusPx = current.rects.coverRadiusDp * frame.density
            this.contentNode = contentNode
            populateTextChildState(
                titleText,
                current.rects.title,
                current.song.displayName,
                this@NodeHolder.titleText,
                colors.title.toArgbNode(),
                frame,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                ellipsize = !frame.marqueeAllowed,
            )
            populateTextChildState(
                subtitleText,
                current.rects.subtitle,
                current.song.subtitle(),
                this@NodeHolder.subtitleText,
                colors.secondary.toArgbNode(),
                frame,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                ellipsize = !frame.marqueeAllowed,
            )
            populateTextChildState(
                metaText,
                current.rects.meta,
                current.song.metaText(),
                this@NodeHolder.metaText,
                colors.meta.toArgbNode(),
                frame,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                ellipsize = !frame.marqueeAllowed,
                metaIconKind = metaIconKind,
            )
            artwork.fillViewGroupArtworkState(this)
            // Shared artwork promotion is a concrete View ownership transfer. The Android
            // replacement holder must mirror the retained Node holder's artwork suppression;
            // otherwise the source cover remains visible while the promoted child animates.
            artworkHidden = artworkHidden || sharedArtworkSuppressed
            presentationLayout.seed(
                width = width,
                height = height,
                scaleX = scaleX,
                scaleY = scaleY,
                alpha = alpha,
            )
        }
    }

    fun prepareSettledViewGroupMotionState(
        frame: VirtualListSettledNodeFrame,
        remainder: Float,
        offsetX: Float,
        offsetY: Float,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ): VirtualListViewGroupMotionState? {
        val current = item ?: return null
        if (!current.drawContentEnabled || !settledLayout.present ||
            settledLayout.right <= settledLayout.left || settledLayout.bottom <= settledLayout.top
        ) return null
        val top = settledLayout.top.toFloat() - remainder
        val bottom = settledLayout.bottom.toFloat() - remainder
        val overshoot = frame.virtualListEdgeOvershootProvider()
        val maxOvershoot = VIRTUAL_LIST_MAX_OVERSHOOT_DP * frame.density
        val holderFraction = virtualListHolderEdgeFraction(top, bottom, frame.viewportHeightPx)
        val edgeScaleY = virtualListHolderScaleY(overshoot, maxOvershoot, holderFraction)
        val edgeTranslationY = virtualListHolderTranslationY(
            overshootPx = overshoot,
            maxOvershootPx = maxOvershoot,
            holderShiftPx = VIRTUAL_LIST_HOLDER_SHIFT_DP * frame.density,
            holderFraction = holderFraction,
        )
        val width = current.position.width.coerceAtLeast(1)
        val height = current.position.height.coerceAtLeast(1)
        val alpha = settledLayout.alpha.coerceIn(0f, 1f)
        return viewGroupMotionState.apply {
            slotId = current.slotId
            this.width = width
            this.height = height
            left = offsetX + settledLayout.left.toFloat()
            this.top = offsetY + top + edgeTranslationY
            scaleX = 1f
            scaleY = edgeScaleY
            this.alpha = alpha
            dualLayout = false
            updateInternalChildren = true
            coverLeft = current.rects.cover.left
            coverTop = current.rects.cover.top
            coverWidth = current.rects.cover.width.coerceAtLeast(1)
            coverHeight = current.rects.cover.height.coerceAtLeast(1)
            coverRadiusPx = current.rects.coverRadiusDp * frame.density
            dirtyBits = presentationLayout.update(
                width = width,
                height = height,
                scaleX = 1f,
                scaleY = edgeScaleY,
                alpha = alpha,
            )
            artwork.fillViewGroupArtworkMotionState(this)
            populateTextChildState(
                titleText,
                current.rects.title,
                boundTitleRawText,
                this@NodeHolder.titleText,
                boundTitleColor,
                frame,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                ellipsize = !frame.marqueeAllowed,
            )
            populateTextChildState(
                subtitleText,
                current.rects.subtitle,
                boundSubtitleRawText,
                this@NodeHolder.subtitleText,
                boundSecondaryColor,
                frame,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                ellipsize = !frame.marqueeAllowed,
            )
            populateTextChildState(
                metaText,
                current.rects.meta,
                boundMetaRawText,
                this@NodeHolder.metaText,
                boundMetaColor,
                frame,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                ellipsize = !frame.marqueeAllowed,
                metaIconKind = boundMetaIconKind,
            )
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareOuterViewGroupState(
        frame: VirtualListSettledNodeFrame,
        motionSample: VirtualListOuterTransitionMotionSample,
        offsetX: Float,
        offsetY: Float,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ): VirtualListViewGroupHolderState? {
        val current = item ?: return null
        if (!current.drawContentEnabled) return null
        resolveOuterTransitionPositionIntoHolder(motionSample, outerTransform)
        if (outerTransform.width <= 0 || outerTransform.height <= 0) return null
        val contentNode = api29DisplayList?.localViewGroupBaseNode ?: return null
        val baseWidth = current.position.width.coerceAtLeast(1)
        val baseHeight = current.position.height.coerceAtLeast(1)
        val dualLayout = outerKind == VirtualListOuterTransitionKind.SHARED
        val colors = virtualListColors(frame.dark, current.isPlaying, selected = false)
        val metaIconKind = when {
            !current.hasCollectionMetaIcon -> VIRTUAL_LIST_META_ICON_NONE
            current.hasFolderMetaIcon -> VIRTUAL_LIST_META_ICON_FOLDER
            else -> VIRTUAL_LIST_META_ICON_NOTE
        }
        val currentRects = outerCurrentRects
        val retainedRects = outerRetainedRects
        val resolvedCover = when (outerKind) {
            VirtualListOuterTransitionKind.SHARED -> {
                val source = retainedRects?.cover
                val target = currentRects?.cover
                if (source != null && target != null) {
                    interpolatePowerItemRect(source, target, motionSample.currentFraction)
                } else {
                    target ?: source ?: current.rects.cover
                }
            }
            VirtualListOuterTransitionKind.CURRENT_ONLY -> currentRects?.cover ?: current.rects.cover
            VirtualListOuterTransitionKind.RETAINED_ONLY -> retainedRects?.cover ?: current.rects.cover
            null -> current.rects.cover
        }
        val resolvedCoverRadiusDp = when (outerKind) {
            VirtualListOuterTransitionKind.SHARED -> interpolateArtworkRadiusDp(
                retainedRects?.coverRadiusDp ?: current.rects.coverRadiusDp,
                currentRects?.coverRadiusDp ?: current.rects.coverRadiusDp,
                motionSample.currentFraction,
            )
            VirtualListOuterTransitionKind.CURRENT_ONLY -> currentRects?.coverRadiusDp ?: current.rects.coverRadiusDp
            VirtualListOuterTransitionKind.RETAINED_ONLY -> retainedRects?.coverRadiusDp ?: current.rects.coverRadiusDp
            null -> current.rects.coverRadiusDp
        }
        return viewGroupState.apply {
            slotId = current.slotId
            drawOrderGroup = when (outerKind) {
                VirtualListOuterTransitionKind.RETAINED_ONLY -> 0
                VirtualListOuterTransitionKind.SHARED -> 1
                VirtualListOuterTransitionKind.CURRENT_ONLY -> 2
                null -> 1
            }
            drawOrderIndex = current.index
            width = if (dualLayout) outerTransform.width.coerceAtLeast(1) else baseWidth
            height = if (dualLayout) outerTransform.height.coerceAtLeast(1) else baseHeight
            left = offsetX + outerTransform.left.toFloat()
            top = offsetY + outerTransform.top.toFloat()
            pivotX = width * 0.5f
            pivotY = height * 0.5f
            scaleX = (if (dualLayout) {
                outerTransform.scaleX
            } else {
                (outerTransform.width.coerceAtLeast(1).toFloat() / baseWidth.toFloat()) * outerTransform.scaleX
            }) * motionSample.holderScale
            scaleY = (if (dualLayout) {
                outerTransform.scaleY
            } else {
                (outerTransform.height.coerceAtLeast(1).toFloat() / baseHeight.toFloat()) * outerTransform.scaleY
            }) * motionSample.holderScale
            alpha = outerTransform.alpha.coerceIn(0f, 1f)
            coverLeft = resolvedCover.left
            coverTop = resolvedCover.top
            coverWidth = resolvedCover.width.coerceAtLeast(1)
            coverHeight = resolvedCover.height.coerceAtLeast(1)
            coverRadiusPx = resolvedCoverRadiusDp * frame.density
            this.contentNode = contentNode
            populateOuterTextChildState(
                titleText,
                currentRects?.title,
                retainedRects?.title,
                current.rects.title,
                current.song.displayName,
                this@NodeHolder.titleText,
                colors.title.toArgbNode(),
                frame,
                motionSample,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
            )
            populateOuterTextChildState(
                subtitleText,
                currentRects?.subtitle,
                retainedRects?.subtitle,
                current.rects.subtitle,
                current.song.subtitle(),
                this@NodeHolder.subtitleText,
                colors.secondary.toArgbNode(),
                frame,
                motionSample,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
            )
            populateOuterTextChildState(
                metaText,
                currentRects?.meta,
                retainedRects?.meta,
                current.rects.meta,
                current.song.metaText(),
                this@NodeHolder.metaText,
                colors.meta.toArgbNode(),
                frame,
                motionSample,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                metaIconKind = metaIconKind,
            )
            artwork.fillViewGroupArtworkState(this)
            presentationLayout.seed(
                width = width,
                height = height,
                scaleX = scaleX,
                scaleY = scaleY,
                alpha = alpha,
            )
        }
    }

    /**
     * Update only the mutable LayoutRes projected onto the already attached holder View.
     * Endpoint LayoutRes values were bound by [bindOuterLayout]; no content/provider/structure work
     * is permitted in this method.
     */
    fun prepareOuterViewGroupMotionState(
        frame: VirtualListSettledNodeFrame,
        motionSample: VirtualListOuterTransitionMotionSample,
        offsetX: Float,
        offsetY: Float,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ): VirtualListViewGroupMotionState? {
        val current = item ?: return null
        if (!current.drawContentEnabled) return null
        resolveOuterTransitionPositionIntoHolder(motionSample, outerTransform)
        if (outerTransform.width <= 0 || outerTransform.height <= 0) return null

        val baseWidth = current.position.width.coerceAtLeast(1)
        val baseHeight = current.position.height.coerceAtLeast(1)
        val dualLayout = outerKind == VirtualListOuterTransitionKind.SHARED
        val resolvedWidth = if (dualLayout) outerTransform.width.coerceAtLeast(1) else baseWidth
        val resolvedHeight = if (dualLayout) outerTransform.height.coerceAtLeast(1) else baseHeight
        val resolvedScaleX = (if (dualLayout) {
            outerTransform.scaleX
        } else {
            (outerTransform.width.coerceAtLeast(1).toFloat() / baseWidth.toFloat()) * outerTransform.scaleX
        }) * motionSample.holderScale
        val resolvedScaleY = (if (dualLayout) {
            outerTransform.scaleY
        } else {
            (outerTransform.height.coerceAtLeast(1).toFloat() / baseHeight.toFloat()) * outerTransform.scaleY
        }) * motionSample.holderScale
        val resolvedAlpha = outerTransform.alpha.coerceIn(0f, 1f)
        val currentRects = outerCurrentRects
        val retainedRects = outerRetainedRects
        val resolvedCoverRadiusDp = when (outerKind) {
            VirtualListOuterTransitionKind.SHARED -> interpolateArtworkRadiusDp(
                retainedRects?.coverRadiusDp ?: current.rects.coverRadiusDp,
                currentRects?.coverRadiusDp ?: current.rects.coverRadiusDp,
                motionSample.currentFraction,
            )
            VirtualListOuterTransitionKind.CURRENT_ONLY -> currentRects?.coverRadiusDp ?: current.rects.coverRadiusDp
            VirtualListOuterTransitionKind.RETAINED_ONLY -> retainedRects?.coverRadiusDp ?: current.rects.coverRadiusDp
            null -> current.rects.coverRadiusDp
        }

        return viewGroupMotionState.apply {
            slotId = current.slotId
            updateInternalChildren = VirtualListOuterTransitionNodePolicy.shouldUpdateInternalScene(outerKind)
            width = resolvedWidth
            height = resolvedHeight
            left = offsetX + outerTransform.left.toFloat()
            top = offsetY + outerTransform.top.toFloat()
            scaleX = resolvedScaleX
            scaleY = resolvedScaleY
            alpha = resolvedAlpha
            this.dualLayout = dualLayout
            val sourceCover = retainedRects?.cover
            val targetCover = currentRects?.cover
            val fallbackCover = current.rects.cover
            when (outerKind) {
                VirtualListOuterTransitionKind.SHARED -> {
                    if (sourceCover != null && targetCover != null) {
                        val p = motionSample.currentFraction.coerceIn(0f, 1f)
                        coverLeft = (sourceCover.left + (targetCover.left - sourceCover.left) * p).roundToInt()
                        coverTop = (sourceCover.top + (targetCover.top - sourceCover.top) * p).roundToInt()
                        coverWidth = (sourceCover.width + (targetCover.width - sourceCover.width) * p)
                            .roundToInt().coerceAtLeast(1)
                        coverHeight = (sourceCover.height + (targetCover.height - sourceCover.height) * p)
                            .roundToInt().coerceAtLeast(1)
                    } else {
                        val cover = targetCover ?: sourceCover ?: fallbackCover
                        coverLeft = cover.left
                        coverTop = cover.top
                        coverWidth = cover.width.coerceAtLeast(1)
                        coverHeight = cover.height.coerceAtLeast(1)
                    }
                }
                VirtualListOuterTransitionKind.CURRENT_ONLY -> {
                    val cover = targetCover ?: fallbackCover
                    coverLeft = cover.left
                    coverTop = cover.top
                    coverWidth = cover.width.coerceAtLeast(1)
                    coverHeight = cover.height.coerceAtLeast(1)
                }
                VirtualListOuterTransitionKind.RETAINED_ONLY -> {
                    val cover = sourceCover ?: fallbackCover
                    coverLeft = cover.left
                    coverTop = cover.top
                    coverWidth = cover.width.coerceAtLeast(1)
                    coverHeight = cover.height.coerceAtLeast(1)
                }
                null -> {
                    coverLeft = fallbackCover.left
                    coverTop = fallbackCover.top
                    coverWidth = fallbackCover.width.coerceAtLeast(1)
                    coverHeight = fallbackCover.height.coerceAtLeast(1)
                }
            }
            coverRadiusPx = resolvedCoverRadiusDp * holderDensity
            populateOuterTextChildState(
                titleText,
                currentRects?.title,
                retainedRects?.title,
                current.rects.title,
                boundTitleRawText,
                this@NodeHolder.titleText,
                boundTitleColor,
                frame,
                motionSample,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
            )
            populateOuterTextChildState(
                subtitleText,
                currentRects?.subtitle,
                retainedRects?.subtitle,
                current.rects.subtitle,
                boundSubtitleRawText,
                this@NodeHolder.subtitleText,
                boundSecondaryColor,
                frame,
                motionSample,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
            )
            populateOuterTextChildState(
                metaText,
                currentRects?.meta,
                retainedRects?.meta,
                current.rects.meta,
                boundMetaRawText,
                this@NodeHolder.metaText,
                boundMetaColor,
                frame,
                motionSample,
                marqueeElapsedMs,
                marqueeSpeedPxPerSecond,
                metaIconKind = boundMetaIconKind,
            )
            dirtyBits = if (dualLayout) {
                // The dual-LayoutRes interpolation branch remeasures and commits every
                // interpolated property every tick; it intentionally bypasses K dirty gating.
                VIRTUAL_LIST_LAYOUT_DIRTY_SIZE or
                    VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_X or
                    VIRTUAL_LIST_LAYOUT_DIRTY_SCALE_Y or
                    VIRTUAL_LIST_LAYOUT_DIRTY_ALPHA
            } else {
                presentationLayout.update(
                    width = resolvedWidth,
                    height = resolvedHeight,
                    scaleX = resolvedScaleX,
                    scaleY = resolvedScaleY,
                    alpha = resolvedAlpha,
                )
            }
            artwork.fillViewGroupArtworkMotionState(this)
        }
    }

    private fun interpolatePowerItemRect(
        source: ComposeItemRect,
        target: ComposeItemRect,
        progress: Float,
    ): ComposeItemRect {
        val p = progress.coerceIn(0f, 1f)
        fun lerpInt(a: Int, b: Int): Int = (a + (b - a) * p).roundToInt()
        fun lerpFloat(a: Float, b: Float): Float = a + (b - a) * p
        return ComposeItemRect(
            left = lerpInt(source.left, target.left),
            top = lerpInt(source.top, target.top),
            width = lerpInt(source.width, target.width).coerceAtLeast(1),
            height = lerpInt(source.height, target.height).coerceAtLeast(1),
            alpha = lerpFloat(source.alpha, target.alpha),
            fontSizeSp = lerpFloat(source.fontSizeSp, target.fontSizeSp),
        )
    }

    private fun resolveOuterTransitionPositionIntoHolder(
        motionSample: VirtualListOuterTransitionMotionSample,
        out: VirtualListResolvedOuterTransform,
    ) {
        when (outerKind) {
            VirtualListOuterTransitionKind.SHARED -> interpolateOuterLayoutInto(
                source = outerRetainedLayout,
                target = outerCurrentLayout,
                progress = motionSample.currentFraction,
                out = out,
            )
            VirtualListOuterTransitionKind.CURRENT_ONLY -> sceneTransformedLayoutInto(
                base = outerCurrentLayout,
                scale = motionSample.currentScale,
                alpha = motionSample.currentAlpha,
                pivotX = motionSample.currentPivotX,
                pivotY = motionSample.currentPivotY,
                out = out,
            )
            VirtualListOuterTransitionKind.RETAINED_ONLY -> sceneTransformedLayoutInto(
                base = outerRetainedLayout,
                scale = motionSample.retainedScale,
                alpha = motionSample.retainedAlpha,
                pivotX = motionSample.retainedPivotX,
                pivotY = motionSample.retainedPivotY,
                out = out,
            )
            null -> {
                out.left = 0
                out.top = 0
                out.right = 0
                out.bottom = 0
                out.alpha = 0f
                out.scaleX = 1f
                out.scaleY = 1f
            }
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareOuterRenderNode(
        frame: VirtualListSettledNodeFrame,
        motionSample: VirtualListOuterTransitionMotionSample,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ): RenderNode? {
        val current = item ?: return null
        if (!current.drawContentEnabled) return null
        resolveOuterTransitionPositionIntoHolder(motionSample, outerTransform)
        if (outerTransform.width <= 0 || outerTransform.height <= 0) return null
        val displayList = api29DisplayList ?: Api29DisplayList().also { api29DisplayList = it }
        return displayList.prepareResolvedTransition(
            current = current,
            frame = frame,
            resolved = outerTransform,
            rowPaint = rowPaint,
            rowAlpha = outerTransform.alpha.coerceIn(0f, 1f),
            noteDrawable = noteDrawable,
            marqueeElapsedMs = marqueeElapsedMs,
            marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
        )
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareSettledRenderNode(
        frame: VirtualListSettledNodeFrame,
        remainder: Float,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ): RenderNode? {
        val current = item ?: return null
        if (!current.drawContentEnabled || !settledLayout.present ||
            settledLayout.right <= settledLayout.left || settledLayout.bottom <= settledLayout.top
        ) return null
        val displayList = api29DisplayList ?: Api29DisplayList().also { api29DisplayList = it }
        val top = settledLayout.top.toFloat() - remainder
        val bottom = settledLayout.bottom.toFloat() - remainder
        val overshoot = frame.virtualListEdgeOvershootProvider()
        val maxOvershoot = VIRTUAL_LIST_MAX_OVERSHOOT_DP * frame.density
        val holderFraction = virtualListHolderEdgeFraction(top, bottom, frame.viewportHeightPx)
        val edgeScaleY = virtualListHolderScaleY(overshoot, maxOvershoot, holderFraction)
        val edgeTranslationY = virtualListHolderTranslationY(
            overshootPx = overshoot,
            maxOvershootPx = maxOvershoot,
            holderShiftPx = VIRTUAL_LIST_HOLDER_SHIFT_DP * frame.density,
            holderFraction = holderFraction,
        )
        return displayList.prepareSettledAt(
            current = current,
            frame = frame,
            left = settledLayout.left.toFloat(),
            top = top,
            edgeScaleY = edgeScaleY,
            edgeTranslationY = edgeTranslationY,
            rowPaint = rowPaint,
            rowAlpha = settledLayout.alpha.coerceIn(0f, 1f),
            noteDrawable = noteDrawable,
            marqueeElapsedMs = marqueeElapsedMs,
            marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
        )
    }

    fun drawOuterTransition(
        canvas: android.graphics.Canvas,
        frame: VirtualListSettledNodeFrame,
        motionSample: VirtualListOuterTransitionMotionSample,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ) {
        val current = item ?: return
        if (!current.drawContentEnabled) return
        resolveOuterTransitionPositionIntoHolder(motionSample, outerTransform)
        if (outerTransform.alpha <= 0f || outerTransform.width <= 0 || outerTransform.height <= 0) return
        val rowAlpha = outerTransform.alpha.coerceIn(0f, 1f)
        if (VirtualListHolderDisplayListPolicy.shouldUseRenderNode(
                apiLevel = Build.VERSION.SDK_INT,
                hardwareAccelerated = canvas.isHardwareAccelerated,
            )
        ) {
            val displayList = api29DisplayList ?: Api29DisplayList().also { api29DisplayList = it }
            displayList.drawResolvedTransition(
                canvas = canvas,
                current = current,
                frame = frame,
                resolved = outerTransform,
                rowPaint = rowPaint,
                rowAlpha = rowAlpha,
                noteDrawable = noteDrawable,
                marqueeElapsedMs = marqueeElapsedMs,
                marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
            )
            return
        }

        val baseWidth = current.position.width.coerceAtLeast(1)
        val baseHeight = current.position.height.coerceAtLeast(1)
        val sx = (outerTransform.width.coerceAtLeast(1).toFloat() / baseWidth.toFloat()) * outerTransform.scaleX
        val sy = (outerTransform.height.coerceAtLeast(1).toFloat() / baseHeight.toFloat()) * outerTransform.scaleY
        canvas.save()
        canvas.translate(outerTransform.left.toFloat(), outerTransform.top.toFloat())
        canvas.scale(sx, sy)
        drawDirect(
            canvas,
            current,
            frame,
            rowPaint,
            rowAlpha,
            noteDrawable,
            marqueeElapsedMs,
            marqueeSpeedPxPerSecond,
        )
        canvas.restore()
    }

    fun drawSettled(
        canvas: android.graphics.Canvas,
        frame: VirtualListSettledNodeFrame,
        remainder: Float,
        rowPaint: Paint,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ) {
        val current = item ?: return
        if (!current.drawContentEnabled || !settledLayout.present || settledLayout.alpha <= 0f) return
        val rowAlpha = settledLayout.alpha.coerceIn(0f, 1f)
        val top = settledLayout.top.toFloat() - remainder
        val bottom = settledLayout.bottom.toFloat() - remainder
        val overshoot = frame.virtualListEdgeOvershootProvider()
        val maxOvershoot = VIRTUAL_LIST_MAX_OVERSHOOT_DP * frame.density
        val holderFraction = virtualListHolderEdgeFraction(top, bottom, frame.viewportHeightPx)
        val edgeScaleY = virtualListHolderScaleY(overshoot, maxOvershoot, holderFraction)
        val edgeTranslationY = virtualListHolderTranslationY(
            overshootPx = overshoot,
            maxOvershootPx = maxOvershoot,
            holderShiftPx = VIRTUAL_LIST_HOLDER_SHIFT_DP * frame.density,
            holderFraction = holderFraction,
        )
        if (VirtualListHolderDisplayListPolicy.shouldUseRenderNode(
                apiLevel = Build.VERSION.SDK_INT,
                hardwareAccelerated = canvas.isHardwareAccelerated,
            )
        ) {
            val displayList = api29DisplayList ?: Api29DisplayList().also { api29DisplayList = it }
            displayList.drawSettledAt(
                canvas = canvas,
                current = current,
                frame = frame,
                left = settledLayout.left.toFloat(),
                top = top,
                edgeScaleY = edgeScaleY,
                edgeTranslationY = edgeTranslationY,
                rowPaint = rowPaint,
                rowAlpha = rowAlpha,
                noteDrawable = noteDrawable,
                marqueeElapsedMs = marqueeElapsedMs,
                marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
            )
            return
        }
        canvas.save()
        canvas.translate(settledLayout.left.toFloat(), top + edgeTranslationY)
        if (edgeScaleY != 1f) {
            canvas.scale(
                1f,
                edgeScaleY,
                current.position.width * 0.5f,
                current.position.height * 0.5f,
            )
        }
        drawDirect(
            canvas,
            current,
            frame,
            rowPaint,
            rowAlpha,
            noteDrawable,
            marqueeElapsedMs,
            marqueeSpeedPxPerSecond,
        )
        canvas.restore()
    }

    private fun drawDirect(
        canvas: android.graphics.Canvas,
        current: VirtualListSettledNodeItem,
        frame: VirtualListSettledNodeFrame,
        rowPaint: Paint,
        rowAlpha: Float,
        noteDrawable: android.graphics.drawable.Drawable?,
        marqueeElapsedMs: Long,
        marqueeSpeedPxPerSecond: Float,
    ) {
        drawBase(canvas, current, frame, rowPaint, rowAlpha, noteDrawable)
        drawText(
            canvas = canvas,
            current = current,
            frame = frame,
            rowAlpha = rowAlpha,
            elapsedMs = if (hasMarqueeOverflow()) marqueeElapsedMs else 0L,
            speed = marqueeSpeedPxPerSecond,
            clipToRect = true,
        )
    }

    private fun drawBase(
        canvas: android.graphics.Canvas,
        current: VirtualListSettledNodeItem,
        frame: VirtualListSettledNodeFrame,
        rowPaint: Paint,
        rowAlpha: Float,
        noteDrawable: android.graphics.drawable.Drawable?,
        includeArtwork: Boolean = true,
    ) {
        val colors = virtualListColors(frame.dark, current.isPlaying, selected = false)
        if (colors.background.alpha > 0f) {
            rowPaint.shader = null
            rowPaint.style = Paint.Style.FILL
            rowPaint.color = colors.background.copy(alpha = colors.background.alpha * rowAlpha).toArgbNode()
            canvas.drawRect(
                0f,
                0f,
                current.position.width.toFloat(),
                current.position.height.toFloat(),
                rowPaint,
            )
        }

        val cover = current.rects.cover
        if (includeArtwork && !current.hideCover && !sharedArtworkSuppressed) {
            // artwork image view has no translucent full-slot underlay once artwork owns the cell. The
            // artwork actor draws its fitted RectF (or its explicit no-art fallback) directly.
            artwork.draw(canvas, cover, rowAlpha)
        }

        if (
            current.hasCollectionMetaIcon &&
            current.rects.meta.alpha > 0f && current.rects.meta.width > 0 && current.rects.meta.height > 0
        ) {
            val metaDrawable = if (current.hasFolderMetaIcon) folderMetaDrawable else noteDrawable
            if (metaDrawable != null) {
                // Reference's CatImage is an independent ArtworkItemNode child. Keep the glyph in the
                // same interpolated meta slot so LIST<->GRID and GenericPivot move it with the
                // holder's LayoutRes instead of running a second Compose animation clock.
                val maxDp = if (current.hasFolderMetaIcon) 14f else 12f
                val iconSize = minOf(current.rects.meta.height, (maxDp * frame.density).toInt()).coerceAtLeast(1)
                val left = current.rects.meta.left
                val top = current.rects.meta.top + (current.rects.meta.height - iconSize) / 2
                metaDrawable.setTint(colors.meta.copy(alpha = colors.meta.alpha * current.rects.meta.alpha * rowAlpha).toArgbNode())
                metaDrawable.setBounds(left, top, left + iconSize, top + iconSize)
                metaDrawable.draw(canvas)
            }
        }
    }

    private fun drawText(
        canvas: android.graphics.Canvas,
        current: VirtualListSettledNodeItem,
        frame: VirtualListSettledNodeFrame,
        rowAlpha: Float,
        elapsedMs: Long,
        speed: Float,
        clipToRect: Boolean,
    ) {
        val colors = virtualListColors(frame.dark, current.isPlaying, selected = false)
        drawVirtualListText(
            canvas = canvas,
            text = titleText.display,
            rect = current.rects.title,
            color = colors.title.copy(alpha = colors.title.alpha * rowAlpha),
            density = frame.textDensity,
            bold = true,
            typefaceOverride = titleText.typeface,
            alreadyEllipsized = true,
            horizontalOffsetPx = titleText.marqueeOffset(elapsedMs, speed, frame.marqueeAllowed),
            clipToRect = clipToRect,
        )
        drawVirtualListText(
            canvas = canvas,
            text = subtitleText.display,
            rect = current.rects.subtitle,
            color = colors.secondary.copy(alpha = colors.secondary.alpha * rowAlpha),
            density = frame.textDensity,
            bold = false,
            typefaceOverride = subtitleText.typeface,
            alreadyEllipsized = true,
            horizontalOffsetPx = subtitleText.marqueeOffset(elapsedMs, speed, frame.marqueeAllowed),
            clipToRect = clipToRect,
        )
        drawVirtualListText(
            canvas = canvas,
            text = metaText.display,
            rect = current.rects.meta,
            color = colors.meta.copy(alpha = colors.meta.alpha * rowAlpha),
            density = frame.textDensity,
            bold = false,
            leftInsetPx = if (current.hasCollectionMetaIcon) 16f * frame.density else 0f,
            typefaceOverride = metaText.typeface,
            alreadyEllipsized = true,
            horizontalOffsetPx = metaText.marqueeOffset(elapsedMs, speed, frame.marqueeAllowed),
            clipToRect = clipToRect,
        )
    }

    private fun drawSingleTextLayer(
        canvas: android.graphics.Canvas,
        current: VirtualListSettledNodeItem,
        frame: VirtualListSettledNodeFrame,
        kind: CachedTextKind,
    ) {
        val colors = virtualListColors(frame.dark, current.isPlaying, selected = false)
        when (kind) {
            CachedTextKind.TITLE -> drawVirtualListText(
                canvas = canvas,
                text = titleText.display,
                rect = current.rects.title,
                color = colors.title,
                density = frame.textDensity,
                bold = true,
                typefaceOverride = titleText.typeface,
                alreadyEllipsized = true,
                horizontalOffsetPx = 0f,
                clipToRect = false,
            )
            CachedTextKind.SUBTITLE -> drawVirtualListText(
                canvas = canvas,
                text = subtitleText.display,
                rect = current.rects.subtitle,
                color = colors.secondary,
                density = frame.textDensity,
                bold = false,
                typefaceOverride = subtitleText.typeface,
                alreadyEllipsized = true,
                horizontalOffsetPx = 0f,
                clipToRect = false,
            )
            CachedTextKind.META -> drawVirtualListText(
                canvas = canvas,
                text = metaText.display,
                rect = current.rects.meta,
                color = colors.meta,
                density = frame.textDensity,
                bold = false,
                leftInsetPx = if (current.hasCollectionMetaIcon) 16f * frame.density else 0f,
                typefaceOverride = metaText.typeface,
                alreadyEllipsized = true,
                horizontalOffsetPx = 0f,
                clipToRect = false,
            )
        }
    }


    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private inner class Api29DisplayList {
        private val rootNode = RenderNode("raw-virtuallist-holder-content")
        val localContentNode: RenderNode get() = rootNode
        private val viewGroupBaseNode = RenderNode("raw-virtuallist-holder-view-base")
        val localViewGroupBaseNode: RenderNode get() = viewGroupBaseNode
        // One physical presentation RenderNode for settled + GenericPivot, matching one Reference View.
        private val presentationNode = RenderNode("raw-virtuallist-holder-presentation")
        private val baseNode = RenderNode("raw-virtuallist-holder-base")
        private val artworkNode = RenderNode("raw-virtuallist-holder-artwork")
        private var recordedArtworkGeneration = Long.MIN_VALUE
        private val titleNode = RenderNode("raw-virtuallist-holder-title")
        private val subtitleNode = RenderNode("raw-virtuallist-holder-subtitle")
        private val metaNode = RenderNode("raw-virtuallist-holder-meta")
        private var recordedWidth = -1
        private var recordedHeight = -1
        private var recordedBaseGeneration = Long.MIN_VALUE
        private var recordedTextGeneration = Long.MIN_VALUE
        private var recordedRootGeneration = Long.MIN_VALUE
        private var recordedRootIncludesArtwork: Boolean? = null
        private var recordedWrapperWidth = -1
        private var recordedWrapperHeight = -1
        private var recordedViewGroupBaseWidth = -1
        private var recordedViewGroupBaseHeight = -1
        private var recordedViewGroupBaseGeneration = Long.MIN_VALUE
        // RenderNode property setters participate in HWUI property synchronization even when the
        // value is unchanged.  GenericPivot touches every attached holder each vsync, so avoid
        // replaying identical property writes (especially pivot=0 and non-marquee text x=0) across
        // the complete holder ring.  This mirrors View's dirty-property behavior more closely.
        private var lastPresentationPivotX = Float.NaN
        private var lastPresentationPivotY = Float.NaN
        private var lastPresentationScaleX = Float.NaN
        private var lastPresentationScaleY = Float.NaN
        private var lastPresentationTranslationX = Float.NaN
        private var lastPresentationTranslationY = Float.NaN
        private var lastPresentationAlpha = Float.NaN
        private var lastTitleTranslationX = Float.NaN
        private var lastSubtitleTranslationX = Float.NaN
        private var lastMetaTranslationX = Float.NaN

        fun debugRecordedSizeMatches(width: Int, height: Int): Boolean =
            recordedWidth == width.coerceAtLeast(1) &&
                recordedHeight == height.coerceAtLeast(1)

        fun debugArtworkDisplayListPresent(): Boolean = artworkNode.hasDisplayList()

        fun prepareSettledAt(
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            left: Float,
            top: Float,
            edgeScaleY: Float,
            edgeTranslationY: Float,
            rowPaint: Paint,
            rowAlpha: Float,
            noteDrawable: android.graphics.drawable.Drawable?,
            marqueeElapsedMs: Long,
            marqueeSpeedPxPerSecond: Float,
        ): RenderNode {
            prepareContent(current, frame, rowPaint, noteDrawable)
            applyMarquee(frame, marqueeElapsedMs, marqueeSpeedPxPerSecond)
            applyPresentationProperties(
                pivotX = current.position.width * 0.5f,
                pivotY = current.position.height * 0.5f,
                scaleX = 1f,
                scaleY = edgeScaleY,
                translationX = left,
                translationY = top + edgeTranslationY,
                alpha = rowAlpha.coerceIn(0f, 1f),
            )
            return presentationNode
        }

        fun prepareLocalContentNode(
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            rowPaint: Paint,
            noteDrawable: android.graphics.drawable.Drawable?,
            marqueeElapsedMs: Long,
            marqueeSpeedPxPerSecond: Float,
            includeArtwork: Boolean = true,
        ): RenderNode {
            prepareContent(current, frame, rowPaint, noteDrawable, includeArtwork)
            applyMarquee(frame, marqueeElapsedMs, marqueeSpeedPxPerSecond)
            return rootNode
        }

        fun prepareLocalViewGroupBaseNode(
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            rowPaint: Paint,
        ): RenderNode {
            val width = current.position.width.coerceAtLeast(1)
            val height = current.position.height.coerceAtLeast(1)
            if (
                recordedViewGroupBaseWidth != width ||
                recordedViewGroupBaseHeight != height
            ) {
                viewGroupBaseNode.setPosition(0, 0, width, height)
                recordedViewGroupBaseWidth = width
                recordedViewGroupBaseHeight = height
                recordedViewGroupBaseGeneration = Long.MIN_VALUE
            }
            if (
                recordedViewGroupBaseGeneration != baseGeneration ||
                !viewGroupBaseNode.hasDisplayList()
            ) {
                val recording = viewGroupBaseNode.beginRecording(width, height)
                val colors = virtualListColors(frame.dark, current.isPlaying, selected = false)
                if (colors.background.alpha > 0f) {
                    rowPaint.shader = null
                    rowPaint.style = Paint.Style.FILL
                    rowPaint.color = colors.background.toArgbNode()
                    recording.drawRect(0f, 0f, width.toFloat(), height.toFloat(), rowPaint)
                }
                viewGroupBaseNode.endRecording()
                recordedViewGroupBaseGeneration = baseGeneration
            }
            return viewGroupBaseNode
        }

        fun drawSettledAt(
            canvas: android.graphics.Canvas,
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            left: Float,
            top: Float,
            edgeScaleY: Float,
            edgeTranslationY: Float,
            rowPaint: Paint,
            rowAlpha: Float,
            noteDrawable: android.graphics.drawable.Drawable?,
            marqueeElapsedMs: Long,
            marqueeSpeedPxPerSecond: Float,
        ) {
            canvas.drawRenderNode(
                prepareSettledAt(
                    current = current,
                    frame = frame,
                    left = left,
                    top = top,
                    edgeScaleY = edgeScaleY,
                    edgeTranslationY = edgeTranslationY,
                    rowPaint = rowPaint,
                    rowAlpha = rowAlpha,
                    noteDrawable = noteDrawable,
                    marqueeElapsedMs = marqueeElapsedMs,
                    marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                )
            )
        }

        fun prepareResolvedTransition(
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            resolved: VirtualListResolvedOuterTransform,
            rowPaint: Paint,
            rowAlpha: Float,
            noteDrawable: android.graphics.drawable.Drawable?,
            marqueeElapsedMs: Long,
            marqueeSpeedPxPerSecond: Float,
        ): RenderNode {
            prepareContent(current, frame, rowPaint, noteDrawable)
            applyMarquee(frame, marqueeElapsedMs, marqueeSpeedPxPerSecond)

            val baseWidth = current.position.width.coerceAtLeast(1)
            val baseHeight = current.position.height.coerceAtLeast(1)
            val resolvedWidth = resolved.width.coerceAtLeast(1)
            val resolvedHeight = resolved.height.coerceAtLeast(1)
            val sx = (resolvedWidth.toFloat() / baseWidth.toFloat()) * resolved.scaleX
            val sy = (resolvedHeight.toFloat() / baseHeight.toFloat()) * resolved.scaleY

            // Reference mutates the physical View/RenderNode properties during GenericPivot. Keep the
            // same retained ownership here: no per-holder Canvas save/translate/scale command stream.
            applyPresentationProperties(
                pivotX = 0f,
                pivotY = 0f,
                scaleX = sx,
                scaleY = sy,
                translationX = resolved.left.toFloat(),
                translationY = resolved.top.toFloat(),
                alpha = rowAlpha.coerceIn(0f, 1f),
            )
            return presentationNode
        }

        private fun applyPresentationProperties(
            pivotX: Float,
            pivotY: Float,
            scaleX: Float,
            scaleY: Float,
            translationX: Float,
            translationY: Float,
            alpha: Float,
        ) {
            if (lastPresentationPivotX != pivotX) {
                presentationNode.setPivotX(pivotX)
                lastPresentationPivotX = pivotX
            }
            if (lastPresentationPivotY != pivotY) {
                presentationNode.setPivotY(pivotY)
                lastPresentationPivotY = pivotY
            }
            if (lastPresentationScaleX != scaleX) {
                presentationNode.setScaleX(scaleX)
                lastPresentationScaleX = scaleX
            }
            if (lastPresentationScaleY != scaleY) {
                presentationNode.setScaleY(scaleY)
                lastPresentationScaleY = scaleY
            }
            if (lastPresentationTranslationX != translationX) {
                presentationNode.setTranslationX(translationX)
                lastPresentationTranslationX = translationX
            }
            if (lastPresentationTranslationY != translationY) {
                presentationNode.setTranslationY(translationY)
                lastPresentationTranslationY = translationY
            }
            if (lastPresentationAlpha != alpha) {
                presentationNode.setAlpha(alpha)
                lastPresentationAlpha = alpha
            }
        }

        fun drawResolvedTransition(
            canvas: android.graphics.Canvas,
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            resolved: VirtualListResolvedOuterTransform,
            rowPaint: Paint,
            rowAlpha: Float,
            noteDrawable: android.graphics.drawable.Drawable?,
            marqueeElapsedMs: Long,
            marqueeSpeedPxPerSecond: Float,
        ) {
            canvas.drawRenderNode(
                prepareResolvedTransition(
                    current = current,
                    frame = frame,
                    resolved = resolved,
                    rowPaint = rowPaint,
                    rowAlpha = rowAlpha,
                    noteDrawable = noteDrawable,
                    marqueeElapsedMs = marqueeElapsedMs,
                    marqueeSpeedPxPerSecond = marqueeSpeedPxPerSecond,
                )
            )
        }

        private fun prepareContent(
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            rowPaint: Paint,
            noteDrawable: android.graphics.drawable.Drawable?,
            includeArtwork: Boolean = true,
        ) {
            val recordStarted = if (TransitionPerfTrace.isActive()) System.nanoTime() else 0L
            val width = current.position.width.coerceAtLeast(1)
            val height = current.position.height.coerceAtLeast(1)
            if (includeArtwork) artwork.prepareRetainedNodes(current.rects.cover)
            if (recordedWidth != width || recordedHeight != height) {
                recordedWidth = width
                recordedHeight = height
                listOf(rootNode, presentationNode, baseNode, artworkNode).forEach { node ->
                    node.setPosition(0, 0, width, height)
                }
                recordedBaseGeneration = Long.MIN_VALUE
                recordedArtworkGeneration = Long.MIN_VALUE
                recordedTextGeneration = Long.MIN_VALUE
                recordedRootGeneration = Long.MIN_VALUE
                recordedRootIncludesArtwork = null
                recordedWrapperWidth = -1
                recordedWrapperHeight = -1
            }

            if (recordedBaseGeneration != baseGeneration || !baseNode.hasDisplayList()) {
                TransitionPerfTrace.count(TransitionPerfEvent.BASE_NODE_RECORD)
                val recording = baseNode.beginRecording(width, height)
                drawBase(
                    canvas = recording,
                    current = current,
                    frame = frame,
                    rowPaint = rowPaint,
                    rowAlpha = 1f,
                    noteDrawable = noteDrawable,
                    includeArtwork = false,
                )
                baseNode.endRecording()
                recordedBaseGeneration = baseGeneration
            }

            // Keep the artwork-child ownership boundary: the artwork child is retained across
            // unrelated item-holder/base changes. prepareRetainedNodes() above updates the child
            // RenderNodes in-place for bitmap/radius/cover-rect changes, so this wrapper display
            // list only needs to be rebuilt for an artwork lifecycle edge (bitmap actor callback,
            // hide/show, shared-artwork suppression) or after its own display list was discarded.
            if (includeArtwork && (recordedArtworkGeneration != artworkGeneration || !artworkNode.hasDisplayList())) {
                TransitionPerfTrace.count(TransitionPerfEvent.ARTWORK_NODE_RECORD)
                val recording = artworkNode.beginRecording(width, height)
                if (!current.hideCover && !sharedArtworkSuppressed) {
                    artwork.drawRetained(recording, current.rects.cover)
                }
                artworkNode.endRecording()
                recordedArtworkGeneration = artworkGeneration
            }

            if (recordedTextGeneration != textGeneration ||
                !titleNode.hasDisplayList() || !subtitleNode.hasDisplayList() || !metaNode.hasDisplayList()
            ) {
                TransitionPerfTrace.count(TransitionPerfEvent.TEXT_NODE_RECORD)
                recordTextNode(titleNode, retainedTextNodeWidth(current, frame, CachedTextKind.TITLE, width), height, current, frame, CachedTextKind.TITLE)
                recordTextNode(subtitleNode, retainedTextNodeWidth(current, frame, CachedTextKind.SUBTITLE, width), height, current, frame, CachedTextKind.SUBTITLE)
                recordTextNode(metaNode, retainedTextNodeWidth(current, frame, CachedTextKind.META, width), height, current, frame, CachedTextKind.META)
                recordedTextGeneration = textGeneration
            }
            if (recordStarted != 0L) TransitionPerfTrace.recordDuration(
                TransitionPerfStage.NODE_CONTENT, System.nanoTime() - recordStarted)

            if (recordedRootGeneration != rootGeneration ||
                recordedRootIncludesArtwork != includeArtwork ||
                !rootNode.hasDisplayList()
            ) {
                val recording = rootNode.beginRecording(width, height)
                recording.drawRenderNode(baseNode)
                if (includeArtwork) recording.drawRenderNode(artworkNode)
                drawClippedChild(recording, current.rects.title, 0f, titleNode, height)
                drawClippedChild(recording, current.rects.subtitle, 0f, subtitleNode, height)
                val metaInset = if (current.hasCollectionMetaIcon) 16f * frame.density else 0f
                drawClippedChild(recording, current.rects.meta, metaInset, metaNode, height)
                rootNode.endRecording()
                recordedRootGeneration = rootGeneration
                recordedRootIncludesArtwork = includeArtwork
            }

            if (recordedWrapperWidth != width || recordedWrapperHeight != height ||
                !presentationNode.hasDisplayList()
            ) {
                recordWrapper(presentationNode, width, height)
                recordedWrapperWidth = width
                recordedWrapperHeight = height
            }
        }

        private fun applyMarquee(
            frame: VirtualListSettledNodeFrame,
            marqueeElapsedMs: Long,
            marqueeSpeedPxPerSecond: Float,
        ) {
            if (!frame.marqueeAllowed || !hasMarqueeOverflow()) {
                applyMarqueeTranslations(0f, 0f, 0f)
                return
            }
            applyMarqueeTranslations(
                title = -titleText.marqueeOffset(marqueeElapsedMs, marqueeSpeedPxPerSecond, true),
                subtitle = -subtitleText.marqueeOffset(marqueeElapsedMs, marqueeSpeedPxPerSecond, true),
                meta = -metaText.marqueeOffset(marqueeElapsedMs, marqueeSpeedPxPerSecond, true),
            )
        }

        private fun applyMarqueeTranslations(title: Float, subtitle: Float, meta: Float) {
            if (lastTitleTranslationX != title) {
                titleNode.setTranslationX(title)
                lastTitleTranslationX = title
            }
            if (lastSubtitleTranslationX != subtitle) {
                subtitleNode.setTranslationX(subtitle)
                lastSubtitleTranslationX = subtitle
            }
            if (lastMetaTranslationX != meta) {
                metaNode.setTranslationX(meta)
                lastMetaTranslationX = meta
            }
        }

        private fun recordWrapper(node: RenderNode, width: Int, height: Int) {
            val recording = node.beginRecording(width, height)
            recording.drawRenderNode(rootNode)
            node.endRecording()
        }

        private fun recordTextNode(
            node: RenderNode,
            width: Int,
            height: Int,
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            kind: CachedTextKind,
        ) {
            // A RenderNode clips recorded commands to its own bounds. Recording a marquee string into
            // holder-width bounds permanently discarded the trailing glyphs before translation ran.
            // Keep the viewport clip in rootNode, but make the retained text child wide enough for
            // the complete unellipsized string exactly like Reference's clipRect + translated drawText.
            node.setPosition(0, 0, width, height)
            val recording = node.beginRecording(width, height)
            drawSingleTextLayer(recording, current, frame, kind)
            node.endRecording()
        }

        private fun retainedTextNodeWidth(
            current: VirtualListSettledNodeItem,
            frame: VirtualListSettledNodeFrame,
            kind: CachedTextKind,
            holderWidth: Int,
        ): Int {
            val rect = when (kind) {
                CachedTextKind.TITLE -> current.rects.title
                CachedTextKind.SUBTITLE -> current.rects.subtitle
                CachedTextKind.META -> current.rects.meta
            }
            val text = when (kind) {
                CachedTextKind.TITLE -> titleText
                CachedTextKind.SUBTITLE -> subtitleText
                CachedTextKind.META -> metaText
            }
            val inset = if (kind == CachedTextKind.META && current.hasCollectionMetaIcon) {
                16f * frame.density
            } else {
                0f
            }
            return virtualListRetainedTextNodeWidth(
                holderWidth = holderWidth,
                rectLeft = rect.left,
                leftInsetPx = inset,
                measuredWidthPx = text.measuredWidthPx,
                overflowPx = text.overflowPx,
                marqueeAllowed = frame.marqueeAllowed,
            )
        }

        private fun drawClippedChild(
            canvas: android.graphics.Canvas,
            rect: ComposeItemRect,
            leftInsetPx: Float,
            node: RenderNode,
            height: Int,
        ) {
            if (rect.alpha <= 0f || rect.width <= 0 || rect.height <= 0) return
            canvas.save()
            canvas.clipRect(
                rect.left.toFloat() + leftInsetPx,
                0f,
                rect.left + rect.width.toFloat(),
                height.toFloat(),
            )
            canvas.drawRenderNode(node)
            canvas.restore()
        }

        fun release() {
            rootNode.discardDisplayList()
            viewGroupBaseNode.discardDisplayList()
            presentationNode.discardDisplayList()
            baseNode.discardDisplayList()
            artworkNode.discardDisplayList()
            titleNode.discardDisplayList()
            subtitleNode.discardDisplayList()
            metaNode.discardDisplayList()
        }
    }

    fun release() {
        artwork.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            api29DisplayList?.release()
        }
        api29DisplayList = null
    }
}

private enum class CachedTextKind { TITLE, SUBTITLE, META }

private data class NodeHolderRenderSignature(
    val songId: Long,
    val songPath: String,
    val artworkKey: String,
    val width: Int,
    val height: Int,
    val rects: ComposeTransitionRects,
    val isPlaying: Boolean,
    val hideCover: Boolean,
    val hasCollectionMetaIcon: Boolean,
    val hasFolderMetaIcon: Boolean,
    val dark: Boolean,
    val density: Float,
    val textDensity: Float,
    val defaultArtworkEnabled: Boolean,
)

private data class PreparedSignature(
    val title: String,
    val subtitle: String,
    val meta: String,
    val titleRect: ComposeItemRect,
    val subtitleRect: ComposeItemRect,
    val metaRect: ComposeItemRect,
    val textDensity: Float,
    val configuredTypeface: Typeface?,
    val leftInset: Float,
    val animateText: Boolean,
)

private data class NodeArtworkBinding(
    val key: String,
    val externalArtworkPath: String,
    val decodeSide: Int,
    val priority: BitmapRequest.Priority,
    val deferLoad: Boolean,
    val hidden: Boolean,
    val cornerRadiusPx: Float,
    val animateChanges: Boolean,
    val defaultArtworkEnabled: Boolean,
    val resources: Resources,
)

/**
 * ArtworkImageNode-style actor owned by the plain holder. It deliberately contains no Snapshot state and
 * no Modifier node: callbacks update the holder and invalidate the single parent draw node.
 */
private class NodeArtworkActor(
    private val invalidateOwner: () -> Unit,
) {
    companion object {
        private val mainHandler = Handler(Looper.getMainLooper())
    }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private val currentMatrix = Matrix()
    private val previousMatrix = Matrix()
    private val fallbackMatrix = Matrix()
    private val currentRect = RectF()
    private val previousRect = RectF()
    private val fallbackRect = RectF()

    private var binding: NodeArtworkBinding? = null
    private var generation = 0L
    private var requestGeneration = 0L
    private var request: BitmapRequest? = null
    private var requestKey = ""
    private var requestSide = 0
    private var lastRequestEndReason = "none"
    private var currentHandle: ArtworkHandle? = null
    private var currentBitmap: Bitmap? = null
    private var currentTier: ArtworkTier = ArtworkTier.Any
    // The artwork child stays the same concrete node during item-to-header, then rebinds that
    // same node to the provider after the holder is moved into the header scene slot.
    // The provider exposes distinct low/high wrappers (its configured size table is low, low,
    // high, high), so the promoted View is allowed to upgrade its raster without swapping visual
    // ownership. Keep an equivalent hi-res lease beside the list lease for the lifetime of the
    // physical shared holder. This is not a second shared-element actor; it is the artwork state of
    // the same NodeHolder/Android child View which is already being promoted.
    private var sharedPromotionGeneration = 0L
    private var sharedPromotionRequest: BitmapRequest? = null
    private var sharedPromotionHandle: ArtworkHandle? = null
    private var sharedPromotionBitmap: Bitmap? = null
    private var sharedPromotionTargetSide = 0
    private var previousHandle: ArtworkHandle? = null
    private var previousBitmap: Bitmap? = null
    private var currentShader: BitmapShader? = null
    private var previousShader: BitmapShader? = null
    private var fallbackBitmap: Bitmap? = null
    private var fallbackShader: BitmapShader? = null
    private var terminalNoArt = false
    private var physicallyAttached = false
    // Survives a temporary detach for the same provider identity. Reference's ArtworkImageNode keeps the
    // accepted wrapper through onStartTemporaryDetach()/onFinishTemporaryDetach(); if Raw ever loses
    // that physical lease while the holder is offscreen, re-entry must repair it instead of trusting
    // a stale terminal/request state and drawing an empty cell forever.
    private var hasPresentedCurrentIdentity = false
    // Marks the physical bitmap that has actually reached a draw. The shared residency lane is the
    // Raw equivalent of ArtworkImageNode's raw/custom Bitmap ownership: it may outlive a released provider
    // lease and can be rebound without a source decode after a real holder recycle.
    private var fadeStartNs = 0L
    private var fadeDurationNs = 0L
    private var fadeProgress = 1f
    private var previousStartAlpha = 1f
    private var retainedNodes: RetainedArtworkNodes? = null

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private class ArtworkPlane(name: String) {
        // Each plane records exactly one shader-filled rectangle. Apply alpha to that primitive,
        // like the image View's Paint alpha, not to an intermediate overlapping-content layer.
        // The containing row still retains its own overlapping-rendering semantics.
        val node = RenderNode(name).apply { setHasOverlappingRendering(false) }
        private var recordedBitmap: Bitmap? = null
        private var recordedCover: ComposeItemRect? = null
        private var recordedRadius = Float.NaN
        private val planePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val matrix = Matrix()
        private val rect = RectF()

        fun bind(bitmap: Bitmap?, cover: ComposeItemRect, radius: Float) {
            val valid = bitmap?.takeIf { !it.isRecycled }
            if (recordedBitmap === valid && recordedCover == cover && recordedRadius == radius && node.hasDisplayList()) return
            recordedBitmap = valid
            recordedCover = cover
            recordedRadius = radius
            val width = (cover.left + cover.width).coerceAtLeast(1)
            val height = (cover.top + cover.height).coerceAtLeast(1)
            node.setPosition(0, 0, width, height)
            node.clipToBounds = false
            val canvas = node.beginRecording(width, height)
            if (valid != null) {
                val scale = minOf(cover.width.toFloat() / valid.width, cover.height.toFloat() / valid.height)
                val left = cover.left + (cover.width - valid.width * scale) * 0.5f
                val top = cover.top + (cover.height - valid.height * scale) * 0.5f
                rect.set(left, top, left + valid.width * scale, top + valid.height * scale)
                matrix.setScale(scale, scale)
                matrix.postTranslate(left, top)
                val shader = BitmapShader(valid, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR)
                shader.setLocalMatrix(matrix)
                planePaint.shader = shader
                val boundedRadius = min(radius, min(rect.width(), rect.height()) * 0.5f)
                canvas.drawRoundRect(rect, boundedRadius, boundedRadius, planePaint)
                planePaint.shader = null
            }
            node.endRecording()
        }

        fun release() {
            node.discardDisplayList()
            recordedBitmap = null
            recordedCover = null
        }
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private class RetainedArtworkNodes {
        val previous = ArtworkPlane("raw-artwork-previous")
        val current = ArtworkPlane("raw-artwork-current")
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun prepareRetainedNodes(cover: ComposeItemRect) {
        val source = binding ?: return
        val nodes = retainedNodes ?: RetainedArtworkNodes().also { retainedNodes = it }
        val current = currentBitmap?.takeIf { !it.isRecycled }
        val previous = previousBitmap?.takeIf { !it.isRecycled }
        nodes.previous.bind(previous, cover, source.cornerRadiusPx)
        nodes.current.bind(current ?: fallbackBitmap, cover, source.cornerRadiusPx)
        val progress = fadeProgress.coerceIn(0f, 1f)
        val previousAlpha = if (source.hidden || previous == null) 0f else
            ReferenceInterruptedPreviousAlpha(previousStartAlpha, progress)
        val currentAlpha = if (source.hidden) 0f else if (current == null) 1f else progress
        if (nodes.previous.node.alpha != previousAlpha) nodes.previous.node.alpha = previousAlpha
        if (nodes.current.node.alpha != currentAlpha) nodes.current.node.alpha = currentAlpha
        if (current != null && !source.hidden && currentAlpha > 0f) hasPresentedCurrentIdentity = true
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    fun drawRetained(canvas: android.graphics.Canvas, cover: ComposeItemRect) {
        prepareRetainedNodes(cover)
        val nodes = retainedNodes ?: return
        canvas.drawRenderNode(nodes.previous.node)
        canvas.drawRenderNode(nodes.current.node)
    }

    fun update(next: NodeArtworkBinding) {
        val old = binding
        if (old == next) return
        // baseline implementation ArtworkImageNode identity is the provider type/id key. A fallback source-path update
        // for the same key must not release an already accepted wrapper and expose an empty holder.
        val identityChanged = old == null || old.key != next.key
        val externalPathChanged = old?.externalArtworkPath != next.externalArtworkPath
        val sideChanged = old?.decodeSide != next.decodeSide
        val defaultChanged = old?.defaultArtworkEnabled != next.defaultArtworkEnabled
        val fadeDisabled = old?.animateChanges == true && !next.animateChanges
        binding = next

        if (identityChanged) {
            generation += 1L
            detachRequest("identity_changed")
            endSharedPromotion()
            releasePrevious()
            releaseCurrent()
            currentShader = null
            previousShader = null
            hasPresentedCurrentIdentity = false
            fallbackBitmap = null
            fallbackShader = null
            terminalNoArt = false
            stopFade()
            // The retained base display list may otherwise keep pixels from the previous binding
            // until the replacement request completes. Clear/re-record it immediately on identity
            // handoff, exactly when ArtworkImageNode releases its old wrapper.
            invalidateOwner()
        }

        if (defaultChanged && !next.defaultArtworkEnabled) {
            fallbackBitmap = null
            fallbackShader = null
        }
        if (fadeDisabled) {
            stopFade()
            releasePrevious()
        }
        if (next.hidden) {
            detachRequest("hidden")
            return
        }
        if (next.deferLoad) {
            // Reference ArtworkImageNode temporary-detach keeps the active provider request and accepts
            // its callback while q=true. Defer only prevents this holder from starting new source
            // work; it must not cancel work that already owns this provider identity.
            return
        }
        if (externalPathChanged && !identityChanged) {
            // Source metadata changed under the same provider identity. Any older no-art decision is
            // stale now. Keep already accepted pixels, but allow the new fallback path to resolve.
            terminalNoArt = false
            if (currentBitmap?.let { !isAcceptable(it, next.decodeSide) } != false) {
                detachRequest("source_path_changed")
            }
        }

        if (identityChanged || sideChanged || currentBitmap?.let { !isAcceptable(it, next.decodeSide) } != false) {
            ensureBestCached()
        }
        // List holders must not inherit BitmapProvider's global failure sentinel. ArtworkSurface.List
        // intentionally probes the source even when Playback/Indexer remembered an older no-art
        // result. Only this holder's own completed source request may set terminalNoArt=true.
        ensureRequest()
    }

    fun onPhysicalAttach() {
        if (physicallyAttached) return
        physicallyAttached = true
        val live = binding ?: return
        if (live.hidden || live.deferLoad || live.key.isBlank()) return
        ensureBestCached()
        ensureRequest()
        invalidateOwner()
    }

    fun onPhysicalRealDetach() {
        if (!physicallyAttached) return
        physicallyAttached = false

        // A real artwork-child detach releases its current bitmap wrapper/shader, while an
        // in-flight provider load is allowed to finish into provider cache. Invalidate this actor's
        // listener generation without cancelling provider work; a later real attach first reacquires
        // the cached wrapper for the same identity and only starts new source work on a true miss.
        if (request != null) {
            request = null
            requestKey = ""
            requestSide = 0
            requestGeneration += 1L
            lastRequestEndReason = "detach_observer:real_detach"
        }
        endSharedPromotion()
        stopFade()
        releasePrevious()
        releaseCurrent()
        hasPresentedCurrentIdentity = false
        fallbackBitmap = null
        fallbackShader = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            retainedNodes?.previous?.release()
            retainedNodes?.current?.release()
        }
        retainedNodes = null
        invalidateOwner()
    }

    fun draw(canvas: android.graphics.Canvas, cover: ComposeItemRect, rowAlpha: Float) {
        val currentBinding = binding ?: return
        if (currentBinding.hidden) return
        val left = cover.left.toFloat()
        val top = cover.top.toFloat()
        val width = cover.width.toFloat().coerceAtLeast(1f)
        val height = cover.height.toFloat().coerceAtLeast(1f)
        val radius = currentBinding.cornerRadiusPx

        val fallback = fallbackBitmap?.takeIf { !it.isRecycled }
        if (fallback != null && currentBitmap == null) {
            val shader = fallbackShader ?: BitmapShader(fallback, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).also {
                fallbackShader = it
            }
            configureFitCenter(fallbackMatrix, fallback, width, height, left, top, fallbackRect)
            shader.setLocalMatrix(fallbackMatrix)
            paint.shader = shader
            paint.alpha = (255f * rowAlpha.coerceIn(0f, 1f)).toInt().coerceIn(0, 255)
            val fallbackRadius = min(radius, min(fallbackRect.width(), fallbackRect.height()) * 0.5f)
            canvas.drawRoundRect(fallbackRect, fallbackRadius, fallbackRadius, paint)
        }

        val current = currentBitmap?.takeIf { !it.isRecycled } ?: return
        if (rowAlpha > 0f) {
            hasPresentedCurrentIdentity = true
        }
        val progress = fadeProgress.coerceIn(0f, 1f)
        val previous = previousBitmap?.takeIf { !it.isRecycled }
        if (previous != null && progress < 1f) {
            val shader = previousShader ?: BitmapShader(previous, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).also {
                previousShader = it
            }
            configureFitCenter(previousMatrix, previous, width, height, left, top, previousRect)
            shader.setLocalMatrix(previousMatrix)
            paint.shader = shader
            paint.alpha = ReferenceArtworkAlphaByte(
                ReferenceInterruptedPreviousAlpha(
                    previousStartAlpha = previousStartAlpha,
                    replacementProgress = progress,
                ) * rowAlpha.coerceIn(0f, 1f)
            )
            val previousRadius = min(radius, min(previousRect.width(), previousRect.height()) * 0.5f)
            canvas.drawRoundRect(previousRect, previousRadius, previousRadius, paint)
        }
        val shader = currentShader ?: BitmapShader(current, Shader.TileMode.MIRROR, Shader.TileMode.MIRROR).also {
            currentShader = it
        }
        configureFitCenter(currentMatrix, current, width, height, left, top, currentRect)
        shader.setLocalMatrix(currentMatrix)
        paint.shader = shader
        val currentAlpha = if (previous != null) progress else progress
        paint.alpha = (255f * currentAlpha * rowAlpha).toInt().coerceIn(0, 255)
        val currentRadius = min(radius, min(currentRect.width(), currentRect.height()) * 0.5f)
        canvas.drawRoundRect(currentRect, currentRadius, currentRadius, paint)
        if (progress >= 1f) releasePrevious()
        paint.shader = null
        paint.alpha = 255
    }

    fun advance(frameTimeNanos: Long): Boolean {
        if (fadeDurationNs <= 0L) return false
        val elapsed = (frameTimeNanos - fadeStartNs).coerceAtLeast(0L)
        fadeProgress = (elapsed.toDouble() / fadeDurationNs.toDouble()).toFloat().coerceIn(0f, 1f)
        if (fadeProgress >= 1f) {
            stopFade()
            releasePrevious()
            return false
        }
        return true
    }

    fun isAnimating(): Boolean = fadeDurationNs > 0L && fadeProgress < 1f

    fun hasCurrentBitmap(): Boolean = currentBitmap?.isRecycled == false

    fun fillViewGroupArtworkState(out: VirtualListViewGroupHolderState) {
        val source = binding
        val current = currentBitmap?.takeIf { !it.isRecycled }
        val previous = previousBitmap?.takeIf { !it.isRecycled }
        val fallback = fallbackBitmap?.takeIf { !it.isRecycled }
        val progress = fadeProgress.coerceIn(0f, 1f)
        val hidden = source?.hidden == true

        out.artworkCurrentBitmap = current
        out.artworkPreviousBitmap = previous
        out.artworkFallbackBitmap = fallback
        out.artworkPreviousAlpha = if (hidden || previous == null) {
            0f
        } else {
            ReferenceInterruptedPreviousAlpha(previousStartAlpha, progress)
        }
        out.artworkCurrentAlpha = if (hidden) 0f else if (current == null) 1f else progress
        out.artworkHidden = hidden
        out.artworkDebugHandleValid = currentHandle?.isValid == true
        out.artworkDebugTier = currentHandle?.tier?.name ?: "-"
        out.artworkDebugRequestActive = request?.isCancelled == false
        out.artworkDebugRequestSide = requestSide
        out.artworkDebugLastRequestEndReason = lastRequestEndReason
        out.artworkDebugKeyTag = source?.key?.takeLast(24) ?: "-"
        if (current != null && !hidden && out.artworkCurrentAlpha > 0f) {
            hasPresentedCurrentIdentity = true
        }
    }

    /**
     * Artwork-view animation-only projection. Bitmap/shader ownership changes are published by
     * the normal content sync; a GenericPivot vsync only needs the already attached image child's
     * replacement alphas.
     */
    fun fillViewGroupArtworkMotionState(out: VirtualListViewGroupMotionState) {
        val source = binding
        val current = currentBitmap?.takeIf { !it.isRecycled }
        val previous = previousBitmap?.takeIf { !it.isRecycled }
        val progress = fadeProgress.coerceIn(0f, 1f)
        val hidden = source?.hidden == true
        out.artworkPreviousAlpha = if (hidden || previous == null) {
            0f
        } else {
            ReferenceInterruptedPreviousAlpha(previousStartAlpha, progress)
        }
        out.artworkCurrentAlpha = if (hidden) 0f else if (current == null) 1f else progress
        if (current != null && !hidden && out.artworkCurrentAlpha > 0f) {
            hasPresentedCurrentIdentity = true
        }
    }

    /** Snapshot only the pixels already owned by this holder; never resolve by key here. */
    fun snapshotDisplayedBitmap(): Bitmap? =
        currentBitmap?.takeIf { !it.isRecycled }
            ?: fallbackBitmap?.takeIf { !it.isRecycled }

    /**
     * Bitmap currently owned by the same physical holder while it occupies the detail header.
     * Prefer the provider's high tier, but keep the already-present list raster visible until that
     * upgrade arrives. The ownership model keeps the row/artwork node intact rather than
     * replaced during Item->Header; its provider binding is refreshed in-place.
     */
    fun snapshotSharedPromotionBitmap(): Bitmap? =
        sharedPromotionBitmap?.takeIf { !it.isRecycled }
            ?: snapshotDisplayedBitmap()

    fun beginSharedPromotion(targetSide: Int) {
        val live = binding ?: return
        if (live.key.isBlank()) return
        val side = maxOf(live.decodeSide, targetSide.coerceAtLeast(1))
        sharedPromotionTargetSide = maxOf(sharedPromotionTargetSide, side)

        val promoted = sharedPromotionBitmap
        if (
            promoted != null && !promoted.isRecycled &&
            sharedPromotionHandle?.isValid == true &&
            isAcceptable(promoted, sharedPromotionTargetSide)
        ) return
        val current = currentBitmap
        if (
            current != null && !current.isRecycled &&
            currentHandle?.isValid == true &&
            isAcceptable(current, sharedPromotionTargetSide)
        ) {
            invalidateOwner()
            return
        }

        val cached = BitmapProvider.acquire(
            key = live.key,
            targetWidth = sharedPromotionTargetSide,
            targetHeight = sharedPromotionTargetSide,
            surface = ArtworkSurface.Fullscreen,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        )
        if (cached != null && cached.isValid && !cached.bitmap.isRecycled) {
            sharedPromotionRequest?.let(BitmapProvider::cancel)
            sharedPromotionRequest = null
            sharedPromotionGeneration += 1L
            sharedPromotionHandle?.release()
            sharedPromotionHandle = cached
            sharedPromotionBitmap = cached.bitmap
            invalidateOwner()
            return
        }
        cached?.release()

        if (
            sharedPromotionRequest?.isCancelled == false &&
            sharedPromotionTargetSide == side
        ) return

        sharedPromotionRequest?.let(BitmapProvider::cancel)
        sharedPromotionRequest = null
        val localGeneration = ++sharedPromotionGeneration
        val key = live.key
        val requestedSide = sharedPromotionTargetSide
        var localRequest: BitmapRequest? = null
        var callbackDeliveredSynchronously = false
        localRequest = BitmapProvider.loadHandle(
            key = key,
            targetWidth = requestedSide,
            targetHeight = requestedSide,
            priority = AlbumArtTiers.PLAYBACK_PROVIDER_PRIORITY,
            surface = ArtworkSurface.Fullscreen,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) { loaded ->
            val synchronous = localRequest == null
            if (synchronous) callbackDeliveredSynchronously = true
            val deliver = {
                val currentBinding = binding
                if (
                    localGeneration != sharedPromotionGeneration ||
                    currentBinding == null || currentBinding.key != key ||
                    requestedSide != sharedPromotionTargetSide
                ) {
                    loaded?.release()
                } else {
                    if (sharedPromotionRequest === localRequest || synchronous) {
                        sharedPromotionRequest = null
                    }
                    if (loaded != null && loaded.isValid && !loaded.bitmap.isRecycled) {
                        sharedPromotionHandle?.release()
                        sharedPromotionHandle = loaded
                        sharedPromotionBitmap = loaded.bitmap
                        invalidateOwner()
                    } else {
                        loaded?.release()
                    }
                }
                Unit
            }
            if (Looper.myLooper() == Looper.getMainLooper()) deliver() else mainHandler.post(deliver)
        }
        if (!callbackDeliveredSynchronously) sharedPromotionRequest = localRequest
    }

    fun endSharedPromotion() {
        sharedPromotionGeneration += 1L
        sharedPromotionRequest?.let(BitmapProvider::cancel)
        sharedPromotionRequest = null
        sharedPromotionHandle?.release()
        sharedPromotionHandle = null
        sharedPromotionBitmap = null
        sharedPromotionTargetSide = 0
        invalidateOwner()
    }

    fun hasActiveRequest(): Boolean = request?.isCancelled == false

    fun release() {
        generation += 1L
        detachRequest("holder_release")
        endSharedPromotion()
        stopFade()
        releasePrevious()
        releaseCurrent()
        hasPresentedCurrentIdentity = false
        fallbackBitmap = null
        fallbackShader = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            retainedNodes?.previous?.release()
            retainedNodes?.current?.release()
        }
        retainedNodes = null
    }

    private fun ensureBestCached() {
        val currentBinding = binding ?: return
        if (currentBinding.key.isBlank()) return
        if (isVirtualListProviderArtworkSatisfied(
                hasUsableBitmap = currentBitmap?.isRecycled == false,
                coversTarget = currentBitmap?.let { isAcceptable(it, currentBinding.decodeSide) } == true,
                hasValidProviderHandle = currentHandle?.isValid == true,
                hasRequestedTier = currentHandle?.tier == ArtworkTier.Low,
            )) return
        val candidate = BitmapProvider.acquireBestThumbnail(
            key = currentBinding.key,
            targetWidth = currentBinding.decodeSide,
            targetHeight = currentBinding.decodeSide,
            surface = ArtworkSurface.List,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        )
        if (candidate != null) accept(candidate, allowAnimation = false, origin = "cache_acquire")
    }

    private fun ensureRequest() {
        val currentBinding = binding ?: return
        if (currentBinding.hidden || currentBinding.deferLoad || currentBinding.key.isBlank()) return
        if (terminalNoArt) {
            // A list holder may have completed its own cold probe before Playback/Indexer later
            // discovered an embedded DSF/APE picture. Do not keep that holder permanently latched
            // to no-art once the central provider has positive embedded-source evidence. This is
            // deliberately one-way evidence: true no-art rows still avoid repeated source probes.
            if (BitmapProvider.originalArtworkSourcePath(currentBinding.key) != null) {
                terminalNoArt = false
                fallbackBitmap = null
                fallbackShader = null
            } else {
                return
            }
        }
        if (isVirtualListProviderArtworkSatisfied(
                hasUsableBitmap = currentBitmap?.isRecycled == false,
                coversTarget = currentBitmap?.let { isAcceptable(it, currentBinding.decodeSide) } == true,
                hasValidProviderHandle = currentHandle?.isValid == true,
                hasRequestedTier = currentHandle?.tier == ArtworkTier.Low,
            )) return
        request?.takeIf { it.isCancelled }?.let {
            request = null
            requestKey = ""
            requestSide = 0
            requestGeneration += 1L
            lastRequestEndReason = "cancel:provider"
        }
        if (request != null && requestKey == currentBinding.key && requestSide == currentBinding.decodeSide) return

        detachRequest("request_rebind")
        val localGeneration = generation
        val localRequestGeneration = ++requestGeneration
        val key = currentBinding.key
        val side = currentBinding.decodeSide
        requestKey = key
        requestSide = side
        var localRequest: BitmapRequest? = null
        var callbackDeliveredSynchronously = false
        localRequest = BitmapProvider.loadViewportThumbnail(
            key = key,
            targetWidth = side,
            targetHeight = side,
            priority = currentBinding.priority,
            externalArtworkPath = currentBinding.externalArtworkPath,
            synchronousMissConfirmed = true,
            aspectPolicy = ArtworkAspectPolicy.KeepAspect,
        ) { loaded: ArtworkHandle? ->
            val synchronousCallback = localRequest == null
            if (synchronousCallback) callbackDeliveredSynchronously = true
            val deliver = {
                handleLoaded(
                    key = key,
                    side = side,
                    localGeneration = localGeneration,
                    localRequestGeneration = localRequestGeneration,
                    requestRef = localRequest,
                    loaded = loaded,
                    callbackDeliveredSynchronously = synchronousCallback,
                )
            }
            if (Looper.myLooper() == Looper.getMainLooper()) deliver() else mainHandler.post { deliver() }
        }
        if (!callbackDeliveredSynchronously) request = localRequest
    }

    private fun handleLoaded(
        key: String,
        side: Int,
        localGeneration: Long,
        localRequestGeneration: Long,
        requestRef: BitmapRequest?,
        loaded: ArtworkHandle?,
        callbackDeliveredSynchronously: Boolean,
    ) {
        val live = binding ?: run { loaded?.release(); return }
        if (
            localGeneration != generation || localRequestGeneration != requestGeneration ||
            live.key != key || live.decodeSide != side
        ) {
            loaded?.release()
            return
        }

        if (request === requestRef || requestKey == key) {
            request = null
            requestKey = ""
            requestSide = 0
            lastRequestEndReason = if (loaded?.isValid == true) "complete:bitmap" else "complete:null"
        }
        if (loaded != null && loaded.isValid) {
            terminalNoArt = false
            fallbackBitmap = null
            fallbackShader = null
            val allowAnimation = shouldAnimateVirtualListProviderAdmission(
                callbackDeliveredSynchronously = callbackDeliveredSynchronously,
                isAttached = true,
                deferLoad = live.deferLoad,
                animateChanges = live.animateChanges,
            )
            accept(
                loaded,
                allowAnimation = allowAnimation,
                origin = if (callbackDeliveredSynchronously) "provider_sync" else "provider_async",
            )
        } else {
            loaded?.release()
            terminalNoArt = shouldAcceptVirtualListTerminalNoArt(
                callbackTerminalNoArt = requestRef?.terminalNoArt == true,
                hasUsableBitmap = currentBitmap?.isRecycled == false,
                hasValidProviderHandle = currentHandle?.isValid == true,
            )
            if (terminalNoArt && live.defaultArtworkEnabled) {
                fallbackBitmap = NodeDefaultArtworkCache.get(live.resources, side)
                fallbackShader = null
            }
            invalidateOwner()
        }
    }

    private fun accept(handle: ArtworkHandle, allowAnimation: Boolean, origin: String) {
        if (!handle.isValid) {
            handle.release()
            return
        }
        val bitmap = handle.bitmap
        if (bitmap.isRecycled) {
            handle.release()
            return
        }
        terminalNoArt = false
        lastRequestEndReason = origin
        fallbackBitmap = null
        fallbackShader = null
        val nextTier = handle.tier
        if (currentBitmap === bitmap) {
            // ArtworkImageNode.same-bitmap update helper: the exact same physical Bitmap is a visual no-op, but the wrapper
            // lease itself still has to be live. A reattach/cache recovery may return the same pixels
            // after the old handle was cancelled/closed; adopt that lease instead of throwing it away.
            val existingHandle = currentHandle
            if (existingHandle == null || !existingHandle.isValid) {
                existingHandle?.release()
                currentHandle = handle
            } else {
                handle.release()
            }
            currentTier = nextTier
            return
        }

        // ArtworkImageNode hands the alpha of the currently visible shader to the previous-wrapper paint
        // when a local replacement interrupts another reveal/replacement. Preserve that exact visual
        // alpha instead of restarting the outgoing bitmap from fully opaque.
        val currentVisibleAlpha = if (fadeDurationNs > 0L) fadeProgress.coerceIn(0f, 1f) else 1f
        val oldBitmap = currentBitmap
        val oldHandle = currentHandle
        val oldTier = currentTier
        val oldShader = currentShader
        releasePrevious()

        val admission = if (allowAnimation) {
            resolveVirtualListArtworkAdmissionAnimation(
                hadRealBitmap = oldBitmap != null,
                sameTier = oldBitmap != null && oldTier == nextTier,
                deferLoad = binding?.deferLoad == true,
                animateChanges = binding?.animateChanges == true,
            )
        } else {
            VirtualListArtworkAdmissionAnimation.None
        }

        when (admission) {
            VirtualListArtworkAdmissionAnimation.Replacement -> {
                previousBitmap = oldBitmap
                previousHandle = oldHandle
                previousShader = oldShader
                previousStartAlpha = currentVisibleAlpha
            }
            else -> {
                previousStartAlpha = 1f
                oldHandle?.release()
                previousBitmap = null
                previousHandle = null
                previousShader = null
            }
        }

        currentBitmap = bitmap
        currentHandle = handle
        currentTier = nextTier
        currentShader = null
        val durationMs = when (admission) {
            VirtualListArtworkAdmissionAnimation.Reveal -> RawArtworkPolicy.LIST_FIRST_REVEAL_MS.toLong()
            VirtualListArtworkAdmissionAnimation.Replacement -> Reference_AA_REPLACEMENT_MS
            VirtualListArtworkAdmissionAnimation.None -> 0L
        }
        if (durationMs > 0L) {
            fadeStartNs = System.nanoTime()
            fadeDurationNs = durationMs * 1_000_000L
            fadeProgress = 0f
        } else {
            stopFade()
            fadeProgress = 1f
            releasePrevious()
        }
        // All admission paths, including a synchronous shared-cache hit from ensureBestCached(),
        // change pixels recorded by the holder's retained base RenderNode. Invalidating only from
        // the asynchronous callback leaves that RenderNode showing its previous empty/low-tier
        // bitmap until an unrelated geometry change forces a recording.
        invalidateOwner()
    }


    private fun detachRequest(reason: String) {
        val active = request ?: return
        request = null
        requestKey = ""
        requestSide = 0
        requestGeneration += 1L
        lastRequestEndReason = "cancel:$reason"
        BitmapProvider.cancel(active)
    }

    private fun releaseCurrent() {
        currentHandle?.release()
        currentHandle = null
        currentBitmap = null
        currentTier = ArtworkTier.Any
        currentShader = null
    }

    private fun releasePrevious() {
        previousHandle?.release()
        previousHandle = null
        previousBitmap = null
        previousShader = null
        previousStartAlpha = 1f
    }

    private fun stopFade() {
        fadeDurationNs = 0L
        fadeStartNs = 0L
        fadeProgress = 1f
    }

    private fun isAcceptable(bitmap: Bitmap, requestedSide: Int): Boolean {
        return !bitmap.isRecycled && maxOf(bitmap.width, bitmap.height) >= requestedSide.coerceAtLeast(1)
    }

    private fun configureFitCenter(
        matrix: Matrix,
        bitmap: Bitmap,
        width: Float,
        height: Float,
        left: Float,
        top: Float,
        outRect: RectF,
    ) {
        matrix.reset()
        outRect.set(left, top, left, top)
        if (bitmap.width <= 0 || bitmap.height <= 0 || width <= 0f || height <= 0f) return
        val scale = min(width / bitmap.width.toFloat(), height / bitmap.height.toFloat())
        val drawnWidth = bitmap.width * scale
        val drawnHeight = bitmap.height * scale
        val dx = left + (width - drawnWidth) * 0.5f
        val dy = top + (height - drawnHeight) * 0.5f
        outRect.set(dx, dy, dx + drawnWidth, dy + drawnHeight)
        matrix.setScale(scale, scale)
        matrix.postTranslate(dx, dy)
    }
}


private object NodeDefaultArtworkCache {
    private val lock = Any()
    private val cache = LinkedHashMap<Int, Bitmap>()

    fun get(resources: Resources, requestedSide: Int): Bitmap? {
        val side = requestedSide.coerceIn(1, 1024)
        synchronized(lock) {
            cache[side]?.takeIf { !it.isRecycled }?.let { return it }
        }
        val decoded = decodeDefaultAlbumArtwork(resources, side) ?: return null
        synchronized(lock) {
            cache[side] = decoded
            while (cache.size > 6) cache.remove(cache.entries.first().key)
        }
        return decoded
    }
}

private fun Color.toArgbNode(): Int {
    val a = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val r = (red.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val g = (green.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    val b = (blue.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

private fun RectF.nearlyEqualsNode(other: RectF, tolerance: Float): Boolean {
    return kotlin.math.abs(left - other.left) <= tolerance &&
        kotlin.math.abs(top - other.top) <= tolerance &&
        kotlin.math.abs(right - other.right) <= tolerance &&
        kotlin.math.abs(bottom - other.bottom) <= tolerance
}

private const val VIRTUAL_LIST_NODE_TAG = "RawVirtualList"
private const val HANDOFF_TRACE_TAG = "SceneHandoff"
