package com.rawsmusic.core.ui.widget.bitmaps

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ArtworkAnalysisRecoveryTest {
    @Test
    fun analysisWaitsForBusySourceThenReturnsPixels() = runBlocking {
        withBusySource("analysis-ready") { release ->
            val analysis = async(start = CoroutineStart.UNDISPATCHED) {
                AudioArtworkDecodeCoordinator.retryAnalysisAfterSourceSelection {
                    probe("analysis-ready")
                }
            }
            assertFalse("Busy source must not finish as missing artwork", analysis.isCompleted)
            release.countDown()
            assertEquals("pixels", withTimeout(2000) { analysis.await() })
        }
    }

    @Test
    fun cancellingAnalysisDoesNotCancelSourceOwner() = runBlocking {
        withBusySource("analysis-cancel") { release ->
            val analysis = async(start = CoroutineStart.UNDISPATCHED) {
                AudioArtworkDecodeCoordinator.retryAnalysisAfterSourceSelection {
                    probe("analysis-cancel")
                }
            }
            assertFalse(analysis.isCompleted)
            analysis.cancelAndJoin()
            release.countDown()
            assertTrue(analysis.isCancelled)
        }
        assertEquals("pixels", probe("analysis-cancel"))
    }

    @Test
    fun realMissWithoutBusySourceDoesNotLoop() = runBlocking {
        var attempts = 0
        val result = AudioArtworkDecodeCoordinator.retryAnalysisAfterSourceSelection<String> {
            attempts++
            null
        }
        assertNull(result)
        assertEquals(1, attempts)
    }

    private fun probe(key: String): String? =
        (AudioArtworkDecodeCoordinator.decode(
            providerKey = key,
            decodeEmbedded = { "pixels" },
            decodeFolder = { null },
        ) as? AudioArtworkDecodeCoordinator.DecodeResult.Found)?.value

    private suspend fun withBusySource(key: String, block: suspend (CountDownLatch) -> Unit) {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val owner = Thread {
            AudioArtworkDecodeCoordinator.decode(
                providerKey = key,
                decodeEmbedded = {
                    entered.countDown()
                    check(release.await(5, TimeUnit.SECONDS))
                    "pixels"
                },
                decodeFolder = { null },
            )
        }
        owner.start()
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            block(release)
        } finally {
            release.countDown()
            owner.join(2000)
            assertFalse(owner.isAlive)
        }
    }
}
