package com.rawsmusic.module.data.prefs

import android.graphics.BitmapFactory
import kotlin.math.max

data class PlayerHiResBadgeSourceInfo(
    val widthPx: Int,
    val heightPx: Int,
) {
    val isValid: Boolean
        get() = widthPx > 0 && heightPx > 0

    val aspectRatio: Float
        get() = if (isValid) widthPx.toFloat() / heightPx.toFloat() else 1f
}

data class PlayerHiResBadgeDisplaySize(
    val widthDp: Float,
    val heightDp: Float,
)

fun readPlayerHiResBadgeSourceInfo(path: String): PlayerHiResBadgeSourceInfo? {
    if (path.isBlank()) return null
    return runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, options)
        PlayerHiResBadgeSourceInfo(options.outWidth, options.outHeight).takeIf { it.isValid }
    }.getOrNull()
}

/**
 * Resolve the visible badge size from the real source aspect ratio without mapping source pixels
 * 1:1 to dp. Imported artwork can be thousands of pixels wide; only its intrinsic aspect ratio is
 * meaningful for UI geometry. The built-in 58x28dp badge defines the 100% visual footprint and
 * imported images use the same 58dp longest-edge baseline.
 */
fun resolvePlayerHiResBadgeDisplaySize(
    customSource: PlayerHiResBadgeSourceInfo?,
    sizeScale: Float,
): PlayerHiResBadgeDisplaySize {
    val scale = sizeScale.coerceIn(
        PlayerHiResBadgeSettings.MIN_SIZE_SCALE,
        PlayerHiResBadgeSettings.MAX_SIZE_SCALE,
    )
    if (customSource == null || !customSource.isValid) {
        return PlayerHiResBadgeDisplaySize(
            widthDp = BUILTIN_HIRES_BADGE_WIDTH_DP * scale,
            heightDp = BUILTIN_HIRES_BADGE_HEIGHT_DP * scale,
        )
    }

    val longest = max(customSource.widthPx, customSource.heightPx).toFloat().coerceAtLeast(1f)
    val base = CUSTOM_HIRES_BADGE_LONG_EDGE_DP
    return PlayerHiResBadgeDisplaySize(
        widthDp = base * (customSource.widthPx / longest) * scale,
        heightDp = base * (customSource.heightPx / longest) * scale,
    )
}

const val BUILTIN_HIRES_BADGE_WIDTH_DP = 58f
const val BUILTIN_HIRES_BADGE_HEIGHT_DP = 28f
const val CUSTOM_HIRES_BADGE_LONG_EDGE_DP = 58f
