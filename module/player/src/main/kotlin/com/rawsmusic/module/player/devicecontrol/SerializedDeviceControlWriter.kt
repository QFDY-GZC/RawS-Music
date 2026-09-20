package com.rawsmusic.module.player.devicecontrol

import java.io.Closeable
import java.util.LinkedHashMap
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * One serialized control-plane writer with per-control conflation.
 *
 * Dragging one slider may enqueue many values. Only the newest value for that control is sent,
 * while commands for different controls keep their relative order. All callers that were folded
 * into the same write receive the final result/readback of the newest value.
 */
internal class SerializedDeviceControlWriter(
    scope: CoroutineScope,
    private val generation: Long,
    private val currentGeneration: () -> Long,
    private val canExecute: () -> Boolean = { true },
    private val coalesceMs: Long = 24L,
    private val execute: suspend (DeviceControlWriteRequest) -> DeviceControlWriteResult,
) : Closeable {
    private data class Command(
        val request: DeviceControlWriteRequest,
        val result: CompletableDeferred<DeviceControlWriteResult>,
    )

    private data class Batch(
        var request: DeviceControlWriteRequest,
        val waiters: MutableList<CompletableDeferred<DeviceControlWriteResult>>,
    )

    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val outstanding = ConcurrentHashMap.newKeySet<CompletableDeferred<DeviceControlWriteResult>>()
    private val job: Job = scope.launch(Dispatchers.IO) {
        try {
        for (first in commands) {
            val batches = LinkedHashMap<DeviceControlId, Batch>()
            fun merge(command: Command) {
                val existing = batches[command.request.controlId]
                if (existing == null) {
                    batches[command.request.controlId] = Batch(command.request, mutableListOf(command.result))
                } else {
                    existing.request = command.request
                    existing.waiters += command.result
                }
            }
            merge(first)
            if (coalesceMs > 0L) delay(coalesceMs)
            while (true) {
                val next = commands.tryReceive().getOrNull() ?: break
                merge(next)
            }

            for (batch in batches.values) {
                if (!isActive) break
                val result = when {
                    currentGeneration() != generation -> DeviceControlWriteResult.Rejected("stale_generation")
                    !canExecute() -> DeviceControlWriteResult.Rejected("control_session_not_ready")
                    else -> runCatching { execute(batch.request) }
                        .getOrElse { DeviceControlWriteResult.Failed("control_write_exception", it) }
                }
                batch.waiters.forEach { waiter ->
                    if (!waiter.isCompleted) waiter.complete(result)
                    outstanding.remove(waiter)
                }
            }
        }
        } finally {
            val rejected = DeviceControlWriteResult.Rejected("control_writer_closed")
            outstanding.toList().forEach { waiter ->
                if (!waiter.isCompleted) waiter.complete(rejected)
                outstanding.remove(waiter)
            }
        }
    }

    suspend fun submit(request: DeviceControlWriteRequest): DeviceControlWriteResult {
        val result = CompletableDeferred<DeviceControlWriteResult>()
        outstanding += result
        if (!commands.trySend(Command(request, result)).isSuccess) {
            outstanding.remove(result)
            return DeviceControlWriteResult.Rejected("control_writer_closed")
        }
        return result.await().also { outstanding.remove(result) }
    }

    override fun close() {
        commands.close()
        job.cancel()
    }
}
