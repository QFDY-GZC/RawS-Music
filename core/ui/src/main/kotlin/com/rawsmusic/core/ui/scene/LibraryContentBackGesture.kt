package com.rawsmusic.core.ui.scene

/** A provider-level parent route handled by the scene's existing horizontal gesture recognizer. */
class LibraryContentBackGesture {
    internal var available: Boolean = false
    internal var begin: (Float) -> Boolean = { false }
    internal var update: (Float) -> Unit = {}
    internal var release: (Boolean, Float) -> Unit = { _, _ -> }
    internal var cancel: () -> Unit = {}
}

internal fun collectionBackPivotX(width: Float, interactive: Boolean, direction: Float): Float =
    if (!interactive) width * 0.5f else if (direction >= 0f) width * 1.5f else -width * 0.5f

internal fun providerOwnsBack(hasParent: Boolean, transitionActive: Boolean): Boolean = hasParent || transitionActive
