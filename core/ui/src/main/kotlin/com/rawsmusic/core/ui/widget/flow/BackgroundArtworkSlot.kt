package com.rawsmusic.core.ui.widget.flow

/** Identity travels with async pixels, independently of the composition's latest requested key. */
internal data class BackgroundArtworkSlot<T>(val key: String?, val artwork: T?) {
    fun forKey(requestedKey: String?): T? = artwork.takeIf {
        !requestedKey.isNullOrBlank() && requestedKey == key
    }
}
