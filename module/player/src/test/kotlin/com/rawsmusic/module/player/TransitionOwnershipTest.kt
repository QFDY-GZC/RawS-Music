package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class TransitionOwnershipTest {
    @Test fun loudOldTrackDoesNotLoseUiToTheFourSecondTimeout() {
        assertFalse(AutoTransitionUiPolicy.ready(10000, 21000, 1000, 1000, false,
            gentleFrames = 6000, outgoingGain = .79f))
    }

    @Test fun protectedVocalTailClearsBeforeIncomingReachesFullVolume() {
        val config = AutomaticCrossfadeEnvelope.Config(21000, 1000, gentleFrames = 6000)
        val gains = AutomaticCrossfadeEnvelope.gains(19000, config, 1f, 0f)
        assertTrue(gains.second > .9f)
        assertEquals(0f, gains.first, .0001f)
    }

    @Test fun aLouderIncomingDoesNotHideAnOldTrackThatIsStillLoud() {
        assertFalse(AutoTransitionUiPolicy.incomingDominates(.5f, -3f, -20f))
        assertFalse(AutoTransitionUiPolicy.ready(10000, 15000, 2000, 1000, false,
            outgoingGain = .5f, dominantFrames = 1000, outgoingDb = -20f))
        assertTrue(AutoTransitionUiPolicy.incomingDominates(.08f, -12f, -12f, .75f))
    }

    @Test fun minimumWindowKeepsBothGainChangesContinuousAndNeverBoostsTheMix() {
        val config = AutomaticCrossfadeEnvelope.Config(7000, 1000, gentleFrames = 1000)
        var previous = AutomaticCrossfadeEnvelope.gains(1000, config, 1f, 0f)
        for (frame in 1100..7000 step 100) {
            val next = AutomaticCrossfadeEnvelope.gains(frame.toLong(), config, 1f, 0f)
            assertTrue(next.first <= previous.first)
            assertTrue(next.second >= previous.second)
            assertTrue(previous.first - next.first < .04f)
            assertTrue(next.second - previous.second < .04f)
            assertTrue(next.first + next.second <= 1.0001f)
            previous = next
        }
    }
}
