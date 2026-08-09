package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidDvcVolumeCurveTest {
    @Test
    fun endpointsRemainMuteAndUnity() {
        assertEquals(0f, AndroidDvcVolumeCurve.linearToGain(0f), 0f)
        assertEquals(1f, AndroidDvcVolumeCurve.linearToGain(1f), 0f)
    }

    @Test
    fun midpointUsesMinusThirtyDb() {
        assertEquals(0.03162f, AndroidDvcVolumeCurve.linearToGain(0.5f), 0.0001f)
    }

    @Test
    fun curveAndStepsAreMonotonic() {
        var previous = 0f
        for (step in 0..AndroidDvcVolumeCurve.MAX_STEPS) {
            val gain = AndroidDvcVolumeCurve.linearToGain(
                AndroidDvcVolumeCurve.stepToLinear(step)
            )
            assertTrue(gain >= previous)
            previous = gain
        }
        assertEquals(
            AndroidDvcVolumeCurve.MAX_STEPS,
            AndroidDvcVolumeCurve.linearToStep(1f),
        )
    }

    @Test
    fun planUsesNearestSystemStepAboveTargetAndNativeResidual() {
        val systemDb = listOf(Float.NEGATIVE_INFINITY, -48f, -36f, -24f, -12f, 0f)
        val plan = AndroidDvcVolumeCurve.plan(
            linear = 0.5f,
            systemMaxStep = 5,
            systemDbAt = systemDb::get,
        )

        assertEquals(3, plan.systemStep)
        assertEquals(-30f, plan.targetDb, 0.001f)
        assertEquals(-24f, plan.systemDb, 0.001f)
        assertEquals(0.50118f, plan.nativeGain, 0.0002f)
    }

    @Test
    fun planDoesNotForceSystemVolumeToMaximum() {
        val plan = AndroidDvcVolumeCurve.plan(
            linear = 0.75f,
            systemMaxStep = 15,
            systemDbAt = { step -> -60f + step * 4f },
        )

        assertTrue(plan.systemStep < 15)
        assertTrue(plan.nativeGain <= 1f)
    }
}
