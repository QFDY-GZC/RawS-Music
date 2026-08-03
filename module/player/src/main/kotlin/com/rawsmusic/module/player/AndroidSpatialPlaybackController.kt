package com.rawsmusic.module.player

import android.content.Context
import com.rawsmusic.core.common.utils.AppLogger
import com.rawsmusic.module.data.prefs.AppPreferences

/**
 * Owns Android-output spatialization policy and head-tracker lifecycle.
 *
 * PlayerController keeps its public API as a thin facade so call sites do not change.
 * USB exclusive is observed only as a routing condition and remains untouched.
 */
internal class AndroidSpatialPlaybackController(
    context: Context,
    private val player: FfmpegAudioPlayer,
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val headTracker = AndroidSpatialHeadTracker(appContext) { x, y, z, w ->
        player.setAndroidBinauralHeadPose(x, y, z, w)
    }

    fun setPlatformSpatialEnabled(enabled: Boolean): Boolean {
        if (enabled) {
            AppPreferences.Player.androidBinauralSpatialEnabled = false
            player.androidBinauralSpatialEnabled = false
        }
        val capability = AndroidSpatialAudio.snapshot(appContext)
        val accepted = !enabled || capability.canRequestPlatform
        AppPreferences.Player.androidSpatialAudioEnabled = enabled && accepted
        val rebuilt = player.onAndroidSpatialAudioPreferenceChanged()
        val snapshot = AndroidSpatialAudio.snapshot(appContext)
        AppLogger.i(
            TAG,
            "Android spatial audio changed: requested=$enabled accepted=$accepted rebuilt=$rebuilt " +
                "mode=${snapshot.outputMode} feature=${snapshot.featureSupported} " +
                "available=${snapshot.available} platformEnabled=${snapshot.platformEnabled} " +
                "stereo=${snapshot.stereoCanBeSpatialized} effective=${snapshot.effective}"
        )
        return rebuilt
    }

    fun setBinauralEnabled(enabled: Boolean): Boolean {
        val platformWasEffective =
            enabled &&
                AppPreferences.Player.androidSpatialAudioEnabled &&
                AndroidSpatialAudio.snapshot(appContext).effective

        AppPreferences.Player.androidBinauralSpatialEnabled = enabled
        if (enabled) AppPreferences.Player.androidSpatialAudioEnabled = false

        player.androidBinauralSpatialIntensity =
            AppPreferences.Player.androidBinauralSpatialIntensity.toFloat()
        player.androidBinauralSpatialRoom =
            AppPreferences.Player.androidBinauralSpatialRoom.toFloat()
        player.androidBinauralSpatialEnabled = enabled
        refreshRoutingState()

        val rebuilt = if (platformWasEffective) {
            player.onAndroidSpatialAudioPreferenceChanged()
        } else {
            false
        }
        AppLogger.i(
            TAG,
            "Raw Android binaural spatial changed: enabled=$enabled hotApplied=true " +
                "platformWasEffective=$platformWasEffective rebuilt=$rebuilt " +
                "usb=${player.usbExclusiveMode} mode=${AppPreferences.Player.audioOutputMode}"
        )
        return rebuilt
    }

    fun setBasicParameters(intensity: Float, room: Float) {
        val safeIntensity = intensity.coerceIn(0f, 100f)
        val safeRoom = room.coerceIn(0f, 100f)
        AppPreferences.Player.androidBinauralSpatialIntensity = safeIntensity.toInt()
        AppPreferences.Player.androidBinauralSpatialRoom = safeRoom.toInt()
        player.androidBinauralSpatialIntensity = safeIntensity
        player.androidBinauralSpatialRoom = safeRoom
    }

    fun setAdvancedParameters(
        brirEnabled: Boolean,
        separation: Float,
        headSizeCentimeters: Float,
        pinnaDetail: Float,
    ) {
        val safeSeparation = separation.coerceIn(0f, 100f)
        val safeHeadSize = headSizeCentimeters.coerceIn(48f, 68f)
        val safePinna = pinnaDetail.coerceIn(0f, 100f)
        AppPreferences.Player.androidBinauralBrirEnabled = brirEnabled
        AppPreferences.Player.androidBinauralSeparation = safeSeparation.toInt()
        AppPreferences.Player.androidBinauralHeadSizeCentimeters = safeHeadSize.toInt()
        AppPreferences.Player.androidBinauralPinnaDetail = safePinna.toInt()
        player.androidBinauralBrirEnabled = brirEnabled
        player.androidBinauralSeparation = safeSeparation
        player.androidBinauralHeadSizeCentimeters = safeHeadSize
        player.androidBinauralPinnaDetail = safePinna
    }

    fun setHeadTrackingEnabled(enabled: Boolean): Boolean {
        val accepted = enabled && headTracker.isAvailable()
        AppPreferences.Player.androidBinauralHeadTrackingEnabled = accepted
        player.androidBinauralHeadTrackingEnabled = accepted
        refreshRoutingState()
        AppLogger.i(
            TAG,
            "Raw spatial head tracking: requested=$enabled accepted=$accepted " +
                "available=${headTracker.isAvailable()}"
        )
        return accepted
    }

    fun recenterHeadTracking() {
        headTracker.recenter()
    }

    fun headTrackingCapability(): AndroidSpatialHeadTracker.Capability =
        AndroidSpatialHeadTracker.capability(appContext)

    fun refreshRoutingState() {
        val enabled =
            AppPreferences.Player.androidBinauralSpatialEnabled &&
                AppPreferences.Player.androidBinauralHeadTrackingEnabled &&
                !player.usbExclusiveMode
        player.androidBinauralHeadTrackingEnabled = enabled
        headTracker.setEnabled(enabled)
        if (!enabled) player.setAndroidBinauralHeadPose(0f, 0f, 0f, 1f)
    }

    fun restoreSettings() {
        player.androidBinauralSpatialIntensity =
            AppPreferences.Player.androidBinauralSpatialIntensity.toFloat()
        player.androidBinauralSpatialRoom =
            AppPreferences.Player.androidBinauralSpatialRoom.toFloat()
        player.androidBinauralBrirEnabled =
            AppPreferences.Player.androidBinauralBrirEnabled
        player.androidBinauralSeparation =
            AppPreferences.Player.androidBinauralSeparation.toFloat()
        player.androidBinauralHeadSizeCentimeters =
            AppPreferences.Player.androidBinauralHeadSizeCentimeters.toFloat()
        player.androidBinauralPinnaDetail =
            AppPreferences.Player.androidBinauralPinnaDetail.toFloat()
        player.androidBinauralSpatialEnabled =
            AppPreferences.Player.androidBinauralSpatialEnabled
        refreshRoutingState()
    }

    override fun close() {
        headTracker.close()
    }

    private companion object {
        const val TAG = "AndroidSpatialPlayback"
    }
}
