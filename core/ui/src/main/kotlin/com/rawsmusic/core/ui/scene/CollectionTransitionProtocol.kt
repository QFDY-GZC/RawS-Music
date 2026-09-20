package com.rawsmusic.core.ui.scene

import androidx.compose.runtime.withFrameNanos
import kotlin.math.roundToInt

/** Shared layout handshake for scene navigation and navigation between directory providers. */
internal suspend fun awaitCollectionSharedPair(
    candidate: () -> Pair<SharedCoverSnapshot, SharedCoverSnapshot>?,
): Pair<SharedCoverSnapshot, SharedCoverSnapshot>? {
    // retained-view implementation's ItemToHeader/HeaderToItem B() observes the LayoutRes made available by endpoint preparation;
    // it does not add an extra frame-count retry loop in the transition protocol. If Raw does not
    // have the target endpoint at this boundary, the provider publication path is wrong and should
    // be fixed there instead of hiding it behind a transition-side wait.
    fun eligible(pair: Pair<SharedCoverSnapshot, SharedCoverSnapshot>?): Pair<SharedCoverSnapshot, SharedCoverSnapshot>? =
        pair?.takeIf { it.first.sharedEligible && it.second.sharedEligible }

    return eligible(candidate())
}

/**
 * Wait only for shared-pixel ownership, never for artwork decoding itself. retained-view implementation starts motion
 * once either the concrete promoted holder or the prepared overlay has a drawable owner; a cold
 * bitmap may still arrive later through the normal provider path. If the prepared transaction is
 * replaced while waiting, return immediately and let the caller fall back to ordinary motion.
 */
internal suspend fun awaitSharedActorReady(
    registry: SharedCoverRegistry,
    transitionKey: String,
    nextFrame: suspend () -> Unit = { withFrameNanos { } },
): Boolean {
    if (transitionKey.isBlank()) return false
    repeat(6) {
        val pair = registry.getPreparedPair(transitionKey) ?: return false
        if (registry.isOverlayReady(transitionKey)) return true
        if (registry.isPhysicalPromotedElement(pair.first.elementId)) return true
        nextFrame()
    }
    val pair = registry.getPreparedPair(transitionKey) ?: return false
    return registry.isOverlayReady(transitionKey) ||
        registry.isPhysicalPromotedElement(pair.first.elementId)
}

/** Non-fling release duration, shared by system-back and the scene's content-back gesture. */
internal fun collectionReleaseDuration(progress: Float, commit: Boolean): Int =
    if (commit) ((1f - progress.coerceIn(0f, 1f)) * VIRTUAL_LIST_COMMIT_MS).roundToInt().coerceAtLeast(100)
    else (progress.coerceIn(0f, 1f) * 500).roundToInt().coerceAtLeast(500 / 3)

internal data class CollectionReleaseMotion(val durationMillis: Int, val carryVelocity: Boolean)

internal fun collectionReleaseMotion(progress: Float, commit: Boolean, normalizedVelocity: Float): CollectionReleaseMotion {
    val p = progress.coerceIn(0f, 1f)
    val carry = if (commit) p > 0.8f && normalizedVelocity > 0f else p < 0.2f && normalizedVelocity < 0f
    val duration = when {
        carry && commit -> (((1f - p) / normalizedVelocity.coerceIn(4f, 8f)) * 1000f).roundToInt().coerceAtLeast(1)
        carry -> ((p / -normalizedVelocity.coerceIn(-8f, -3.5f)) * 1000f).roundToInt().coerceAtLeast(1)
        else -> collectionReleaseDuration(p, commit)
    }
    return CollectionReleaseMotion(duration, carry)
}
