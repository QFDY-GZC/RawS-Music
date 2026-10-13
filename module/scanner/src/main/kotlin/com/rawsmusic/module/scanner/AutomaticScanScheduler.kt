package com.rawsmusic.module.scanner

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.atomic.AtomicBoolean

/** Owns all scan requests on one mailbox, including deferred requests and cancellation. */
class AutomaticScanScheduler(
    private val scope: CoroutineScope,
    private val automaticEnabled: () -> Boolean,
    private val nowMs: () -> Long,
    private val uptimeMs: () -> Long,
    private val immediate: () -> Boolean,
    private val startupToken: AtomicBoolean,
    private val wait: suspend (Long) -> Unit = { delay(it) },
    private val scan: suspend (Request) -> Unit,
    private val externallyScanning: () -> Boolean = { false },
) {
    data class Request(val reason: String, val automatic: Boolean)
    private sealed interface Command {
        data class Submit(val request: Request, val delayMs: Long = 0) : Command
        data class Ready(val request: Request, val generation: Long) : Command
        data class Finished(val generation: Long) : Command
        data object Cancel : Command
    }
    private val commands = Channel<Command>(Channel.UNLIMITED)
    private val policy = AutomaticScanPolicy()
    private var timer: Job? = null
    private var scheduledRequest: Request? = null
    private var running: Job? = null
    private var pending: Request? = null
    private var generation = 0L
    private var runSequence = 0L
    private var activeRun = 0L
    private val owner = scope.launch {
        for (command in commands) when (command) {
            is Command.Submit -> {
                if (command.request.automatic && !automaticEnabled()) continue
                if (command.request.automatic && scheduledRequest?.automatic == false) continue
                if (command.request.automatic && (running != null || externallyScanning())) continue
                timer?.cancel()
                timer = null
                scheduledRequest = null
                generation++
                if (!command.request.automatic && running != null) {
                    pending = command.request
                } else {
                    val token = generation
                    scheduledRequest = command.request
                    val delayMs = if (command.delayMs < 0L) {
                        policy.systemChangeDelayMs(nowMs(), uptimeMs(), immediate())
                    } else command.delayMs
                    timer = scope.launch {
                        wait(delayMs)
                        commands.trySend(Command.Ready(command.request, token))
                    }
                }
            }
            is Command.Ready -> {
                if (command.generation != generation) continue
                timer = null
                scheduledRequest = null
                if (command.request.automatic &&
                    (!automaticEnabled() || !policy.canDispatch(nowMs(), running != null || externallyScanning(), 0))) continue
                start(command.request)
            }
            is Command.Finished -> {
                if (command.generation != activeRun) continue
                running = null
                val next = pending
                pending = null
                if (next != null) start(next)
            }
            Command.Cancel -> {
                generation++
                timer?.cancelAndJoin()
                timer = null
                scheduledRequest = null
                pending = null
                activeRun = 0L
                running?.cancelAndJoin()
                running = null
            }
        }
    }
    private fun start(request: Request) {
        val token = ++runSequence
        activeRun = token
        running = scope.launch {
            try { scan(request) }
            finally { if (currentCoroutineContext().isActive) commands.trySend(Command.Finished(token)) }
        }
    }
    fun startup(reason: String, enabled: Boolean) {
        if (startupToken.compareAndSet(false, true) && enabled && automaticEnabled()) {
            commands.trySend(Command.Submit(Request(reason, true), policy.startupDelayMs(uptimeMs())))
        }
    }
    fun systemChanged(reason: String) {
        commands.trySend(Command.Submit(Request(reason, true), -1L))
    }
    fun manual(reason: String) { commands.trySend(Command.Submit(Request(reason, false))) }
    fun mounted(reason: String) {
        commands.trySend(Command.Submit(Request(reason, true), if (uptimeMs() > 60000L) 0L else 10000L))
    }
    fun cancel() { commands.trySend(Command.Cancel) }
    fun destroy() {
        owner.cancel()
        timer?.cancel()
        running?.cancel()
        commands.close()
    }
}
