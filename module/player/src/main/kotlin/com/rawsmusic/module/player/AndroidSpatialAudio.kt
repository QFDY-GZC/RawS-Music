package com.rawsmusic.module.player

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.Spatializer
import android.os.Build
import com.rawsmusic.core.common.model.AudioOutputMode
import com.rawsmusic.module.data.prefs.AppPreferences
import java.util.concurrent.Executor

/**
 * Android platform spatial-audio policy for the regular (non USB-exclusive) output path.
 *
 * Platform Spatializer delegation and RawSMusic's custom binaural renderer are mutually
 * exclusive. When the custom renderer is enabled, output is declared already spatialized
 * and Android is explicitly asked not to process it a second time.
 */
object AndroidSpatialAudio {
    /** Values defined by aaudio/AAudio.h (API 32+). */
    const val AAUDIO_SPATIALIZATION_BEHAVIOR_AUTO = 1
    const val AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER = 2

    data class Snapshot(
        val apiSupported: Boolean,
        val featureSupported: Boolean,
        val available: Boolean,
        val platformEnabled: Boolean,
        val stereoCanBeSpatialized: Boolean,
        val multichannelCanBeSpatialized: Boolean,
        val headTrackerAvailable: Boolean,
        val requestedByApp: Boolean,
        val customRendererRequested: Boolean,
        val outputMode: AudioOutputMode,
        val backendSupportsExplicitBehavior: Boolean
    ) {
        val effective: Boolean
            get() = requestedByApp &&
                !customRendererRequested &&
                featureSupported &&
                available &&
                platformEnabled &&
                stereoCanBeSpatialized &&
                backendSupportsExplicitBehavior

        val canRequestPlatform: Boolean
            get() = apiSupported &&
                featureSupported &&
                backendSupportsExplicitBehavior &&
                !customRendererRequested
    }

    fun isRequested(): Boolean = AppPreferences.Player.androidSpatialAudioEnabled

    fun isCustomRendererRequested(): Boolean =
        AppPreferences.Player.androidBinauralSpatialEnabled

    /** AudioTrack/AAudio expose explicit per-stream behavior. */
    fun backendSupportsExplicitBehavior(mode: AudioOutputMode): Boolean {
        return mode == AudioOutputMode.AUDIO_TRACK || mode == AudioOutputMode.AAUDIO
    }

    /** The custom renderer applies to every Android PCM backend, including Direct. */
    fun customRendererAppliesTo(mode: AudioOutputMode): Boolean {
        // USB exclusive is selected outside AudioOutputMode and is still bypassed
        // by FfmpegAudioPlayer/PlaybackDspProcessor. Direct is ordinary PCM before
        // the final Android write, so it can safely use the same renderer.
        return true
    }

    fun applyToMediaAttributesBuilder(
        context: Context,
        builder: AudioAttributes.Builder,
        outputMode: AudioOutputMode
    ): AudioAttributes.Builder {
        if (Build.VERSION.SDK_INT >= 32) {
            val customSpatialized =
                isCustomRendererRequested() && customRendererAppliesTo(outputMode)
            builder.setSpatializationBehavior(
                if (customSpatialized) {
                    AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER
                } else {
                    javaSpatializationBehavior(context, outputMode)
                }
            )
            builder.setIsContentSpatialized(customSpatialized)
        }
        return builder
    }

    fun javaSpatializationBehavior(context: Context, outputMode: AudioOutputMode): Int {
        if (Build.VERSION.SDK_INT < 32) return 0
        return if (shouldRequestAuto(context, outputMode)) {
            AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO
        } else {
            AudioAttributes.SPATIALIZATION_BEHAVIOR_NEVER
        }
    }

    fun aaudioSpatializationBehavior(context: Context, outputMode: AudioOutputMode): Int {
        return if (Build.VERSION.SDK_INT >= 32 && shouldRequestAuto(context, outputMode)) {
            AAUDIO_SPATIALIZATION_BEHAVIOR_AUTO
        } else {
            AAUDIO_SPATIALIZATION_BEHAVIOR_NEVER
        }
    }

    fun aaudioContentSpatialized(outputMode: AudioOutputMode): Boolean {
        return Build.VERSION.SDK_INT >= 32 &&
            isCustomRendererRequested() &&
            customRendererAppliesTo(outputMode)
    }

    private fun shouldRequestAuto(context: Context, outputMode: AudioOutputMode): Boolean {
        if (isCustomRendererRequested()) return false
        if (!AppPreferences.Player.androidSpatialAudioEnabled) return false
        if (!backendSupportsExplicitBehavior(outputMode)) return false
        if (Build.VERSION.SDK_INT < 32) return false

        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val spatializer = audioManager?.spatializer ?: return false
            // Avoid asking broken vendor services to create AUTO streams after the
            // framework has already reported STATE_NOT_SUPPORTED / immersive NONE.
            spatializer.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE
        } catch (_: Throwable) {
            false
        }
    }

    fun snapshot(context: Context): Snapshot {
        val storedMode = AppPreferences.Player.audioOutputMode
        val mode = if (storedMode == AudioOutputMode.AAUDIO && Build.VERSION.SDK_INT < 27) {
            AudioOutputMode.OPENSL_ES
        } else {
            storedMode
        }
        val requested = AppPreferences.Player.androidSpatialAudioEnabled
        val custom = AppPreferences.Player.androidBinauralSpatialEnabled
        val explicit = backendSupportsExplicitBehavior(mode)

        if (Build.VERSION.SDK_INT < 32) {
            return emptySnapshot(
                apiSupported = false,
                requested = requested,
                custom = custom,
                mode = mode,
                explicit = explicit
            )
        }

        return snapshotApi32(context, mode, requested, custom, explicit)
    }

    private fun emptySnapshot(
        apiSupported: Boolean,
        requested: Boolean,
        custom: Boolean,
        mode: AudioOutputMode,
        explicit: Boolean
    ) = Snapshot(
        apiSupported = apiSupported,
        featureSupported = false,
        available = false,
        platformEnabled = false,
        stereoCanBeSpatialized = false,
        multichannelCanBeSpatialized = false,
        headTrackerAvailable = false,
        requestedByApp = requested,
        customRendererRequested = custom,
        outputMode = mode,
        backendSupportsExplicitBehavior = explicit
    )

    private fun snapshotApi32(
        context: Context,
        mode: AudioOutputMode,
        requested: Boolean,
        custom: Boolean,
        explicit: Boolean
    ): Snapshot {
        return try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            val spatializer = audioManager?.spatializer
                ?: return emptySnapshot(true, requested, custom, mode, explicit)

            val featureSupported =
                spatializer.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE

            // Critical vendor-log fix: do not call canBeSpatialized() at all when
            // immersive level is NONE. OPlus/Xiaomi otherwise log the same
            // STATE_NOT_SUPPORTED failure several times per refresh.
            if (!featureSupported) {
                return emptySnapshot(true, requested, custom, mode, explicit)
            }

            val attributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .setSpatializationBehavior(AudioAttributes.SPATIALIZATION_BEHAVIOR_AUTO)
                .setIsContentSpatialized(false)
                .build()

            Snapshot(
                apiSupported = true,
                featureSupported = true,
                available = spatializer.isAvailable,
                platformEnabled = spatializer.isEnabled,
                stereoCanBeSpatialized = canSpatializeStereo(spatializer, attributes),
                multichannelCanBeSpatialized = canSpatializeMultichannel(spatializer, attributes),
                headTrackerAvailable =
                    Build.VERSION.SDK_INT >= 33 && spatializer.isHeadTrackerAvailable,
                requestedByApp = requested,
                customRendererRequested = custom,
                outputMode = mode,
                backendSupportsExplicitBehavior = explicit
            )
        } catch (_: Throwable) {
            emptySnapshot(true, requested, custom, mode, explicit)
        }
    }

    /**
     * Event-driven capability observer. No polling and no repeated capability probes.
     */
    fun observe(context: Context, onChanged: () -> Unit): AutoCloseable {
        val appContext = context.applicationContext
        val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return AutoCloseable { }
        val executor: Executor = if (Build.VERSION.SDK_INT >= 28) {
            appContext.mainExecutor
        } else {
            Executor { command -> command.run() }
        }

        val deviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) = onChanged()
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) = onChanged()
        }
        runCatching { audioManager.registerAudioDeviceCallback(deviceCallback, null) }

        var spatializer: Spatializer? = null
        var stateListener: Spatializer.OnSpatializerStateChangedListener? = null
        if (Build.VERSION.SDK_INT >= 32) {
            runCatching {
                val candidate = audioManager.spatializer
                if (candidate.immersiveAudioLevel != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE) {
                    val listener = object : Spatializer.OnSpatializerStateChangedListener {
                        override fun onSpatializerEnabledChanged(
                            spatializer: Spatializer,
                            enabled: Boolean
                        ) = onChanged()

                        override fun onSpatializerAvailableChanged(
                            spatializer: Spatializer,
                            available: Boolean
                        ) = onChanged()
                    }
                    candidate.addOnSpatializerStateChangedListener(executor, listener)
                    spatializer = candidate
                    stateListener = listener
                }
            }
        }

        return AutoCloseable {
            runCatching { audioManager.unregisterAudioDeviceCallback(deviceCallback) }
            if (Build.VERSION.SDK_INT >= 32) {
                val target = spatializer
                val listener = stateListener
                if (target != null && listener != null) {
                    runCatching { target.removeOnSpatializerStateChangedListener(listener) }
                }
            }
        }
    }

    private fun canSpatializeStereo(
        spatializer: Spatializer,
        attributes: AudioAttributes
    ): Boolean {
        val encodings = intArrayOf(AudioFormat.ENCODING_PCM_FLOAT, AudioFormat.ENCODING_PCM_16BIT)
        return encodings.any { encoding ->
            runCatching {
                val format = AudioFormat.Builder()
                    .setSampleRate(48_000)
                    .setEncoding(encoding)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                    .build()
                spatializer.canBeSpatialized(attributes, format)
            }.getOrDefault(false)
        }
    }

    private fun canSpatializeMultichannel(
        spatializer: Spatializer,
        attributes: AudioAttributes
    ): Boolean {
        return runCatching {
            val format = AudioFormat.Builder()
                .setSampleRate(48_000)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1)
                .build()
            spatializer.canBeSpatialized(attributes, format)
        }.getOrDefault(false)
    }
}
