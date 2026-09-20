package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.runtime.staticCompositionLocalOf
import com.rawsmusic.core.ui.scene.RetainedSceneItemTransform

/**
 * Provider-to-provider navigation inside one logical library scene.
 *
 * Reference Folders Hierarchy does not replace the VirtualList object when entering a child folder.
 * The old provider becomes RETAINED, the child provider becomes CURRENT, and both LayoutRes roles
 * share the same physical holder/runtime until the GenericPivot finishes.
 */
internal data class VirtualListProviderNavigationFrame(
    val active: Boolean = false,
    val retainedProvider: ReferenceRegisteredVirtualList? = null,
    val retainedTransform: RetainedSceneItemTransform? = null,
    val currentTransform: RetainedSceneItemTransform? = null,
    val onPopulationPublished: (() -> Unit)? = null,
)

internal val LocalVirtualListProviderNavigationFrame =
    staticCompositionLocalOf { VirtualListProviderNavigationFrame() }

internal fun usesLocalProviderNavigation(localActive: Boolean, hasRetainedProvider: Boolean, outerActive: Boolean): Boolean =
    localActive && hasRetainedProvider && !outerActive

/** Snapshot only the layout state Reference keeps behind the retained provider. */
internal fun ComposeVirtualListState.copyForRetainedProvider(): ComposeVirtualListState =
    ComposeVirtualListState(
        initialLevel = currentLevel,
        initialColumns = currentColumns,
        persistZoomLevel = {},
        persistColumns = {},
    ).also { snapshot ->
        snapshot.viewportScrollY = viewportScrollY
        snapshot.updateVisibleRangeForNavigation(currentVisibleRange)
    }
