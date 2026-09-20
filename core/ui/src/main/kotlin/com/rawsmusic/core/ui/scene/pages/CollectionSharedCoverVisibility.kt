package com.rawsmusic.core.ui.scene.pages

/** True only while the concrete collection-header cover intersects the host window. */
internal fun isCollectionSharedCoverVisibleInWindow(
    left: Float,
    top: Float,
    right: Float,
    bottom: Float,
    hostWidth: Float,
    hostHeight: Float,
): Boolean {
    if (hostWidth <= 0f || hostHeight <= 0f) return false
    return right > 0f && left < hostWidth && bottom > 0f && top < hostHeight
}
