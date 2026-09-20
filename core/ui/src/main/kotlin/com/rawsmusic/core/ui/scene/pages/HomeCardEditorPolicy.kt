package com.rawsmusic.core.ui.scene.pages

import kotlin.math.roundToInt

/** Pure two-column HOME-card drop policy, kept outside Compose so it can be regression-tested. */
internal fun resolveHomeCardDropDelta(
    offsetX: Float,
    offsetY: Float,
    tileWidthPx: Int,
    tileHeightPx: Int,
): Int {
    val safeWidth = tileWidthPx.coerceAtLeast(1).toFloat()
    val safeHeight = tileHeightPx.coerceAtLeast(1).toFloat()
    val columnDelta = (offsetX / safeWidth).roundToInt().coerceIn(-1, 1)
    val rowDelta = (offsetY / safeHeight).roundToInt()
    return rowDelta * 2 + columnDelta
}


/** Stable pseudo-random artwork slot derived from card identity, never from its visual position. */
internal fun stableHomeCardArtworkIndex(sceneTag: String, salt: String, poolSize: Int): Int {
    if (poolSize <= 0) return -1
    var hash = 0x811C9DC5.toInt()
    ("$salt|$sceneTag").forEach { ch ->
        hash = hash xor ch.code
        hash *= 16777619
    }
    return (hash and Int.MAX_VALUE) % poolSize
}
