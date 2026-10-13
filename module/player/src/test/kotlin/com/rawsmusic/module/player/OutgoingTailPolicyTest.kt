package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class OutgoingTailPolicyTest {
    @Test fun sharpDropFadesBeforeQuietAndCutsNearItsEntrance() {
        val plan = OutgoingTailPolicy.select(List(50) { -15f } + List(50) { -29f }, 200_000, 100, 200_000)!!
        assertTrue(plan.fadeStartMs < 205_000)
        assertTrue(plan.cutoffMs in 204_500..206_000)
    }
    @Test fun smallPersistentDescentDoesNotRequireAnEightDbDrop() {
        val levels = List(30) { -22f } + List(60) { -22f - it * .08f } + List(30) { -27f }
        assertNotNull(OutgoingTailPolicy.select(levels, 200_000, 100, 200_000))
    }
    @Test fun temporaryDipAndFlatLoudTailArePreserved() {
        assertNull(OutgoingTailPolicy.select(List(40) { -14f } + List(8) { -35f } + List(40) { -14f }, 0, 100, 0))
        assertNull(OutgoingTailPolicy.select(List(100) { -15f }, 0, 100, 0))
    }
    @Test fun finalVocalCannotBeCutByAnEarlierEnergyDip() {
        val plan = OutgoingTailPolicy.select(List(50) { -15f } + List(50) { -29f }, 200_000, 100, 207_000)
        assertTrue(plan == null || plan.fadeStartMs >= 207_000)
    }
}
