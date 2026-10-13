package com.rawsmusic.core.ui.widget.background

import com.rawsmusic.core.ui.widget.bitmaps.ArtworkAspectPolicy

/** Background decoding must preserve the source before the viewport applies its display mode. */
internal object CustomBackgroundImagePolicy {
    val decodeAspect: ArtworkAspectPolicy = ArtworkAspectPolicy.KeepAspect
}
