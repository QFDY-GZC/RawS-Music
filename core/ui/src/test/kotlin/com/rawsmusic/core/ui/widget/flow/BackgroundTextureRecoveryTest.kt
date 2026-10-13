package com.rawsmusic.core.ui.widget.flow

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import kotlinx.coroutines.awaitCancellation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackgroundTextureRecoveryTest {
    @Test fun transientMissRecoversWithoutRestart() = runBlocking {
        var attempts = 0
        val result = recoverBackgroundTexture(pause = {}) {
            attempts++
            if (attempts < 3) null else "texture"
        }
        assertEquals("texture", result)
        assertEquals(3, attempts)
    }

    @Test fun readyTextureDoesNotTriggerExtraDecodes() = runBlocking {
        var attempts = 0
        assertEquals("ready", recoverBackgroundTexture(pause = {}) { attempts++; "ready" })
        assertEquals(1, attempts)
    }

    @Test fun permanentMissTerminates() = runBlocking {
        var attempts = 0
        assertNull(recoverBackgroundTexture<String>(pause = {}) { attempts++; null })
        org.junit.Assert.assertTrue(attempts in 2..6)
    }

    @Test fun leavingSceneCancelsPendingRetry() = runBlocking {
        var attempts = 0
        val job = launch(start = kotlinx.coroutines.CoroutineStart.UNDISPATCHED) {
            recoverBackgroundTexture<String>(pause = { awaitCancellation() }) {
                attempts++
                null
            }
        }
        job.cancel()
        job.join()
        assertEquals(1, attempts)
    }
}
