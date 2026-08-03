package com.rawsmusic

internal data class HomeFullCoverOverlayLayer(
    val zIndex: Float,
    val alpha: Float,
)

internal fun resolveHomeFullCoverOverlayLayer(active: Boolean): HomeFullCoverOverlayLayer =
    if (active) {
        HomeFullCoverOverlayLayer(zIndex = -1f, alpha = 0f)
    } else {
        HomeFullCoverOverlayLayer(zIndex = 1f, alpha = 1f)
    }

internal data class HomeFullCoverSceneOwnershipDecision(
    val resetControllerToMain: Boolean,
    val resetComposeToMain: Boolean,
)

internal fun resolveHomeFullCoverSceneOwnershipDecision(
    controllerSceneIsMain: Boolean,
    controllerComposeSceneIsMain: Boolean,
    controllerTransitioning: Boolean,
    composeSceneIsMain: Boolean,
): HomeFullCoverSceneOwnershipDecision = HomeFullCoverSceneOwnershipDecision(
    resetControllerToMain =
        !controllerSceneIsMain || !controllerComposeSceneIsMain || controllerTransitioning,
    resetComposeToMain = !composeSceneIsMain,
)
