package com.rawsmusic.module.player.control

/** Pure decision for whether a destructive USB recovery should wait for a safer foreground point. */
object UsbHardRecoveryDeferralPolicy {
    data class Snapshot(
        val exclusiveActive: Boolean,
        val appInBackground: Boolean,
        val backgroundEnteredAtMs: Long,
        val nowMs: Long,
        val playing: Boolean,
        val transportTransitioning: Boolean,
        val usbSeeking: Boolean,
        val recovering: Boolean,
    )

    data class Decision(
        val defer: Boolean,
        val backgroundAgeMs: Long,
        val recentlyBackgrounded: Boolean,
        val playingInBackground: Boolean,
    )

    fun evaluate(snapshot: Snapshot): Decision {
        if (!snapshot.exclusiveActive) {
            return Decision(
                defer = false,
                backgroundAgeMs = Long.MAX_VALUE,
                recentlyBackgrounded = false,
                playingInBackground = false,
            )
        }
        val age = if (snapshot.backgroundEnteredAtMs > 0L) {
            (snapshot.nowMs - snapshot.backgroundEnteredAtMs).coerceAtLeast(0L)
        } else {
            Long.MAX_VALUE
        }
        val recentlyBackgrounded = age in 0L..3_000L
        val playingInBackground = snapshot.appInBackground && snapshot.playing
        return Decision(
            defer = playingInBackground ||
                recentlyBackgrounded ||
                snapshot.transportTransitioning ||
                snapshot.usbSeeking ||
                snapshot.recovering,
            backgroundAgeMs = age,
            recentlyBackgrounded = recentlyBackgrounded,
            playingInBackground = playingInBackground,
        )
    }
}
