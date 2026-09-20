package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.platform.InspectorInfo

/**
 * Narrow Compose projection of exact VirtualList scroll. The owning composition scope is invalidated,
 * not every caller which merely holds [ComposeVirtualListState]. Keep reads inside small chrome/header
 * subtrees or rare exact-layout compatibility paths.
 */
@Composable
internal fun rememberVirtualListExactScrollPx(
    owner: VirtualListScrollPositionOwner,
    enabled: Boolean = true,
): State<Float> {
    val observed = remember(owner, enabled) { mutableFloatStateOf(owner.currentPx) }
    DisposableEffect(owner, enabled) {
        observed.floatValue = owner.currentPx
        if (!enabled) {
            onDispose { }
        } else {
            val listener: () -> Unit = {
                val value = owner.currentPx
                if (observed.floatValue != value) observed.floatValue = value
            }
            owner.addExactPositionListener(listener)
            onDispose { owner.removeExactPositionListener(listener) }
        }
    }
    return observed
}

/** Narrow fallback-only projection of the row-bucket fractional remainder. */
@Composable
internal fun rememberVirtualListRenderRemainderPx(
    owner: VirtualListScrollPositionOwner,
): State<Int> {
    val observed = remember(owner) { mutableIntStateOf(owner.currentRenderRemainderPx) }
    DisposableEffect(owner) {
        observed.intValue = owner.currentRenderRemainderPx
        val listener: () -> Unit = {
            val value = owner.currentRenderRemainderPx
            if (observed.intValue != value) observed.intValue = value
        }
        owner.addRenderInvalidationListener(listener)
        onDispose { owner.removeRenderInvalidationListener(listener) }
    }
    return observed
}

/**
 * Isolate per-pixel top-chrome progress from the page/body composition. Only [content] recomposes as
 * the list moves; the VirtualList rows and the surrounding page do not observe exact scroll state.
 */
@Composable
internal fun VirtualListScrollProgressObserver(
    owner: VirtualListScrollPositionOwner?,
    distancePx: Float,
    content: @Composable (Float) -> Unit,
) {
    if (owner == null) {
        content(0f)
        return
    }
    val scroll = rememberVirtualListExactScrollPx(owner).value
    val progress = if (distancePx > 0f) (scroll / distancePx).coerceIn(0f, 1f) else 0f
    content(progress)
}

/**
 * Draw-only settled remainder translation for non-interactive section headers. This keeps the
 * fractional row motion inside the VirtualList Node-era draw ownership without a per-pixel snapshot or
 * graphicsLayer state read. Interactive fallback holders deliberately keep their compatibility path.
 */
internal fun Modifier.virtualListRenderRemainderTranslation(
    owner: VirtualListScrollPositionOwner,
): Modifier = this then VirtualListRenderRemainderElement(owner)

private data class VirtualListRenderRemainderElement(
    val owner: VirtualListScrollPositionOwner,
) : ModifierNodeElement<VirtualListRenderRemainderNode>() {
    override fun create(): VirtualListRenderRemainderNode = VirtualListRenderRemainderNode(owner)

    override fun update(node: VirtualListRenderRemainderNode) {
        node.updateOwner(owner)
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "virtualListRenderRemainderTranslation"
    }
}

private class VirtualListRenderRemainderNode(
    initialOwner: VirtualListScrollPositionOwner,
) : Modifier.Node(), DrawModifierNode {
    override val shouldAutoInvalidate: Boolean
        get() = false

    private var owner = initialOwner
    private val invalidator: () -> Unit = {
        if (isAttached) invalidateDraw()
    }

    override fun onAttach() {
        super.onAttach()
        owner.addRenderInvalidationListener(invalidator)
        invalidateDraw()
    }

    override fun onDetach() {
        owner.removeRenderInvalidationListener(invalidator)
        super.onDetach()
    }

    fun updateOwner(next: VirtualListScrollPositionOwner) {
        if (owner === next) return
        if (isAttached) owner.removeRenderInvalidationListener(invalidator)
        owner = next
        if (isAttached) owner.addRenderInvalidationListener(invalidator)
        if (isAttached) invalidateDraw()
    }

    override fun ContentDrawScope.draw() {
        val contentDrawScope = this
        translate(top = -owner.currentRenderRemainderPx.toFloat()) {
            contentDrawScope.drawContent()
        }
    }
}
