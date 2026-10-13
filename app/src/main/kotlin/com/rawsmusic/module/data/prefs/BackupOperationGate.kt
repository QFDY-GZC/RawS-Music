package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Transfers serialize independently of the shorter Store mutation gate. Never acquire recursively. */
internal object BackupOperationGate {
    private val mutex = Mutex()
    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
