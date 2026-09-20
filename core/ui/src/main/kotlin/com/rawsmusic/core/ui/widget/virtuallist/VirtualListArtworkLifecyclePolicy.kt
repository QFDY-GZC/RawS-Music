package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Presentation-only policy for a retained VirtualList artwork holder.
 *
 * Loading is intentionally distinct from terminal no-art: an unresolved provider request keeps the
 * neutral row color visible. Default artwork is admitted only after the provider has made a terminal
 * no-art decision for the versioned source identity.
 */
internal enum class VirtualListArtworkPlaceholder {
    LoadingColor,
    TerminalColor,
    TerminalDefaultArtwork,
}

internal fun resolveVirtualListArtworkPlaceholder(
    terminalNoArt: Boolean,
    defaultArtworkEnabled: Boolean,
): VirtualListArtworkPlaceholder = when {
    !terminalNoArt -> VirtualListArtworkPlaceholder.LoadingColor
    defaultArtworkEnabled -> VirtualListArtworkPlaceholder.TerminalDefaultArtwork
    else -> VirtualListArtworkPlaceholder.TerminalColor
}

internal enum class VirtualListArtworkAdmissionAnimation {
    None,
    Reveal,
    Replacement,
}

/**
 * Mirrors the ArtworkImageNode split between first wrapper admission and replacement of accepted pixels.
 * A deferred holder does not start a local image clock. VirtualList/GenericPivot geometry ownership is
 * independent: an async wrapper may still run ArtworkImageNode's local 200 ms reveal while its parent moves.
 * Quality upgrades are deliberately not represented here as a replacement: callers pass [sameTier] = false.
 */
internal fun resolveVirtualListArtworkAdmissionAnimation(
    hadRealBitmap: Boolean,
    sameTier: Boolean,
    deferLoad: Boolean,
    animateChanges: Boolean,
): VirtualListArtworkAdmissionAnimation = when {
    deferLoad || !animateChanges -> VirtualListArtworkAdmissionAnimation.None
    !hadRealBitmap -> VirtualListArtworkAdmissionAnimation.Reveal
    sameTier -> VirtualListArtworkAdmissionAnimation.Replacement
    else -> VirtualListArtworkAdmissionAnimation.None
}

/**
 * A cache/synchronous re-attachment is not a fresh visual admission. Reference's provider callback
 * can resolve to an already-owned wrapper and pass same-bitmap update helper mode 0; temporary detach/rebind must not
 * manufacture another reveal. Only a genuinely asynchronous miss that resolves while the holder is
 * attached and stable may start the first-wrapper clock.
 */
internal fun shouldAnimateVirtualListProviderAdmission(
    callbackDeliveredSynchronously: Boolean,
    isAttached: Boolean,
    deferLoad: Boolean,
    animateChanges: Boolean,
): Boolean =
    !callbackDeliveredSynchronously &&
        isAttached &&
        !deferLoad &&
        animateChanges


/**
 * A physical VirtualList slot is temporarily reattaching when it existed before the immediately
 * previous settled frame but is present again now. First attach and continuously-visible holders are
 * not reattach events. This is the plain-holder equivalent of View.onStart/FinishTemporaryDetach.
 */
internal fun isVirtualListTemporaryArtworkReattach(
    lastAttachedGeneration: Int,
    previousVisibleGeneration: Int,
): Boolean =
    lastAttachedGeneration != 0 && lastAttachedGeneration != previousVisibleGeneration

/**
 * A provider identity that really reached the screen once must not inherit a later terminal-no-art
 * decision after its physical bitmap lease disappears during a temporary detach. Re-entry retries
 * cache/provider ownership; a genuinely never-presented no-art identity remains terminal.
 */
internal fun shouldRetryPresentedArtworkAfterLeaseLoss(
    wasPresented: Boolean,
    hasUsableBitmap: Boolean,
    hasValidProviderHandle: Boolean = hasUsableBitmap,
): Boolean = wasPresented && (!hasUsableBitmap || !hasValidProviderHandle)

/**
 * Normal Reference artwork list artwork is satisfied only by a usable bitmap that is still backed by a
 * live provider wrapper. A naked bitmap is the explicit F0(custom bitmap) lane and must never be
 * inferred automatically from a lost provider lease.
 */
internal fun isVirtualListProviderArtworkSatisfied(
    hasUsableBitmap: Boolean,
    coversTarget: Boolean,
    hasValidProviderHandle: Boolean,
    hasRequestedTier: Boolean = true,
): Boolean = hasUsableBitmap && coversTarget && hasValidProviderHandle && hasRequestedTier

/** A terminal no-art callback cannot override an already-owned provider wrapper for this identity. */
internal fun shouldAcceptVirtualListTerminalNoArt(
    callbackTerminalNoArt: Boolean,
    hasUsableBitmap: Boolean,
    hasValidProviderHandle: Boolean,
): Boolean = callbackTerminalNoArt && !(hasUsableBitmap && hasValidProviderHandle)


/**
 * A real ArtworkImageNode is redrawn when it returns from RecyclerView/VirtualList temporary invisibility.
 * Raw's retained RenderNode/Modifier.Node must synthesize that same visual invalidation when the
 * exact artwork window changes from deferred/offscreen back to visible, even if key and pixel size
 * are unchanged. Pinch changing the size must not be the only event that refreshes the image.
 */
internal fun shouldRefreshVirtualListArtworkOnVisualReentry(
    wasDeferred: Boolean,
    isDeferred: Boolean,
): Boolean = wasDeferred && !isDeferred
