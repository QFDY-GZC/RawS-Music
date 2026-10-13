package com.rawsmusic.core.ui.widget.bitmaps

internal fun shouldRequestPlaybackArtworkHdr(
    sdk: Int,
    displaySupportsHdr: Boolean,
    hardwareAccelerated: Boolean,
    artworkVisible: Boolean,
    hasGainmap: Boolean,
): Boolean = sdk >= 34 && displaySupportsHdr && hardwareAccelerated && artworkVisible && hasGainmap
