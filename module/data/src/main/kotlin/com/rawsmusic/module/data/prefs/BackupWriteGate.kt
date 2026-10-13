package com.rawsmusic.module.data.prefs

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Shared persistence boundary; callers already holding it must use their unlocked store paths. */
object BackupWriteGate {
    private val mutex = Mutex()
    suspend fun <T> withExclusive(block: suspend () -> T): T = mutex.withLock { block() }
}
