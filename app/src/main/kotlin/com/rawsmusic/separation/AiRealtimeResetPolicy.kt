package com.rawsmusic.separation

/** Pure lifecycle rule shared by realtime separation and AI performance. */
internal object AiRealtimeResetPolicy {
    fun shouldRearm(
        desiredEnabled: Boolean,
        ready: Boolean,
        cachedDirect: Boolean,
        sharedDirect: Boolean,
        modelOpenInFlight: Boolean,
    ): Boolean = desiredEnabled && !ready && !cachedDirect && !sharedDirect && !modelOpenInFlight
}
