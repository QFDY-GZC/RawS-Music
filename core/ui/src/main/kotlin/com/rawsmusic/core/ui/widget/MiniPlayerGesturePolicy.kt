package com.rawsmusic.core.ui.widget

/**
 * A captured horizontal switch owns the complete pointer transaction. Child click targets remain
 * blocked through a short post-release window so the drag UP cannot also trigger a transport or
 * secondary action callback.
 */
internal fun miniPlayerChildControlAllowed(
    pointerCaptured: Boolean,
    controlsBlockedUntilUptimeMs: Long,
    nowUptimeMs: Long,
): Boolean = !pointerCaptured && nowUptimeMs >= controlsBlockedUntilUptimeMs
