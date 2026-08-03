package com.rawsmusic.module.player

import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences
import com.rawsmusic.module.player.usb.UsbOutputProfile
import com.rawsmusic.module.player.usb.UsbPcmOutputMode

/** Commits a resolved USB profile without owning the playback lifecycle. */
internal class PlayerUsbOutputProfileApplier(
    private val tag: String,
    private val callbacks: Callbacks,
) {
    data class Callbacks(
        val setFfmpegBitPerfect: (Boolean) -> Unit,
        val setPcmOutputMode: (UsbPcmOutputMode) -> Unit,
        val setDacSettings: (Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
        val setNativePolicy: (Boolean, Boolean, Boolean) -> Unit,
        val setDsdConversion: (Boolean, Int, Int, Boolean, Boolean) -> Unit,
        val setLastGoodProfile: (Int, Int, Int, Int, Int) -> Unit,
        val setCompatFlags: (Boolean, Boolean, Boolean, Boolean, Boolean) -> Unit,
        val currentDsdRate: () -> Int,
        val currentSongIsDsdSource: () -> Boolean,
    )

    fun apply(profile: UsbOutputProfile) {
        callbacks.setFfmpegBitPerfect(profile.bitPerfect)
        val effectiveNoFeedback = profile.noFeedback
        val effectiveFeedbackEndpoint = if (effectiveNoFeedback) 0 else profile.lastGoodFeedbackEndpoint

        callbacks.setPcmOutputMode(profile.pcmOutputMode)
        callbacks.setDacSettings(false, false, false, false, profile.force1msPacket)

        // Keep the requested hardware-volume bit here. Native init must probe
        // the Feature Unit before the effective state can become true.
        callbacks.setNativePolicy(
            profile.exclusive,
            profile.bitPerfect || profile.fixedDigitalVolume,
            profile.hardwareVolumeRequested,
        )

        val dsdRate = callbacks.currentDsdRate()
        val sourceIsDsd = callbacks.currentSongIsDsdSource()
        callbacks.setDsdConversion(
            profile.dsdConversionEnabled,
            dsdRate,
            AppPreferences.Player.dsdConversionType,
            AppPreferences.Player.dsdDitherEnabled,
            profile.dsdConversionEnabled && profile.dsdDoPEnabled,
        )
        AppLogger.i(
            tag,
            "USB DSD transport apply: sourceDsd=$sourceIsDsd " +
                "pcmToDsd=${AppPreferences.Player.dsdConversionEnabled && !sourceIsDsd} " +
                "active=${profile.dsdConversionEnabled} rate=DSD$dsdRate " +
                "transport=${if (profile.dsdDoPEnabled) "DoP" else "Native"}",
        )

        callbacks.setLastGoodProfile(
            profile.lastGoodAlt,
            profile.lastGoodSampleRate,
            profile.lastGoodBitDepth,
            profile.lastGoodSubslot,
            effectiveFeedbackEndpoint,
        )
        callbacks.setCompatFlags(
            profile.noClockSet,
            effectiveNoFeedback,
            profile.noFeatureUnit,
            profile.preferSafeAlt,
            profile.safeMode,
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
