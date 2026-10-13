package com.rawsmusic.core.ui.widget.bitmaps

import android.content.ContextWrapper
import androidx.compose.runtime.BroadcastFrameClock
import androidx.compose.runtime.MutableState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.*
import org.junit.Test

class PlaybackArtworkReverseResumeTest {
    @Test fun nextPreviousNextBeforeRollbackEndsKeepsLatestTarget() {
        repeat(20) { checkResume(PlayerArtworkDirection.Next, 2, 0) }
    }

    @Test fun previousNextPreviousAlsoKeepsLatestTarget() {
        repeat(20) { checkResume(PlayerArtworkDirection.Previous, 0, 2) }
    }

    @Test fun intermediateRendererAckDoesNotHideTheReturningMotion() {
        checkResume(PlayerArtworkDirection.Next, 2, 0, intermediateAck = true)
    }

    private fun checkResume(direction: PlayerArtworkDirection, currentIndex: Int, targetIndex: Int,
        intermediateAck: Boolean = false) {
        val clock = BroadcastFrameClock()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined + clock)
        val state = PlaybackArtworkTransitionState(scope, ContextWrapper(null))
        try {
            // Retained A/B holders mid-flight; no bitmap decoding is needed to test motion ownership.
            field(state, "artworkImageProviderOwnerCount", 1)
            state.bindSong("A", currentIndex, 3)
            delegated(state, "currentToken", 1)
            delegatedString(state, "targetKey", "B")
            field(state, "targetAutoSettle", true)
            val curveField = state.javaClass.getDeclaredField("targetSettleCurve").apply { isAccessible = true }
            curveField.set(state, curveField.type.enumConstants!!.first { (it as Enum<*>).name == "Programmatic" })
            delegated(state, "ratio", 0.6f)
            delegated(state, "isSettling", true)
            delegated(state, "manualProgrammaticTransition", true)
            assertTrue(state.reverseManualProgrammaticMotionToCurrent("A"))
            if (intermediateAck) state.bindSong("B", targetIndex, 3)
            assertTrue(state.startManualNavigation(direction, "B", targetIndex))
            assertEquals(0.6f, state.ratio, 0f)
            state.bindSong("B", targetIndex, 3)
            clock.sendFrame(System.nanoTime() + 2_000_000_000L)
            assertEquals("B", state.foregroundCurrentKey())
            assertEquals("", state.foregroundTargetKey())
            assertEquals(0f, state.ratio, 0f)
        } finally {
            scope.cancel()
        }
    }

    private fun field(state: PlaybackArtworkTransitionState, name: String, value: Any) {
        state.javaClass.getDeclaredField(name).apply { isAccessible = true }.set(state, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun delegatedString(state: PlaybackArtworkTransitionState, name: String, value: String) {
        delegated(state, name, value)
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T> delegated(state: PlaybackArtworkTransitionState, name: String, value: T) {
        val delegate = state.javaClass.getDeclaredField(name + "\$delegate").apply { isAccessible = true }
        (delegate.get(state) as MutableState<T>).value = value
    }
}
