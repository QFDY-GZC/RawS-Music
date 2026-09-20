package com.rawsmusic.core.ui.widget.virtuallist

/**
 * Retired compatibility marker.
 *
 * Reference baseline implementation keeps normal list artwork in the provider-wrapper lane. A raw/custom Bitmap
 * is an explicit ArtworkImageNode F0 binding and is not a fallback for a lost provider wrapper. Earlier
 * RawSMusic revisions used this object as a second VirtualList bitmap owner and could therefore turn
 * ordinary provider artwork into a naked-Bitmap lease that suppressed provider re-requests.
 *
 * Keep the source path as an overlay-safe tombstone for incremental packages, but do not add runtime
 * artwork state or acquisition APIs here. Normal VirtualList artwork is owned exclusively by
 * BitmapProvider / SizeSlotCache / ArtworkHandle.
 */
@Deprecated(
    message = "Normal VirtualList artwork must remain provider-owned; raw/custom Bitmap is explicit only",
    level = DeprecationLevel.ERROR,
)
internal object VirtualListArtworkWrapperResidency
