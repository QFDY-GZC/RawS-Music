package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class TransitionWindowBudgetTest {
    private fun envelopeRecipe() = AutoTransitionPolicy.Recipe("next",
        AutoTransitionPolicy.Source.ENVELOPE, null, null, 12000, confidence = .5f)

    @Test fun smallEnergyFallDoesNotCompressTheWholeFadeToTheCueLength() {
        val recipe = envelopeRecipe()
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        runtime.installTailPlan(recipe, OutgoingTailPlan(220000, 223000))
        assertEquals(15000, runtime.resolveStartPlan(220000, 250000)?.handoverMs)
    }
    @Test fun latePreparedIncomingStillGetsAFullAdjustmentWindow() {
        val recipe = envelopeRecipe()
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        runtime.installTailPlan(recipe, OutgoingTailPlan(220000, 223000))
        assertEquals(15000, runtime.resolveStartPlan(224000, 250000)?.handoverMs)
    }
    @Test fun lateLyricHandoverDoesNotInheritAnAlmostExpiredDeadline() {
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(AutoTransitionPolicy.Recipe("next", AutoTransitionPolicy.Source.LYRICS,
            200000, 210000, 15000, confidence = 1f, gentleStartPositionMs = 200000))
        val plan = runtime.resolveStartPlan(223000, 250000)!!
        assertEquals(0, plan.preRollMs)
        assertEquals(15000, plan.handoverMs)
    }
    @Test fun shortRemainingAudioStillLimitsTheWindowAndUiWaitsForCompletion() {
        val recipe = envelopeRecipe()
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(recipe)
        runtime.installTailPlan(recipe, OutgoingTailPlan(220000, 223000))
        assertEquals(6000, runtime.resolveStartPlan(246000, 250000)?.handoverMs)
        assertFalse(AutoTransitionUiPolicy.ready(14999, 15000, 14000, 1000, false))
        assertTrue(AutoTransitionUiPolicy.ready(15000, 15000, 14000, 1000, true))
    }

    @Test fun extendedWindowCannotConsumeTheEntireIncomingTrackBeforeHandover() {
        val plan = AutoTransitionStartPlan(20000, 15000, 8250, AutoTransitionPolicy.Source.LYRICS)
        val fitted = AutoTransitionWindowBudget.fitIncoming(plan, 30000, 0)!!
        assertEquals(20000, fitted.preRollMs)
        assertEquals(8500, fitted.handoverMs)
        assertNull(AutoTransitionWindowBudget.fitIncoming(plan, 30000, 10000))
    }

    @Test fun loudTailWithOnlyASmallFallDoesNotWaitUntilTheFinalSecond() {
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(envelopeRecipe())
        repeat(12) { index ->
            val amplitude = 16000 - index * 50
            val pcm = ByteArray(256)
            repeat(128) { i -> pcm[i * 2] = amplitude.toByte(); pcm[i * 2 + 1] = (amplitude shr 8).toByte() }
            runtime.observeCurrentPcm(223800L + index * 100L, pcm, pcm.size, false, false, 16)
        }
        assertNull(runtime.resolveStartPlan(224999, 240000))
        assertEquals(15000, runtime.resolveStartPlan(225000, 240000)?.handoverMs)
    }

    @Test fun olderRecipeDurationsCannotExceedTheNewWindowCap() {
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(AutoTransitionPolicy.Recipe("next", AutoTransitionPolicy.Source.LYRICS,
            210000, 220000, 20000, confidence = 1f))
        assertEquals(15000, runtime.resolveStartPlan(220000, 260000)?.handoverMs)
    }
}
