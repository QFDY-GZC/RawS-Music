package com.rawsmusic.module.player.control

import com.rawsmusic.core.common.model.AudioFile

/**
 * Keeps the Android foreground/media identity synchronized while USB exclusive playback owns
 * the real audio route. The controller supplies transport state; this coordinator owns the
 * service-alive fallback and heartbeat de-duplication policy.
 */
class UsbMediaIdentityCoordinator(
    private val isExclusiveActive: () -> Boolean,
    private val syncControllerIdentity: (AudioFile?, Boolean, Long, String) -> Boolean,
    private val sendServiceIdentity: (String, AudioFile?, Long, Boolean) -> Unit,
    private val logInfo: (String) -> Unit,
) {
    fun ensure(
        reason: String,
        song: AudioFile?,
        positionMs: Long,
        playing: Boolean,
    ) {
        if (!isExclusiveActive()) return

        val serviceAlive = syncControllerIdentity(
            song,
            playing,
            positionMs.coerceAtLeast(0L),
            reason,
        )
        if (!serviceAlive || !reason.contains("heartbeat")) {
            sendServiceIdentity(reason, song, positionMs, playing)
        }
        logInfo(
            "ensureUsbMediaIdentity: reason=$reason pos=$positionMs " +
                "title=${song?.title} serviceAlive=$serviceAlive"
        )
    }
}
