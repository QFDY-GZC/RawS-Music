package com.rawsmusic.core.ui.scene

import androidx.compose.ui.Modifier

/**
 * Compatibility marker retained for VirtualList call sites.
 *
 * Category transitions now promote only the selected artwork through
 * [SharedCoverRegistry]. Recording every visible row duplicated geometry ownership and allowed
 * recycled slots to replace the immutable return endpoint.
 */
fun Modifier.virtualListSceneTransitionItem(
    sceneId: String,
    itemId: String
): Modifier {
    @Suppress("UNUSED_VARIABLE")
    val compatibilityKey = sceneId to itemId
    return this
}
