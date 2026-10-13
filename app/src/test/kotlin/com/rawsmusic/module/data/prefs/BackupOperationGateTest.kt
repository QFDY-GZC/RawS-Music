package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class BackupOperationGateTest {
    @Test fun cancelledOperationReleasesOwnershipAndConcurrentTransfersWait() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val first = launch { BackupOperationGate.withExclusive { order += "first"; entered.complete(Unit); awaitCancellation() } }
        entered.await()
        val second = launch { BackupOperationGate.withExclusive { order += "second" } }
        yield()
        assertEquals(listOf("first"), order)
        first.cancelAndJoin()
        second.join()
        assertEquals(listOf("first", "second"), order)
    }
}
