package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class GentleIncomingLevelTest {
    @Test fun entryStaysInTheBackgroundAndRampsOverTwoSeconds() {
        val config = AutomaticCrossfadeEnvelope.Config(21000, 1000, gentleFrames = 6000)
        val one = AutomaticCrossfadeEnvelope.gains(1000, config, 1f, 0f)
        val two = AutomaticCrossfadeEnvelope.gains(2000, config, 1f, 0f)
        assertTrue(one.second <= .0251f)
        assertTrue(two.second <= .0501f)
        assertTrue(two.first >= .9499f)
    }

    @Test fun loudDirectVocalGetsLessGainThanAnEqualLevelTrack() {
        fun entry(old: Int, next: Int): Float {
            fun pcm(amplitude: Int) = ByteArray(200).also { bytes ->
                repeat(100) { i -> bytes[i * 2] = amplitude.toByte(); bytes[i * 2 + 1] = (amplitude shr 8).toByte() }
            }
            val mixer = KotlinAutomaticCrossfadeProcessor()
            mixer.start(AutoTransitionStartPlan(6000, 15000, 8250,
                AutoTransitionPolicy.Source.LYRICS), 1000)
            repeat(30) { mixer.mixInPlace(pcm(old), pcm(next), 0, 200, 1000, 2, 16, false, false) }
            return mixer.incomingGain
        }
        val same = entry(8192, 8192)
        val loud = entry(1638, 16384)
        assertTrue(same <= .0501f)
        assertTrue(loud <= .0151f)
        assertTrue(loud < same)
    }

    @Test fun loweringTheEntryDoesNotIntroduceAnAbruptStepAtMainHandover() {
        for (entry in listOf(.015f, .05f)) {
            val config = AutomaticCrossfadeEnvelope.Config(7000, 1000,
                gentleFrames = 1000, gentleIncomingGain = entry)
            var previous = AutomaticCrossfadeEnvelope.gains(1000, config, 1f, 0f)
            for (frame in 1020..7000 step 20) {
                val next = AutomaticCrossfadeEnvelope.gains(frame.toLong(), config, 1f, 0f)
                assertTrue(previous.first - next.first in 0f.. .009f)
                assertTrue(next.second - previous.second in 0f.. .009f)
                assertTrue(next.first + next.second <= 1.0001f)
                previous = next
            }
            assertEquals(0f, previous.first, .0001f)
            assertEquals(1f, previous.second, .0001f)
        }
    }
}
