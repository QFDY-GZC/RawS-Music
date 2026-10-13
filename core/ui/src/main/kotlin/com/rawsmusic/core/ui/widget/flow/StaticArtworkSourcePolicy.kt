package com.rawsmusic.core.ui.widget.flow

internal object StaticArtworkSourcePolicy {
    const val REQUEST_SIDE = 1200

    fun canReuse(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && maxOf(width, height) >= REQUEST_SIDE
}
