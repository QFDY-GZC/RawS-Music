package com.rawsmusic.module.player.control

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Owns the small state machine used to decide whether USB playback must remain protected while the
 * app is backgrounded. PlayerController still performs service, wakelock and engine operations; the
 * repeated state predicates, delayed idle release and log throttling live here.
 */
internal class UsbBackgroundPlaybackCoordinator(
    private val clockMs: () -> Long,
    private val scope: CoroutineScope? = null,
    private val snapshotProvider: (() -> Snapshot)? = null,
    private val releaseIdleResources: ((String) -> Unit)? = null,
) {
    data class Snapshot(
        val released: Boolean,
        val exclusiveActive: Boolean,
        val engineExclusiveMode: Boolean,
        val appInBackground: Boolean,
        val controllerPlaying: Boolean,
        val controllerPreparing: Boolean,
        val backendPlaying: Boolean,
        val backendPreparing: Boolean,
    )

    private var lastReinforceLogMs: Long = 0L
    private var idleReleaseJob: Job? = null

    fun shouldSustain(snapshot: Snapshot): Boolean {
        if (
            snapshot.released ||
            !snapshot.exclusiveActive ||
            !snapshot.engineExclusiveMode ||
            !snapshot.appInBackground
        ) {
            return false
        }
        return snapshot.controllerPlaying ||
            snapshot.controllerPreparing ||
            snapshot.backendPlaying ||
            snapshot.backendPreparing
    }

    fun shouldSustain(): Boolean = snapshotProvider?.invoke()?.let(::shouldSustain) ?: false

    fun shouldReleaseIdle(snapshot: Snapshot): Boolean {
        if (snapshot.released || !snapshot.exclusiveActive || !snapshot.appInBackground) return false
        return !snapshot.controllerPlaying &&
            !snapshot.controllerPreparing &&
            !snapshot.backendPlaying &&
            !snapshot.backendPreparing
    }

    fun scheduleIdleRelease(reason: String, delayMs: Long = DEFAULT_IDLE_RELEASE_DELAY_MS) {
        val ownerScope = scope ?: return
        val provider = snapshotProvider ?: return
        val release = releaseIdleResources ?: return
        if (!shouldReleaseIdle(provider())) return
        idleReleaseJob?.cancel()
        idleReleaseJob = ownerScope.launch(Dispatchers.Default) {
            delay(delayMs.coerceAtLeast(0L))
            if (shouldReleaseIdle(provider())) release(reason)
        }
    }

    fun cancelIdleRelease() {
        idleReleaseJob?.cancel()
        idleReleaseJob = null
    }

    fun shouldLogReinforce(minIntervalMs: Long = DEFAULT_LOG_INTERVAL_MS): Boolean {
        val now = clockMs()
        if (now - lastReinforceLogMs < minIntervalMs.coerceAtLeast(0L)) return false
        lastReinforceLogMs = now
        return true
    }

    fun reset() {
        cancelIdleRelease()
        lastReinforceLogMs = 0L
    }

    companion object {
        const val DEFAULT_IDLE_RELEASE_DELAY_MS = 1_200L
        const val DEFAULT_LOG_INTERVAL_MS = 10_000L
    }
}
