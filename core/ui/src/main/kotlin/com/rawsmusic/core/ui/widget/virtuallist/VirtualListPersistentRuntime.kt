package com.rawsmusic.core.ui.widget.virtuallist

import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Persistent physical VirtualList runtime shared by HOME and root library providers.
 *
 * Reference keeps one VirtualList instance while only the provider/layout roles change.  The provider
 * registry alone is not enough to reproduce that ownership: the physical slot allocator, movable
 * holder owner and current/retained LayoutRes table must survive the provider switch as well.
 *
 * Keep this object free of scene identity.  Navigation owns which provider publishes CURRENT or
 * RETAINED LayoutRes; this runtime owns the physical holder lifetime across that switch.
 */
@Stable
internal class VirtualListPersistentRuntime {
    val physicalSlotPool = VirtualListPhysicalSlotPool()
    val physicalHolderOwner = VirtualListPhysicalHolderOwner()
    val physicalLayoutSlots = VirtualListDualLayoutSlots<Any, VirtualListPhysicalLayoutRes>()
    val settledNodeRuntime = VirtualListSettledNodeRuntime()

    fun clear() {
        physicalSlotPool.clear()
        physicalLayoutSlots.clear()
        physicalHolderOwner.clear()
        settledNodeRuntime.clear()
    }
}

internal val LocalVirtualListPersistentRuntime =
    staticCompositionLocalOf<VirtualListPersistentRuntime?> { null }

/** True when the HOME/root-library owner already owns the one permanent presentation node. */
internal val LocalVirtualListPresentationHostExternal =
    staticCompositionLocalOf { false }

/**
 * Extra top occlusion owned by the physical presentation host.
 *
 * Transparent top chrome cannot rely on a Compose parent clip because the retained Android/
 * RenderNode presentation host outlives that parent. Publish the requested top inset here so the
 * real presentation viewport clips the list pixels before they reach the fixed top chrome.
 */
internal val LocalVirtualListPresentationTopClipInset =
    staticCompositionLocalOf<Dp> { 0.dp }
