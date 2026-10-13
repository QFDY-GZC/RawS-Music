package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test
import kotlin.math.log10

class TransitionGainContinuityTest {
    @Test fun longLyricTailGetsFifteenSecondsAfterVocalEnd() {
        val runtime = AutoTransitionRuntime("test")
        runtime.updateRecipe(AutoTransitionPolicy.Recipe("next", AutoTransitionPolicy.Source.LYRICS,
            200000, 210000, 20000, confidence = 1f, gentleStartPositionMs = 200000))
        val plan = runtime.resolveStartPlan(200000, 240000)!!
        assertEquals(10000, plan.preRollMs)
        assertEquals(15000, plan.handoverMs)
    }

    @Test fun mainIncomingGainRisesInDbWithoutLargeHundredMillisecondSteps() {
        val config = AutomaticCrossfadeEnvelope.Config(11000, 1000, gentleFrames = 6000)
        val middle = AutomaticCrossfadeEnvelope.gains(8500, config, 1f, 0f)
        assertEquals(.3152158f, middle.second, .0001f)
        var previous = AutomaticCrossfadeEnvelope.gains(6000, config, 1f, 0f)
        for (frame in 6100..11000 step 100) {
            val gains = AutomaticCrossfadeEnvelope.gains(frame.toLong(), config, 1f, 0f)
            assertTrue(20f * log10(gains.second / previous.second) <= .8f)
            assertTrue(previous.first - gains.first < .05f)
            assertTrue(gains.first + gains.second <= 1.0001f)
            previous = gains
        }
    }

    @Test fun extendedWindowKeepsIncomingDbStepsSmallWithTheLowerEntryFloor() {
        val config = AutomaticCrossfadeEnvelope.Config(21000, 1000, gentleFrames = 6000)
        var previous = AutomaticCrossfadeEnvelope.gains(6000, config, 1f, 0f)
        for (frame in 6100..21000 step 100) {
            val gains = AutomaticCrossfadeEnvelope.gains(frame.toLong(), config, 1f, 0f)
            assertTrue(20f * log10(gains.second / previous.second) <= .27f)
            assertTrue(previous.first - gains.first < .017f)
            previous = gains
        }
    }

    @Test fun missingIncomingBufferKeepsTheActualOutgoingGainAndFrameClock() {
        val processor = KotlinAutomaticCrossfadeProcessor()
        processor.start(AutoTransitionStartPlan(6000, 5000, 2750, AutoTransitionPolicy.Source.LYRICS), 1000)
        val old = ByteArray(2000)
        processor.mixInPlace(old, ByteArray(2000), 0, 2000, 1000, 2, 16, false, false)
        val gain = processor.outgoingGain
        val underflow = ByteArray(200)
        repeat(100) { underflow[it * 2 + 1] = 32 }
        PcmCrossfadeMixer.scaleInPlace(underflow, 200, gain, frameSize = 2,
            outputIsFloat = false, bitsPerSample = 16)
        assertEquals(8192f * gain, ((underflow[1].toInt() shl 8) or (underflow[0].toInt() and 255)).toFloat(), 2f)
        assertEquals(gain, processor.outgoingGain, .0001f)
        val next = processor.mixInPlace(ByteArray(200), ByteArray(200), 0, 200, 1000, 2, 16, false, false)
        assertEquals(1100, next.consumedFrames)
    }

    @Test fun recoveredIncomingAudioFadesBackOverOneHundredFiftyMilliseconds() {
        val recovery = CrossfadeBufferRecovery()
        recovery.markMissing(1000)
        val buf = ByteArray(100)
        repeat(50) { buf[it * 2 + 1] = 32 }
        recovery.apply(buf, 100, 2, false, false, 16)
        assertEquals(0, buf[0].toInt())
        assertEquals(0, buf[1].toInt())
        assertTrue(((buf[99].toInt() shl 8) or (buf[98].toInt() and 255)) in 2600..2800)
    }

    @Test fun recoveryDoesNotStretchWhenOneBufferExceedsTheRecoveryWindow() {
        val recovery = CrossfadeBufferRecovery()
        recovery.markMissing(1000)
        val buf = ByteArray(400)
        repeat(200) { buf[it * 2 + 1] = 32 }
        recovery.apply(buf, 400, 2, false, false, 16)
        assertEquals(8192, (buf[399].toInt() shl 8) or (buf[398].toInt() and 255))
        assertEquals(8192, (buf[299].toInt() shl 8) or (buf[298].toInt() and 255))
    }
}
