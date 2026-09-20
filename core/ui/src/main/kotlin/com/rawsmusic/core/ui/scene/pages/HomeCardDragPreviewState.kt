package com.rawsmusic.core.ui.scene.pages

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import com.rawsmusic.core.ui.scene.NavScene
import kotlin.math.pow

/**
 * Visual-only HOME card reorder owner.
 *
 * Slot geometry is frozen at long-press capture. While the pointer moves, the dragged card follows
 * the finger and the remaining cards translate toward the slots they would occupy after the drop.
 * Persistence is deliberately deferred until UP so the VirtualList provider never rebuilds underneath
 * the active pointer transaction.
 */
@Stable
internal class HomeCardDragPreviewState {
    private val liveBounds = mutableStateMapOf<NavScene, Rect>()
    private var frozenBounds: Map<NavScene, Rect> = emptyMap()
    private var frozenOrder: List<NavScene> = emptyList()
    private var sourceIndex: Int = -1

    var activeScene by mutableStateOf<NavScene?>(null)
        private set
    var previewIndex by mutableIntStateOf(-1)
        private set
    var dragOffsetX by mutableFloatStateOf(0f)
        private set
    var dragOffsetY by mutableFloatStateOf(0f)
        private set
    var committedReleaseEpoch by mutableIntStateOf(0)
        private set

    fun publishSlot(scene: NavScene, bounds: Rect) {
        if (activeScene == null && bounds.width > 1f && bounds.height > 1f) {
            liveBounds[scene] = bounds
        }
    }

    fun start(scene: NavScene, visibleOrder: List<NavScene>) {
        val from = visibleOrder.indexOf(scene)
        if (from < 0) return
        frozenOrder = visibleOrder.toList()
        frozenBounds = liveBounds.filterKeys { it in frozenOrder }
        sourceIndex = from
        previewIndex = from
        dragOffsetX = 0f
        dragOffsetY = 0f
        activeScene = scene
    }

    fun dragBy(dx: Float, dy: Float) {
        val scene = activeScene ?: return
        dragOffsetX += dx
        dragOffsetY += dy
        val source = frozenBounds[scene] ?: return
        val center = source.center + Offset(dragOffsetX, dragOffsetY)
        val nearest = frozenOrder.indices
            .filter { frozenBounds[frozenOrder[it]] != null }
            .minByOrNull { index ->
                val target = checkNotNull(frozenBounds[frozenOrder[index]]).center
                (target.x - center.x).pow(2) + (target.y - center.y).pow(2)
            }
        if (nearest != null) previewIndex = nearest
    }

    fun draggedOffset(scene: NavScene): Offset =
        if (activeScene == scene) Offset(dragOffsetX, dragOffsetY) else Offset.Zero

    fun displacementFor(scene: NavScene): Offset {
        val dragged = activeScene ?: return Offset.Zero
        if (scene == dragged || sourceIndex !in frozenOrder.indices || previewIndex !in frozenOrder.indices) {
            return Offset.Zero
        }
        val ownIndex = frozenOrder.indexOf(scene)
        if (ownIndex < 0) return Offset.Zero
        val targetIndex = when {
            sourceIndex < previewIndex && ownIndex in (sourceIndex + 1)..previewIndex -> ownIndex - 1
            previewIndex < sourceIndex && ownIndex in previewIndex until sourceIndex -> ownIndex + 1
            else -> ownIndex
        }
        if (targetIndex == ownIndex) return Offset.Zero
        val own = frozenBounds[scene] ?: return Offset.Zero
        val targetSlotScene = frozenOrder.getOrNull(targetIndex) ?: return Offset.Zero
        val target = frozenBounds[targetSlotScene] ?: return Offset.Zero
        return target.topLeft - own.topLeft
    }

    fun targetIndex(scene: NavScene): Int? =
        previewIndex.takeIf { activeScene == scene && it >= 0 }

    fun commitAndClear() {
        committedReleaseEpoch++
        clear()
    }

    fun clear() {
        activeScene = null
        previewIndex = -1
        sourceIndex = -1
        dragOffsetX = 0f
        dragOffsetY = 0f
        frozenOrder = emptyList()
        frozenBounds = emptyMap()
    }
}
