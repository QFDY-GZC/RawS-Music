package com.rawsmusic.core.ui.widget.player

import kotlin.math.roundToInt

/** Per-holder render resource cache; never shared across threads or songs. */
internal class LyricBlurEffectCache<T : Any>(private val create: (Float) -> T) {
    private val effects = LinkedHashMap<Int, T>()

    fun effect(radiusPx: Float): T? {
        if (!radiusPx.isFinite() || radiusPx <= 0f) return null
        // A quarter device pixel is below the visible precision of a blur, but avoids constructing
        // native effects for every tiny spring tail sample. Bound retained resources per holder.
        val key = (radiusPx.coerceAtMost(128f) * 4f).roundToInt()
        if (key <= 0) return null
        effects[key]?.let { return it }
        if (effects.size >= 64) effects.remove(effects.keys.first())
        return create(key / 4f).also { effects[key] = it }
    }
}
