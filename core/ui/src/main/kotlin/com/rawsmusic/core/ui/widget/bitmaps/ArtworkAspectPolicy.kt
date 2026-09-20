package com.rawsmusic.core.ui.widget.bitmaps

import kotlin.math.roundToInt

/**
 * Album-art geometry policy. Crop keeps the historical square provider wrapper. KeepAspect keeps
 * the source width/height ratio and fits the decoded bitmap inside the requested decode envelope.
 *
 * KeepAspect has its own provider/cache identity so an old square-cropped wrapper can never satisfy
 * a Reference-style keep-aspect list/player request.
 */
enum class ArtworkAspectPolicy {
    Crop,
    KeepAspect,
}

internal data class ArtworkScaledSize(val width: Int, val height: Int)

internal fun resolveArtworkScaledSize(
    sourceWidth: Int,
    sourceHeight: Int,
    targetWidth: Int,
    targetHeight: Int,
    policy: ArtworkAspectPolicy,
): ArtworkScaledSize {
    val sw = sourceWidth.coerceAtLeast(1)
    val sh = sourceHeight.coerceAtLeast(1)
    val tw = targetWidth.coerceAtLeast(1)
    val th = targetHeight.coerceAtLeast(1)
    if (policy == ArtworkAspectPolicy.Crop) return ArtworkScaledSize(tw, th)

    val scale = minOf(tw / sw.toFloat(), th / sh.toFloat())
    return ArtworkScaledSize(
        width = (sw * scale).roundToInt().coerceIn(1, tw),
        height = (sh * scale).roundToInt().coerceIn(1, th),
    )
}

private const val KEEP_ASPECT_CACHE_SUFFIX = "|raw-aa-fit-v1"

internal fun artworkAspectCacheSourceKey(key: String, policy: ArtworkAspectPolicy): String =
    if (policy == ArtworkAspectPolicy.KeepAspect && key.isNotBlank()) "$key$KEEP_ASPECT_CACHE_SUFFIX" else key

internal fun artworkAspectSourceString(sourceString: String, policy: ArtworkAspectPolicy): String =
    if (policy == ArtworkAspectPolicy.KeepAspect && sourceString.isNotBlank()) "$sourceString$KEEP_ASPECT_CACHE_SUFFIX" else sourceString

internal fun isKeepAspectArtworkCacheSourceKey(key: String): Boolean =
    key.endsWith(KEEP_ASPECT_CACHE_SUFFIX)
