package com.rawsmusic.module.player

import org.junit.Assert.*
import org.junit.Test

class IncomingEntryPolicyTest {
    @Test fun sustainedSilenceIsSkippedWithAttackMargin() {
        assertEquals(2800L, IncomingEntryPolicy.select(List(30) { -80f } + List(20) { -18f }, 100))
    }
    @Test fun instrumentalAndQuietMasterArePreserved() {
        assertEquals(0L, IncomingEntryPolicy.select(List(300) { -39f }, 100))
        assertEquals(0L, IncomingEntryPolicy.select(List(100) { -43f } + List(200) { -18f }, 100))
    }
    @Test fun lyricBackedWeakIntroUsesSustainedOnsetAndKeepsPreroll() {
        assertEquals(20000L, IncomingEntryPolicy.select(List(100) { -43f } + List(200) { -18f }, 100, 24000))
    }
    @Test fun noReliableOnsetNeverSkipsWholeIntro() {
        assertEquals(0L, IncomingEntryPolicy.select(List(300) { -80f }, 100))
        assertEquals(0L, IncomingEntryPolicy.select(List(100) { -80f } + listOf(-10f) + List(199) { -80f }, 100))
    }
    @Test fun neverCutsFirstVocalOrBeyondSkipLimit() {
        assertEquals(0L, IncomingEntryPolicy.select(List(100) { -80f } + List(200) { -18f }, 100, 1000))
        assertTrue(IncomingEntryPolicy.select(List(200) { -80f } + List(100) { -18f }, 100) <= 15000)
    }
    @Test fun shortIntroCanEnterAtFirstRealVocalWithAttackMargin() {
        assertEquals(2800L, IncomingEntryPolicy.select(List(100) { -18f }, 100, 3000))
    }
}
