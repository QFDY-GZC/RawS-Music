package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class AutomaticCrossfadeTrackingTest {
    private fun pcm(amplitude: Int) = ByteArray(200).also { bytes ->
        repeat(100) { i -> bytes[i * 2] = amplitude.toByte(); bytes[i * 2 + 1] = (amplitude shr 8).toByte() }
    }

    @Test fun `loud entrance after quiet intro delays ownership without stepping gains`() {
        val mixer = KotlinAutomaticCrossfadeProcessor()
        mixer.start(AutoTransitionStartPlan(0, 8_000, 4_400, AutoTransitionPolicy.Source.ENVELOPE), 1_000)
        var previous = 0f
        var midpoint = 0f
        repeat(80) { block ->
            val incoming = if (block < 10) 512 else 24_576
            val current = pcm(8_192)
            val result = mixer.mixInPlace(current, pcm(incoming), 0, 200, 1_000, 2, 16, false, false)
            val firstSample = ((current[0].toInt() and 255) or (current[1].toInt() shl 8)).toShort().toInt()
            assertEquals("block boundary must retain actual previous gain", 8_192f * (1f - previous) + incoming * previous,
                firstSample.toFloat(), 2f)
            val lastSample = ((current[198].toInt() and 255) or (current[199].toInt() shl 8)).toShort().toInt()
            val ownership = (lastSample - 8_192f) / (incoming - 8_192f)
            assertTrue("incoming must not retreat", ownership + 0.001f >= previous)
            assertTrue("no abrupt gain step", ownership - previous < 0.04f)
            previous = ownership
            if (block == 39) midpoint = ownership
            if (block == 79) { assertTrue(result.completed); assertEquals(1f, ownership, 0.001f) }
        }
        assertTrue("loud follow must not own the mix at halfway", midpoint < 0.5f)
    }

    @Test fun `nonzero PCM offset follows the same gain trajectory`() {
        val first = KotlinAutomaticCrossfadeProcessor()
        val second = KotlinAutomaticCrossfadeProcessor()
        val plan = AutoTransitionStartPlan(0, 2_000, 1_100, AutoTransitionPolicy.Source.ENVELOPE)
        first.start(plan, 1_000); second.start(plan, 1_000)
        repeat(20) {
            val plain = pcm(8_192)
            val padded = ByteArray(240).also { bytes -> plain.copyInto(bytes, 20) }
            val follow = pcm(20_000)
            val paddedFollow = ByteArray(240).also { bytes -> follow.copyInto(bytes, 20) }
            first.mixInPlace(plain, follow, 0, 200, 1_000, 2, 16, false, false)
            second.mixInPlace(padded, paddedFollow, 20, 200, 1_000, 2, 16, false, false)
            assertArrayEquals(plain, padded.copyOfRange(20, 220))
        }
    }
}
