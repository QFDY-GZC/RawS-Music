package com.rawsmusic.core.ui.widget

/** Hidden ownership is independent of whether the player has already been visited. */
fun retainStandardPlayerOffstage(
    immersive: Boolean,
    hasSong: Boolean,
    playerVisible: Boolean,
    auxiliaryVisible: Boolean,
): Boolean = !immersive && hasSong && !playerVisible && !auxiliaryVisible
