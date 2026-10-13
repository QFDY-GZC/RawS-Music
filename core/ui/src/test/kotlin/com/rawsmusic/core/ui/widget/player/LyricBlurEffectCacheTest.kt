package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class LyricBlurEffectCacheTest {
    private data class Effect(val radius: Float)

    @Test fun subpixelSpringSamplesReuseAnEffect() {
        val cache = LyricBlurEffectCache(::Effect)
        val first = cache.effect(3.01f)
        assertSame(first, cache.effect(3.02f))
        assertEquals(3f, first!!.radius, 0.001f)
        assertEquals(3.25f, cache.effect(3.24f)!!.radius, 0.001f)
    }

    @Test fun sharpAndInvalidFramesDoNotCreateAnEffect() {
        val cache = LyricBlurEffectCache(::Effect)
        assertNull(cache.effect(0f))
        assertNull(cache.effect(-1f))
        assertNull(cache.effect(Float.NaN))
        assertNull(cache.effect(Float.POSITIVE_INFINITY))
    }
}
