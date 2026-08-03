package com.rawsmusic.module.player.usb

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Single owner for destructive USB transport commands.
 *
 * Android callbacks, playback workers and UI commands may request a lifecycle
 * transition, but only this thread is allowed to execute init/start/pause/stop,
 * interface reconfiguration, detach and close. Re-entrant calls from a command
 * execute inline so a prepare operation may safely invoke another transport
 * helper without deadlocking itself.
 */
internal class UsbTransportCommandQueue(
    private val tag: String = "UsbTransportOwner"
) {
    private val commands = LinkedBlockingQueue<Runnable>()
    private val sequence = AtomicLong(0L)

    @Volatile
    private var ownerThread: Thread? = null

    init {
        thread(
            name = "RawS USB Transport",
            isDaemon = true,
            priority = Thread.NORM_PRIORITY + 1
        ) {
            ownerThread = Thread.currentThread()
            while (true) {
                val command = commands.take()
                try {
                    command.run()
                } catch (t: Throwable) {
                    AppLogger.e(tag, "uncaught USB transport command failure", t)
                }
            }
        }
    }

    fun isOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    fun <T> call(name: String, block: () -> T): T {
        if (isOwnerThread()) return block()

        val id = sequence.incrementAndGet()
        val submittedAt = System.nanoTime()
        val task = FutureTask {
            val startedAt = System.nanoTime()
            AppLogger.d(tag, "command#$id START name=$name queuedMs=${(startedAt - submittedAt) / 1_000_000}")
            try {
                block()
            } finally {
                AppLogger.d(tag, "command#$id END name=$name runMs=${(System.nanoTime() - startedAt) / 1_000_000}")
            }
        }
        commands.put(task)

        var interrupted = false
        try {
            while (true) {
                try {
                    return task.get()
                } catch (_: InterruptedException) {
                    // A caller being interrupted must not abandon a lifecycle
                    // command and start a competing reopen. Wait for the single
                    // owner to finish, then restore the interrupt status.
                    interrupted = true
                } catch (e: ExecutionException) {
                    val cause = e.cause ?: e
                    when (cause) {
                        is RuntimeException -> throw cause
                        is Error -> throw cause
                        else -> throw IllegalStateException("USB transport command failed: $name", cause)
                    }
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt()
        }
    }

    fun post(name: String, block: () -> Unit) {
        if (isOwnerThread()) {
            block()
            return
        }
        val id = sequence.incrementAndGet()
        commands.put(Runnable {
            val startedAt = System.nanoTime()
            AppLogger.d(tag, "command#$id START async name=$name")
            try {
                block()
            } catch (t: Throwable) {
                AppLogger.e(tag, "command#$id FAILED async name=$name", t)
            } finally {
                AppLogger.d(tag, "command#$id END async name=$name runMs=${(System.nanoTime() - startedAt) / 1_000_000}")
            }
        })
    }
}
