package com.rawsmusic.module.player

import com.rawsmusic.core.common.model.AudioFile
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.usb.UsbVolumePath
import com.rawsmusic.module.player.usb.UsbVolumePlan
import com.rawsmusic.module.player.usb.userVolumeToHardwareDb

internal data class ReplayGainDecision(
    val active: Boolean,
    val gainDb: Float,
    val peak: Float,
    val linearGain: Float
)

/** Pure playback-volume calculations; hardware and decoder writes remain in PlayerController. */
internal object PlaybackVolumePlanner {
    private const val USB_SOFTWARE_VOLUME_TAPER = 3.0

    fun replayGain(
        song: AudioFile,
        normalizationEnabled: Boolean,
        replayGainEnabled: Boolean,
        replayGainMode: Int
    ): ReplayGainDecision {
        val active = normalizationEnabled || replayGainEnabled
        if (!active) {
            return ReplayGainDecision(false, 0f, 1f, 1f)
        }

        val gainDb = when {
            replayGainEnabled -> when (replayGainMode) {
                1 -> song.trackGain
                2 -> song.albumGain
                else -> 0f
            }
            song.trackGain != 0f -> song.trackGain
            else -> 0f
        }
        val peak = when {
            replayGainEnabled -> when (replayGainMode) {
                1 -> song.trackPeak
                2 -> song.albumPeak
                else -> 1f
            }
            else -> song.trackPeak.takeIf { it > 0f } ?: 1f
        }
        var linearGain = dbToLinear(gainDb)
        if (peak > 0f && linearGain * peak > 1f) {
            linearGain = 1f / peak
        }
        return ReplayGainDecision(true, gainDb, peak, linearGain)
    }

    /**
     * Ordinary Android output already receives the user's STREAM_MUSIC volume in AudioFlinger.
     * The player-side stream gain must contain only internal playback modifiers, otherwise the
     * system volume is multiplied a second time by AudioTrack/AAudio/OpenSL.
     */
    fun androidSoftwareGain(
        replayGain: Float,
        duck: Float
    ): Float {
        val effectiveReplayGain = replayGain.coerceIn(0f, 4f)
        val effectiveDuck = duck.coerceIn(0f, 1f)
        return (effectiveReplayGain * effectiveDuck).coerceIn(0f, 1f)
    }

    fun usbSoftwarePcmGain(uiVolume: Float): Float {
        val volume = uiVolume.coerceIn(0f, 1f)
        if (volume <= 0.0001f) return 0f
        if (volume >= 0.9999f) return 1f
        return Math.pow(volume.toDouble(), USB_SOFTWARE_VOLUME_TAPER)
            .toFloat()
            .coerceIn(0f, 1f)
    }

    fun usbVolumePlan(
        profile: UsbOutputProfile,
        userVolume: Float,
        replayGain: Float,
        duck: Float,
        reason: String
    ): UsbVolumePlan {
        val volume = userVolume.coerceIn(0f, 1f)
        val effectiveReplayGain = if (profile.bitPerfect) 1f else replayGain.coerceIn(0f, 4f)
        val effectiveDuck = if (profile.bitPerfect) 1f else duck.coerceIn(0f, 1f)
        return when (profile.volumePath) {
            UsbVolumePath.HardwareUserVolume -> UsbVolumePlan(
                pcmGain = if (profile.bitPerfect) {
                    1f
                } else {
                    (effectiveReplayGain * effectiveDuck).coerceIn(0f, 1f)
                },
                hardwareDb = userVolumeToHardwareDb(volume),
                useHardwareVolume = true,
                fixedOutput = profile.bitPerfect,
                reason = reason
            )
            UsbVolumePath.Fixed -> UsbVolumePlan(
                pcmGain = 1f,
                hardwareDb = 0,
                useHardwareVolume = false,
                fixedOutput = true,
                reason = reason
            )
            else -> UsbVolumePlan(
                pcmGain = (
                    usbSoftwarePcmGain(volume) * effectiveReplayGain * effectiveDuck
                ).coerceIn(0f, 1f),
                hardwareDb = 0,
                useHardwareVolume = false,
                fixedOutput = false,
                reason = reason
            )
        }
    }

    private fun dbToLinear(db: Float): Float {
        if (db == 0f || db.isNaN() || db.isInfinite()) return 1f
        val clampedDb = db.coerceIn(-24f, 12f)
        return Math.pow(10.0, clampedDb / 20.0)
            .toFloat()
            .coerceIn(0.063f, 3.981f)
    }
}
