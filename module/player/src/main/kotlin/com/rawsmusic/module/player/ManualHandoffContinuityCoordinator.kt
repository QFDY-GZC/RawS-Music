package com.rawsmusic.module.player

/** Keeps an audible two-slot handoff owned by the renderer while newer commands are conflated. */
internal class ManualHandoffContinuityCoordinator(
    private val readiness: ManualCrossfadeReadinessCoordinator = ManualCrossfadeReadinessCoordinator(),
) {
    suspend fun await(
        timeoutMs: Long,
        isCurrent: () -> Boolean,
        mixing: () -> Boolean,
        ownershipPending: () -> Boolean,
    ): ManualCrossfadeReadinessCoordinator.Outcome<Unit> = readiness.await(
        timeoutMs = timeoutMs,
        isRequestCurrent = isCurrent,
        snapshot = { if (isCurrent() && !mixing() && !ownershipPending()) Unit else null },
    )
}
