package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertTrue
import org.junit.Test

class LyricFollowMotionTest {
    @Test
    fun shortIntervalsUseAResponsiveSettle() {
        val short = lyricFollowMotion(250L)
        val long = lyricFollowMotion(4_000L)

        assertTrue(short.stiffness > long.stiffness)
        assertTrue(short.dampingRatio < long.dampingRatio)
    }

    @Test
    fun instrumentalGapsUseTheSoftestSettle() {
        val gap = lyricFollowMotion(1_000L, isInterlude = true)
        val normal = lyricFollowMotion(1_000L)

        assertTrue(gap.stiffness < normal.stiffness)
        assertTrue(gap.dampingRatio > normal.dampingRatio)
    }
}
