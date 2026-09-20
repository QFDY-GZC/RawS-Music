package com.rawsmusic.separation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AiRealtimeResetPolicyTest {
    @Test
    fun resetRearmsOnlyWhenModelIsNotAlreadyOpening() {
        assertTrue(AiRealtimeResetPolicy.shouldRearm(true, false, false, false, false))
        assertFalse(AiRealtimeResetPolicy.shouldRearm(true, false, false, false, true))
    }

    @Test
    fun readyOrDirectPathsDoNotReopenModel() {
        assertFalse(AiRealtimeResetPolicy.shouldRearm(true, true, false, false, false))
        assertFalse(AiRealtimeResetPolicy.shouldRearm(true, false, true, false, false))
        assertFalse(AiRealtimeResetPolicy.shouldRearm(true, false, false, true, false))
    }

    @Test
    fun disabledIntentNeverRearms() {
        assertFalse(AiRealtimeResetPolicy.shouldRearm(false, false, false, false, false))
    }
}
