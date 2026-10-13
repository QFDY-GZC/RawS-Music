package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class TransitionLyricContentTest {
    @Test fun creditsAreNotVocalLines() {
        for (line in listOf("作词：某某", "作曲: 某某", "编曲：某某", "Lyrics by: A", "Vocal: B", "作詞：作者"))
            assertFalse(line, TransitionLyricContent.isPerformed(line))
    }
    @Test fun wordsInsideRealLyricsDoNotEraseTheLine() {
        assertTrue(TransitionLyricContent.isPerformed("你为我的青春作词"))
        assertTrue(TransitionLyricContent.isPerformed("音乐从未停下"))
    }
    @Test fun tailPlanningWaitsForFadeStartThenUsesCutoff() {
        val runtime = AutoTransitionRuntime("test")
        val recipe = AutoTransitionPolicy.Recipe("next", AutoTransitionPolicy.Source.ENVELOPE,
            null, null, 12000, confidence = .7f)
        runtime.updateRecipe(recipe)
        runtime.installTailPlan(recipe, OutgoingTailPlan(280000, 283000))
        assertNull(runtime.resolveStartPlan(279000, 300000))
        assertEquals(15000, runtime.resolveStartPlan(280000, 300000)!!.handoverMs)
        runtime.updateRecipe(recipe.copy(targetPath = "another"))
        runtime.installTailPlan(recipe, OutgoingTailPlan(280000, 283000))
        assertNull(runtime.resolveStartPlan(280000, 300000))
    }
}
