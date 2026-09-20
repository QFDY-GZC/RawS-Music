package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import java.util.concurrent.Executor

/**
 * Serializes planned-next decoder preparation away from renderer/control hot paths.
 *
 * Besides de-duplicating work, this coordinator exposes the terminal state of the
 * current request. Manual handoff must not spend its entire grace interval waiting
 * after an FFmpeg open/prime task has already failed.
 */
internal class NextDecoderPrepareCoordinator(
    private val tag: String,
    private val executor: Executor,
    private val isRequestCurrent: (Request) -> Boolean,
    private val isAlreadyPrepared: (Request) -> Boolean,
    private val prepare: (Request) -> Boolean,
    private val outcomeDetail: (Request, Boolean) -> String = { _, ok -> if (ok) "prepared" else "prepare_returned_false" },
    private val monotonicMs: () -> Long = { System.nanoTime() / 1_000_000L },
) {
    data class Request(
        val path: String,
        val generation: Int,
        val epoch: Long,
        val reason: String,
    ) {
        val key: String = "$generation:$epoch:$path"
    }

    enum class State {
        IDLE,
        QUEUED,
        RUNNING,
        SUCCEEDED,
        FAILED,
        OBSOLETE,
    }

    data class Snapshot(
        val key: String?,
        val state: State,
        val attempt: Int,
        val detail: String,
        val updatedAtMs: Long,
    ) {
        val inFlight: Boolean get() = state == State.QUEUED || state == State.RUNNING
        val terminalFailure: Boolean get() = state == State.FAILED || state == State.OBSOLETE
    }

    private val lock = Any()
    private var inFlightKey: String? = null
    private var snapshot = Snapshot(
        key = null,
        state = State.IDLE,
        attempt = 0,
        detail = "idle",
        updatedAtMs = monotonicMs(),
    )

    fun schedule(request: Request): Boolean {
        if (request.path.isBlank() || !isRequestCurrent(request)) {
            publish(request.key, State.OBSOLETE, snapshot.attempt, "request_not_current")
            return false
        }
        if (isAlreadyPrepared(request)) {
            publish(request.key, State.SUCCEEDED, snapshot.attempt, "already_prepared")
            return true
        }

        val attempt: Int
        synchronized(lock) {
            if (inFlightKey == request.key) return true
            inFlightKey = request.key
            attempt = if (snapshot.key == request.key) snapshot.attempt + 1 else 1
            snapshot = Snapshot(
                key = request.key,
                state = State.QUEUED,
                attempt = attempt,
                detail = request.reason,
                updatedAtMs = monotonicMs(),
            )
        }

        return try {
            executor.execute {
                if (!isRequestCurrent(request)) {
                    publish(request.key, State.OBSOLETE, attempt, "obsolete_before_start")
                    clearInFlight(request.key)
                    return@execute
                }
                publish(request.key, State.RUNNING, attempt, request.reason)
                try {
                    val ok = isAlreadyPrepared(request) || prepare(request)
                    val detail = outcomeDetail(request, ok)
                    publish(
                        request.key,
                        if (ok) State.SUCCEEDED else State.FAILED,
                        attempt,
                        detail,
                    )
                    AppLogger.i(
                        tag,
                        "Next decoder async prepare finished ok=$ok path=${request.path.substringAfterLast('/')} " +
                            "gen=${request.generation} epoch=${request.epoch} attempt=$attempt " +
                            "reason=${request.reason} detail=$detail",
                    )
                } catch (error: Throwable) {
                    val detail = "exception:${error.javaClass.simpleName}:${error.message.orEmpty().take(120)}"
                    publish(request.key, State.FAILED, attempt, detail)
                    AppLogger.e(
                        tag,
                        "Next decoder async prepare failed path=${request.path.substringAfterLast('/')} " +
                            "gen=${request.generation} attempt=$attempt reason=${request.reason}",
                        error,
                    )
                } finally {
                    clearInFlight(request.key)
                }
            }
            true
        } catch (error: Throwable) {
            clearInFlight(request.key)
            publish(
                request.key,
                State.FAILED,
                attempt,
                "executor_rejected:${error.javaClass.simpleName}",
            )
            AppLogger.e(tag, "Next decoder executor rejected request path=${request.path}", error)
            false
        }
    }

    fun snapshot(path: String, generation: Int, epoch: Long): Snapshot = synchronized(lock) {
        val key = "$generation:$epoch:$path"
        if (snapshot.key == key) snapshot else Snapshot(
            key = key,
            state = State.IDLE,
            attempt = 0,
            detail = "no_attempt",
            updatedAtMs = monotonicMs(),
        )
    }

    fun isInFlight(path: String, generation: Int, epoch: Long): Boolean =
        snapshot(path, generation, epoch).inFlight

    private fun publish(key: String, state: State, attempt: Int, detail: String) {
        synchronized(lock) {
            // A stale worker must not overwrite the status of a newer request.
            if (snapshot.key != null && snapshot.key != key && state != State.QUEUED) return
            snapshot = Snapshot(
                key = key,
                state = state,
                attempt = attempt,
                detail = detail,
                updatedAtMs = monotonicMs(),
            )
        }
    }

    private fun clearInFlight(key: String) {
        synchronized(lock) {
            if (inFlightKey == key) inFlightKey = null
        }
    }
}
