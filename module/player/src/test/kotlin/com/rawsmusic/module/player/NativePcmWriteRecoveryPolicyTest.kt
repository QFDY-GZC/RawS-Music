package com.rawsmusic.module.player

import org.junit.Assert.assertEquals
import org.junit.Test

class NativePcmWriteRecoveryPolicyTest {
    @Test
    fun `negative write retargets before rebuilding`() {
        assertEquals(
            NativePcmWriteRecoveryStep.Retarget,
            nextNativePcmWriteRecoveryStep(-1, acceptedBytes = 0, retargetAttempted = false, rebuildAttempted = false),
        )
        assertEquals(
            NativePcmWriteRecoveryStep.Rebuild,
            nextNativePcmWriteRecoveryStep(-1, acceptedBytes = 0, retargetAttempted = true, rebuildAttempted = false),
        )
    }

    @Test
    fun `replacement engine is forbidden after a partial block was accepted`() {
        assertEquals(
            NativePcmWriteRecoveryStep.Fail,
            nextNativePcmWriteRecoveryStep(-1, acceptedBytes = 4096, retargetAttempted = true, rebuildAttempted = false),
        )
    }

    @Test
    fun `recovery attempts are bounded`() {
        assertEquals(
            NativePcmWriteRecoveryStep.Fail,
            nextNativePcmWriteRecoveryStep(-1, acceptedBytes = 0, retargetAttempted = true, rebuildAttempted = true),
        )
        assertEquals(
            NativePcmWriteRecoveryStep.Fail,
            nextNativePcmWriteRecoveryStep(0, acceptedBytes = 0, retargetAttempted = false, rebuildAttempted = false),
        )
    }
}
