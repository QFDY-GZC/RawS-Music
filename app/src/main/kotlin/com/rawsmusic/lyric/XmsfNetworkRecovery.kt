package com.rawsmusic.lyric

/** Persist intent before denying networking; only a successful restore clears recovery state. */
internal object XmsfNetworkRecovery {
    suspend fun apply(
        enabled: Boolean,
        markBlocked: (Boolean) -> Boolean,
        applyRule: suspend () -> Boolean,
    ): Boolean {
        if (!enabled && !markBlocked(true)) return false
        val applied = applyRule()
        return applied && (!enabled || markBlocked(false))
    }
}
