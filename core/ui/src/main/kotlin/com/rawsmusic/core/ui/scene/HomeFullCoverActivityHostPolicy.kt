package com.rawsmusic.core.ui.scene

/**
 * Activity-root ownership for the portrait home full-cover scene.
 *
 * The portrait dial is an in-window scene. Entering or leaving it must not dispose the normal
 * player overlay or mutate Activity orientation while the shared artwork owns the transition.
 */
data class HomeFullCoverActivityHostPolicy(
    val playerOverlayZIndex: Float,
    val playerOverlayAlpha: Float,
    val blockRootSceneGesture: Boolean,
    val landscapeLaunchArmed: Boolean,
    val clearPendingLandscapeLaunch: Boolean,
    val refreshOrientationPolicy: Boolean,
)

fun resolveHomeFullCoverActivityHostPolicy(
    active: Boolean,
): HomeFullCoverActivityHostPolicy = HomeFullCoverActivityHostPolicy(
    playerOverlayZIndex = if (active) -1f else 1f,
    playerOverlayAlpha = if (active) 0f else 1f,
    blockRootSceneGesture = active,
    landscapeLaunchArmed = !active,
    clearPendingLandscapeLaunch = active,
    // HOME is already portrait. Refreshing requestedOrientation in this callback can relayout the
    // decor view between shared-artwork frames, so scene/orientation events own that policy instead.
    refreshOrientationPolicy = false,
)
