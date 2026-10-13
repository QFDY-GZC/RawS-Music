package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.*
import org.junit.Assert.*
import org.junit.Test

class AutomaticVocalFallbackTest {
    private fun recipe(last: LyricLine) = AutoTransitionPolicy.build(
        AudioFile(path = "old", duration = 240000), AudioFile(path = "next", duration = 240000),
        LyricData(listOf(LyricLine(5000, "first"), LyricLine(80000, "second"),
            LyricLine(150000, "third"), last))).recipe!!

    @Test fun longNormalFinalLineStartsGentleMixTwoSecondsAfterItsStart() {
        val r = recipe(LyricLine(210000, "last", words = listOf(LyricWord("last", 210000, 235000))))
        assertEquals(212000L, r.gentleStartPositionMs)
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(r)
        assertNull(runtime.resolveStartPlan(211999, 240000))
        assertEquals(23000, runtime.resolveStartPlan(212000, 240000)?.preRollMs)
    }

    @Test fun missingTrustedWordEndDoesNotBlockTheGentleMix() {
        val r = recipe(LyricLine(220000, "last", endTime = 240000, endTimeIsEstimated = true))
        assertFalse(r.vocalEndKnown)
        assertEquals(222000L, r.gentleStartPositionMs)
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(r)
        assertNotNull(runtime.resolveStartPlan(222000, 240000))
    }

    @Test fun finalWordsAtFileEndStillAllowEarlyGentleMix() {
        val r = recipe(LyricLine(220000, "last", words = listOf(LyricWord("last", 220000, 240000))))
        assertEquals(222000L, r.gentleStartPositionMs)
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(r)
        val plan = runtime.resolveStartPlan(222000, 240000)!!
        assertEquals(3000, plan.preRollMs)
        assertEquals(15000, plan.handoverMs)
    }

    @Test fun endingMarkersStartImmediatelyWithoutNeedingWordTimings() {
        for (text in listOf("END", "end", "The EnD", "结束", "演唱结束", "完结")) {
            val r = recipe(LyricLine(230000, text))
            assertEquals(text, 230000L, r.gentleStartPositionMs)
            val runtime = AutoTransitionRuntime("test")
            runtime.updateRecipe(r)
            val plan = runtime.resolveStartPlan(230000, 240000)
            assertNotNull(text, plan)
            assertEquals(1000, plan?.preRollMs)
            assertEquals(9000, plan?.handoverMs)
        }
        assertFalse(TransitionLyricContent.isEnding("friend"))
    }

    @Test fun sungEndingWordsStartImmediatelyButRetainTheirRealVocalEnd() {
        val r = recipe(LyricLine(220000, "The End",
            words = listOf(LyricWord("The End", 220000, 235000))))
        assertEquals(220000L, r.gentleStartPositionMs)
        assertEquals(235000L, r.handoverPositionMs)
    }

    @Test fun timedSungEndingStillCountsAsOneOfTheFinalThreePhrases() {
        val r = AutoTransitionPolicy.build(AudioFile(path = "old", duration = 240000),
            AudioFile(path = "next", duration = 240000), LyricData(listOf(
                LyricLine(5000, "first"), LyricLine(210000, "second"), LyricLine(215000, "third"),
                LyricLine(220000, "The End", words = listOf(LyricWord("End", 220000, 227000)))))).recipe!!
        assertEquals(210000L, r.gentleStartPositionMs)
    }

    @Test fun vocalsStartingAtZeroToTwoSecondsKeepTheWholeOpening() {
        for (first in listOf(0L, 1000L, 2000L)) {
            assertEquals(0L, IncomingEntryPolicy.select(List(40) { -20f }, 100, first))
        }
    }

    @Test fun measuredTailMayFinishUnconfirmedVocalWindowEarlier() {
        val r = recipe(LyricLine(220000, "last"))
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(r)
        runtime.installTailPlan(r, OutgoingTailPlan(225000, 229000))
        val plan = runtime.resolveStartPlan(222000, 240000)!!
        assertEquals(3000, plan.preRollMs)
        assertEquals(15000, plan.handoverMs)
    }
}
