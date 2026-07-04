package com.rawsmusic.module.player.statemachine

import android.util.Log
import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.core.common.utils.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Serialized playback operation queue.
 *
 * Design:
 * - Single-threaded event processing via a Channel consumer
 * - put() clears the queue before offering (only latest event kept for same-type)
 * - Each event handle() is followed by a short delay (30ms)
 * - clearEvents() on pause/stop flushes pending events
 *
 * Implementation notes:
 * - Uses Kotlin Coroutines Channel instead of ArrayBlockingQueue + Thread
 * - Sealed class PlaybackEvent instead of abstract PlayerOPEvent
 * - Conflation by event-type: consecutive same-type events keep only the latest
 */
class PlaybackEventQueue(
    private val tag: String = "PlaybackEventQueue"
) {
    companion object {
        private const val TAG = "PlaybackEventQueue"
        private const val QUEUE_CAPACITY = 64
        private const val POST_HANDLE_DELAY_MS = 30L
    }

    /**
     * Sealed event hierarchy. Each event knows how to handle itself via
     * a suspend lambda provided at construction time.
     */
    sealed class PlaybackEvent {
        abstract val eventType: String

        data class PlayEvent(
            val song: AudioFile,
            val queue: List<AudioFile>,
            val index: Int,
            val handler: suspend (AudioFile, List<AudioFile>, Int) -> Unit
        ) : PlaybackEvent() {
            override val eventType = "PLAY"
        }

        data class SeekEvent(
            val positionMs: Long,
            val songPath: String,
            val handler: suspend (Long, String) -> Unit
        ) : PlaybackEvent() {
            override val eventType = "SEEK"
        }

        data class PauseEvent(
            val handler: suspend () -> Unit
        ) : PlaybackEvent() {
            override val eventType = "PAUSE"
        }

        data class ResumeEvent(
            val handler: suspend () -> Unit
        ) : PlaybackEvent() {
            override val eventType = "RESUME"
        }

        data class StopEvent(
            val handler: suspend () -> Unit
        ) : PlaybackEvent() {
            override val eventType = "STOP"
        }

        /**
         * Generic event for custom operations (e.g. render switch, settings change).
         * These are NOT conflated — each runs exactly once.
         */
        data class GenericEvent(
            override val eventType: String,
            val handler: suspend () -> Unit
        ) : PlaybackEvent()
    }

    private val queue = Channel<PlaybackEvent>(capacity = QUEUE_CAPACITY)
    private val running = AtomicBoolean(false)
    private var consumerJob: Job? = null

    /** Current event being processed, for observability. */
    private val _currentEvent = MutableStateFlow<PlaybackEvent?>(null)
    val currentEvent: StateFlow<PlaybackEvent?> = _currentEvent

    /** Pending events count (approximate). */
    private val _pendingCount = MutableStateFlow(0)
    val pendingCount: StateFlow<Int> = _pendingCount

    /**
     * Start the queue consumer. Must be called before submit().
     * Uses the provided scope for the consumer coroutine.
     */
    fun start(scope: CoroutineScope) {
        if (running.get()) return
        running.set(true)
        consumerJob = scope.launch(Dispatchers.Default) {
            Log.i(TAG, "Event queue consumer started")
            for (event in queue) {
                if (!running.get()) break
                _currentEvent.value = event
                try {
                    handleEvent(event)
                } catch (t: Throwable) {
                    AppLogger.e(TAG, "Event ${event.eventType} failed", t)
                }
                _currentEvent.value = null
                _pendingCount.value = /* approximate */ maxOf(0, _pendingCount.value - 1)
                if (running.get()) {
                    delay(POST_HANDLE_DELAY_MS)
                }
            }
            Log.i(TAG, "Event queue consumer stopped")
        }
    }

    /**
     * Submit an event to the queue.
     *
     * Conflation logic (put() clear+offer):
     * - For PLAY events: cancels any pending PLAY events, keeps only this one
     * - For SEEK events: cancels any pending SEEK events, keeps only this one
     * - For PAUSE/STOP events: clears ALL pending events
     * - For GenericEvent: always queued as-is
     */
    fun submit(event: PlaybackEvent) {
        if (!running.get()) {
            Log.w(TAG, "submit(${event.eventType}) ignored: queue not running")
            return
        }

        when (event) {
            is PlaybackEvent.PauseEvent, is PlaybackEvent.StopEvent -> {
                // Clear all pending events on pause/stop
                clearPendingInternal()
            }
            is PlaybackEvent.PlayEvent, is PlaybackEvent.SeekEvent, is PlaybackEvent.ResumeEvent -> {
                // Conflate: remove same-type pending events
                conflateSameType(event.eventType)
            }
            is PlaybackEvent.GenericEvent -> {
                // No conflation
            }
        }

        val result = queue.trySend(event)
        if (result.isSuccess) {
            _pendingCount.value = _pendingCount.value + 1
            Log.d(TAG, "submit(${event.eventType}) queued")
        } else {
            Log.w(TAG, "submit(${event.eventType}) rejected: ${result.exceptionOrNull()?.message}")
        }
    }

    /**
     * Clear all pending events.
     */
    fun clearPending() {
        clearPendingInternal()
        Log.i(TAG, "clearPending() called")
    }

    /**
     * Stop the queue and discard all pending events.
     */
    fun stop() {
        running.set(false)
        clearPendingInternal()
        consumerJob?.cancel()
        consumerJob = null
        _currentEvent.value = null
        _pendingCount.value = 0
        Log.i(TAG, "Event queue stopped")
    }

    /** True if the consumer is running. */
    val isRunning: Boolean
        get() = running.get()

    private suspend fun handleEvent(event: PlaybackEvent) {
        when (event) {
            is PlaybackEvent.PlayEvent -> {
                Log.d(TAG, "Handling PLAY: ${event.song.title}")
                event.handler(event.song, event.queue, event.index)
            }
            is PlaybackEvent.SeekEvent -> {
                Log.d(TAG, "Handling SEEK: ${event.positionMs}ms")
                event.handler(event.positionMs, event.songPath)
            }
            is PlaybackEvent.PauseEvent -> {
                Log.d(TAG, "Handling PAUSE")
                event.handler()
            }
            is PlaybackEvent.ResumeEvent -> {
                Log.d(TAG, "Handling RESUME")
                event.handler()
            }
            is PlaybackEvent.StopEvent -> {
                Log.d(TAG, "Handling STOP")
                event.handler()
            }
            is PlaybackEvent.GenericEvent -> {
                Log.d(TAG, "Handling ${event.eventType}")
                event.handler()
            }
        }
    }

    /**
     * Internal: drain the channel without blocking.
     * Since Kotlin Channel doesn't have a direct clear(), we use tryReceive in a loop.
     */
    private fun clearPendingInternal() {
        var cleared = 0
        while (true) {
            val result = queue.tryReceive()
            if (result.isSuccess) {
                cleared++
            } else {
                break
            }
        }
        if (cleared > 0) {
            _pendingCount.value = 0
            Log.d(TAG, "clearPendingInternal: drained $cleared events")
        }
    }

    /**
     * Internal: remove pending events of the same type, keeping only the latest.
     * Since Channel is FIFO and doesn't support filtered removal, we drain and
     * re-queue non-matching events.
     */
    private fun conflateSameType(eventType: String) {
        val kept = mutableListOf<PlaybackEvent>()
        while (true) {
            val result = queue.tryReceive()
            if (result.isSuccess) {
                val e = result.getOrThrow()
                if (e.eventType != eventType) {
                    kept.add(e)
                }
            } else {
                break
            }
        }
        // Re-queue non-matching events
        for (e in kept) {
            queue.trySend(e)
        }
        if (kept.isNotEmpty()) {
            Log.d(TAG, "conflateSameType($eventType): kept ${kept.size} other events")
        }
    }
}
