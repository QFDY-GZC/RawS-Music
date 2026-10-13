package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class TransitionNearEofWindowTest {
    @Test fun loggedEighteenSecondPreludeCannotLeaveOnlySeventyThreeMillisecondsForMainFade() {
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(AutoTransitionPolicy.Recipe("next", AutoTransitionPolicy.Source.LYRICS,
            221110, 239927, 73, confidence = 1f, gentleStartPositionMs = 221110))
        val plan = runtime.resolveStartPlan(221110, 240000)!!
        assertEquals(18817, plan.preRollMs)
        assertTrue(plan.handoverMs >= 6000)
        assertTrue(plan.handoverMs <= 15000)
    }
    @Test fun shortOldTailDoesNotForceARapidIncomingBoost() {
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(AutoTransitionPolicy.Recipe("next", AutoTransitionPolicy.Source.ENVELOPE,
            null, null, 12000, confidence = .5f))
        runtime.installTailPlan(runtime.currentRecipe()!!, OutgoingTailPlan(220000, 223000))
        assertEquals(6000, runtime.resolveStartPlan(249927, 250000)?.handoverMs)
    }
    @Test fun finalBudgetProtectsAgainstShortPlansFromOtherCallers() {
        val plan = AutoTransitionStartPlan(18817, 73, 40, AutoTransitionPolicy.Source.LYRICS)
        val safe = AutoTransitionWindowBudget.fitIncoming(plan, 240000, 0)!!
        assertEquals(6000, safe.handoverMs)
        assertNull(AutoTransitionWindowBudget.fitIncoming(plan, 24000, 0))
    }
}
