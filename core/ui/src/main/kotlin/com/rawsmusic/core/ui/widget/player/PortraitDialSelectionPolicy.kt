package com.rawsmusic.core.ui.widget.player

internal sealed interface PortraitDialExternalSyncAction {
    object None : PortraitDialExternalSyncAction
    object ConfirmPending : PortraitDialExternalSyncAction
    object IgnoreStaleEmission : PortraitDialExternalSyncAction
    data class Animate(val delta: Int) : PortraitDialExternalSyncAction
    data class Snap(val wrappedIndex: Int) : PortraitDialExternalSyncAction
}

/**
 * Resolves player -> dial synchronization without ever dispatching a transport command.
 *
 * Step88 reused the user-settle path for player emissions. That path also called setPlayQueue(),
 * so every successful song change could enqueue the same song again and pull the dial back and
 * forth. This policy makes the one-way ownership explicit: player emissions may only confirm,
 * ignore, animate or snap the visual centre.
 */
internal fun resolvePortraitDialExternalSyncAction(
    currentWrappedIndex: Int,
    resolvedPlayerIndex: Int,
    queueSize: Int,
    currentSongIdentity: String?,
    pendingSelectionIndex: Int,
    pendingSelectionIdentity: String?,
    pendingSelectionDeadlineMs: Long,
    nowMs: Long,
): PortraitDialExternalSyncAction {
    if (queueSize <= 0) return PortraitDialExternalSyncAction.None
    val resolved = resolvedPlayerIndex.coerceIn(0, queueSize - 1)
    if (pendingSelectionIndex >= 0 && pendingSelectionIdentity != null) {
        if (
            currentSongIdentity == pendingSelectionIdentity &&
            resolved == pendingSelectionIndex
        ) {
            return PortraitDialExternalSyncAction.ConfirmPending
        }
        if (nowMs < pendingSelectionDeadlineMs) {
            return PortraitDialExternalSyncAction.IgnoreStaleEmission
        }
    }
    if (resolved == currentWrappedIndex) return PortraitDialExternalSyncAction.None
    val forward = (resolved - currentWrappedIndex + queueSize) % queueSize
    val backward = (currentWrappedIndex - resolved + queueSize) % queueSize
    return when {
        forward in 1..2 -> PortraitDialExternalSyncAction.Animate(forward)
        backward in 1..2 -> PortraitDialExternalSyncAction.Animate(-backward)
        else -> PortraitDialExternalSyncAction.Snap(resolved)
    }
}
