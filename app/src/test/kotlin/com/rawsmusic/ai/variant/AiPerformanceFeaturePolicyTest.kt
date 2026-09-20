package com.rawsmusic.ai.variant

import com.rawsmusic.core.ui.widget.player.PlayerAiPerformanceMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiPerformanceFeaturePolicyTest {
    @Test
    fun sourceTagMapsOnlyPreparedAiVariants() {
        assertEquals(
            PlayerAiPerformanceMode.INSTRUMENT_PERFORMANCE,
            AiPerformanceFeaturePolicy.modeFromSourceTag("ai_variant:instrument_performance:abcdef"),
        )
        assertEquals(
            PlayerAiPerformanceMode.VOCAL_ENSEMBLE,
            AiPerformanceFeaturePolicy.modeFromSourceTag("ai_variant:vocal_ensemble:abcdef"),
        )
        assertEquals(PlayerAiPerformanceMode.ORIGINAL, AiPerformanceFeaturePolicy.modeFromSourceTag(null))
        assertEquals(PlayerAiPerformanceMode.ORIGINAL, AiPerformanceFeaturePolicy.modeFromSourceTag("other"))
    }

    @Test
    fun prerequisitesDoNotHideWhichLocalAssetIsMissing() {
        assertTrue(AiPerformanceFeaturePolicy.missingPrerequisite(false, false).contains("RMVPE"))
        assertTrue(AiPerformanceFeaturePolicy.missingPrerequisite(false, true).contains("RMVPE"))
        assertTrue(AiPerformanceFeaturePolicy.missingPrerequisite(true, false).contains("Piano"))
        assertEquals("", AiPerformanceFeaturePolicy.missingPrerequisite(true, true))
    }

    @Test
    fun separationOwnsOnlyItsReservedProgressWindow() {
        assertEquals(0.03f, AiPerformanceFeaturePolicy.separationProgress(0f), 0.0001f)
        assertEquals(0.235f, AiPerformanceFeaturePolicy.separationProgress(0.5f), 0.0001f)
        assertEquals(0.44f, AiPerformanceFeaturePolicy.separationProgress(1f), 0.0001f)
        assertEquals(0.44f, AiPerformanceFeaturePolicy.separationProgress(3f), 0.0001f)
    }
}
