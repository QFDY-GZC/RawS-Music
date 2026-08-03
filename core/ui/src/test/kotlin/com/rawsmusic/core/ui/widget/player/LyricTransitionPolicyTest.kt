package com.rawsmusic.core.ui.widget.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LyricTransitionPolicyTest {
    @Test
    fun manualForwardSeekPullsFollowingRowsEvenAcrossSeveralLines() {
        assertTrue(
            shouldPullFollowingLyrics(
                previousIndex = 10,
                newIndex = 14,
                manualSeekTransition = true,
                isPlaying = false,
                userScrolling = true,
                hasActiveInterlude = false
            )
        )
    }

    @Test
    fun manualBackwardSeekDoesNotRunForwardPull() {
        assertFalse(
            shouldPullFollowingLyrics(
                previousIndex = 14,
                newIndex = 10,
                manualSeekTransition = true,
                isPlaying = true,
                userScrolling = false,
                hasActiveInterlude = false
            )
        )
    }

    @Test
    fun naturalPlaybackKeepsAdjacentForwardGuard() {
        assertTrue(
            shouldPullFollowingLyrics(
                previousIndex = 20,
                newIndex = 21,
                manualSeekTransition = false,
                isPlaying = true,
                userScrolling = false,
                hasActiveInterlude = false
            )
        )
        assertFalse(
            shouldPullFollowingLyrics(
                previousIndex = 20,
                newIndex = 25,
                manualSeekTransition = false,
                isPlaying = true,
                userScrolling = false,
                hasActiveInterlude = false
            )
        )
    }

    @Test
    fun interludeNeverStartsLinePull() {
        assertFalse(
            shouldPullFollowingLyrics(
                previousIndex = 2,
                newIndex = 3,
                manualSeekTransition = true,
                isPlaying = true,
                userScrolling = false,
                hasActiveInterlude = true
            )
        )
    }
}
