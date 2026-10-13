package com.rawsmusic.module.player

import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/** One in-progress write and at most one pending snapshot; final flush invalidates old work. */
internal class LatestSnapshotWriter<T>(scope: CoroutineScope, private val write: (T) -> Unit) {
    private data class Request<T>(val generation: Long, val value: T)
    private val lock = Any()
    private val generation = AtomicLong()
    private val pending = Channel<Request<T>>(Channel.CONFLATED)
    init {
        scope.launch(Dispatchers.IO) {
            for (request in pending) {
                synchronized(lock) {
                    if (request.generation == generation.get()) write(request.value)
                }
            }
        }.invokeOnCompletion { pending.cancel() }
    }
    @Synchronized fun submit(value: T?) {
        val epoch = generation.incrementAndGet()
        if (value != null) pending.trySend(Request(epoch, value))
    }
    fun <R> flushFinal(block: () -> R): R {
        synchronized(this) { generation.incrementAndGet() }
        return synchronized(lock) { block() }
    }
}
