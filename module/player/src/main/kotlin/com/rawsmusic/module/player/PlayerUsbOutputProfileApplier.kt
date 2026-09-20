package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbOutputProfile

/** Commits a resolved USB profile without owning the playback lifecycle. */
internal class PlayerUsbOutputProfileApplier(
    private val tag: String,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val setFfmpegBitPerfect: (Boolean) -> Unit,
        val setNativePolicy: (Boolean, Boolean, Boolean) -> Unit,
        val stageNativeSessionPolicy: (UsbOutputProfile, Int, Int, Boolean) -> Unit,
        val currentDsdRate: () -> Int,
        val currentSongIsDsdSource: () -> Boolean,
    )

    fun apply(profile: UsbOutputProfile) {
        callbacks.setFfmpegBitPerfect(profile.bitPerfect)
        val effectiveNoFeedback = profile.noFeedback
        val effectiveFeedbackEndpoint = if (effectiveNoFeedback) 0 else profile.lastGoodFeedbackEndpoint

        // Keep the requested hardware-volume bit live for the current handle. Native session init,
        // however, receives the rest of the transport profile atomically through one transaction.
        callbacks.setNativePolicy(
            profile.exclusive,
            profile.bitPerfect || profile.fixedDigitalVolume || profile.dsdSourceDirect,
            profile.hardwareVolumeRequested,
        )

        val dsdRate = callbacks.currentDsdRate()
        val sourceIsDsd = callbacks.currentSongIsDsdSource()
        callbacks.stageNativeSessionPolicy(
            profile.copy(lastGoodFeedbackEndpoint = effectiveFeedbackEndpoint),
            dsdRate,
            AppPreferences.Player.dsdConversionType,
            AppPreferences.Player.dsdDitherEnabled,
        )
        AppLogger.i(
            tag,
            "USB DSD transport staged: sourceDsd=$sourceIsDsd " +
                "pcmToDsd=${AppPreferences.Player.dsdConversionEnabled && !sourceIsDsd} " +
                "active=${profile.dsdConversionEnabled} rate=DSD$dsdRate " +
                "transport=${if (profile.dsdDoPEnabled) "DoP" else "Native"}",
        )
        AppLogger.i(
            tag,
            "USB profile applied: noFeedback=${profile.noFeedback} effectiveNoFeedback=$effectiveNoFeedback " +
                "lastGoodFeedbackEndpoint=0x${profile.lastGoodFeedbackEndpoint.toString(16)} " +
                "effectiveLastGoodFeedbackEndpoint=0x${effectiveFeedbackEndpoint.toString(16)} " +
                "alt=${profile.lastGoodAlt} sr=${profile.lastGoodSampleRate} " +
                "bits=${profile.lastGoodBitDepth} subslot=${profile.lastGoodSubslot}",
        )
    }
}
