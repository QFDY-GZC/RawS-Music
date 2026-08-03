package com.rawsmusic.core.ui.scene

/**
 * Keeps the home centre holder and the shared transition holder mutually exclusive.
 *
 * The full-cover page owns the centre while [hideHomeCenter] is true. During the closing handoff,
 * [showSharedOwner] remains true for one rendered frame while the real home holder is still hidden;
 * both flags then switch off together.
 */
internal data class HomeFullCoverOwnerVisibility(
    val hideHomeCenter: Boolean,
    val showSharedOwner: Boolean,
)

internal fun resolveHomeFullCoverOwnerVisibility(
    hostActive: Boolean,
    transitionRunning: Boolean,
    predictiveBackActive: Boolean,
    closingHandoffPending: Boolean,
): HomeFullCoverOwnerVisibility = HomeFullCoverOwnerVisibility(
    hideHomeCenter = hostActive,
    showSharedOwner = transitionRunning || predictiveBackActive || closingHandoffPending,
)
