package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticVocalWindowTest {
    @Test fun choosesLargestEligibleFinalPhraseWindow() {
        assertEquals(220000L, AutoTransitionVocalWindow.select(listOf(220000L, 230000L, 234000L), 238000L))
        assertEquals(225000L, AutoTransitionVocalWindow.select(listOf(211000L, 225000L, 231000L), 238000L))
        assertEquals(231000L, AutoTransitionVocalWindow.select(listOf(211000L, 222000L, 231000L), 238000L))
        assertNull(AutoTransitionVocalWindow.select(listOf(210000L, 222000L, 229000L), 238000L))
    }

    @Test fun opensBeforeLastWordsAndBoundsMainHandoverToRemainingAudio() {
        val lyrics = LyricData(listOf(LyricLine(5000, "a"), LyricLine(150000, "b"),
            LyricLine(230000, "c"), LyricLine(234000, "d"),
            LyricLine(236000, "e", words = listOf(LyricWord("e", 236000, 238000)))))
        val recipe = AutoTransitionPolicy.build(AudioFile(path = "old", duration = 240000),
            AudioFile(path = "next", duration = 240000), lyrics).recipe!!
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        runtime.installTailPlan(recipe, OutgoingTailPlan(238500, 239500))
        assertNull(runtime.resolveStartPlan(229999, 240000))
        val plan = runtime.resolveStartPlan(230000, 240000)!!
        assertEquals(8000, plan.preRollMs)
        assertEquals(6000, plan.handoverMs)
        assertEquals(238000L, recipe.handoverPositionMs)
    }

    @Test fun gentlePhaseKeepsLeadClearAndFollowAudibleThenExchangesGainsSmoothly() {
        val config = AutomaticCrossfadeEnvelope.Config(10000, 1000, gentleFrames = 6000)
        val first = AutomaticCrossfadeEnvelope.gains(1000, config, 1f, 0f)
        val vocalEnd = AutomaticCrossfadeEnvelope.gains(6000, config, 1f, 0f)
        val midpoint = AutomaticCrossfadeEnvelope.gains(8000, config, 1f, 0f)
        val end = AutomaticCrossfadeEnvelope.gains(10000, config, 1f, 0f)
        assertEquals(.975f, first.first, .0001f)
        assertEquals(.05f, vocalEnd.second, .0001f)
        assertEquals(.3152158f, midpoint.second, .0001f)
        assertEquals(1f, end.second, .0001f)
        for (frame in 0..10000 step 100) {
            val gains = AutomaticCrossfadeEnvelope.gains(frame.toLong(), config, 1f, 0f)
            assertTrue(gains.first + gains.second <= 1.0001f)
        }
    }

    @Test fun gentleMixKeepsOldUiUntilAudioCutoff() {
        assertFalse(AutoTransitionUiPolicy.ready(9500, 10000, 5000, 1000, false, gentleFrames = 6000))
        assertTrue(AutoTransitionUiPolicy.ready(10000, 10000, 5000, 1000, true, gentleFrames = 6000))
    }

    @Test fun fallbackMixerUsesSameTwoPhaseTimelineAndCompletesAtCutoff() {
        val mixer = KotlinAutomaticCrossfadeProcessor()
        assertTrue(mixer.start(AutoTransitionStartPlan(6000, 4000, 2200,
            AutoTransitionPolicy.Source.LYRICS), 1000))
        for (block in 0 until 100) {
            val old = ByteArray(200)
            val next = ByteArray(200)
            repeat(100) { next[it * 2 + 1] = 32 }
            val result = mixer.mixInPlace(old, next, 0, 200, 1000, 2, 16, false, false)
            val lastSample = ((old[199].toInt() shl 8) or (old[198].toInt() and 255))
            if (block == 59) assertEquals(123f, lastSample.toFloat(), 2f)
            if (block == 79) assertEquals(2376f, lastSample.toFloat(), 3f)
            assertEquals(block == 99, result.completed)
        }
    }

    @Test fun longIntroRetainsContextAndValidatesActualVocalEnergy() {
        assertEquals(12500L, IncomingEntryPolicy.select(List(40) { -20f }, 100,
            firstLyricMs = 20000, introLeadMs = 7500, sampleStartMs = 19000))
        assertEquals(112500L, IncomingEntryPolicy.select(List(40) { -20f }, 100,
            firstLyricMs = 120000, introLeadMs = 7500, sampleStartMs = 119000))
        assertEquals(0L, IncomingEntryPolicy.select(List(40) { -60f }, 100,
            firstLyricMs = 20000, introLeadMs = 7500, sampleStartMs = 19000))
    }
}
