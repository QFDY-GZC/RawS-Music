package com.rawsmusic.module.player.devicecontrol.bluetooth

import java.nio.charset.StandardCharsets
import java.util.UUID

/** Bluetooth SIG-assigned LE Audio control UUIDs and Android-free value codecs. */
internal object BluetoothLeAudioStandard {
    private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

    fun uuid16(value: Int): UUID = UUID.fromString("0000%04x%s".format(value and 0xffff, BASE_UUID_SUFFIX))

    // Services.
    val AICS_SERVICE: UUID = uuid16(0x1843)
    val VCS_SERVICE: UUID = uuid16(0x1844)
    val VOCS_SERVICE: UUID = uuid16(0x1845)

    // AICS characteristics.
    val AUDIO_INPUT_STATE: UUID = uuid16(0x2B77)
    val GAIN_SETTING_PROPERTIES: UUID = uuid16(0x2B78)
    val AUDIO_INPUT_TYPE: UUID = uuid16(0x2B79)
    val AUDIO_INPUT_STATUS: UUID = uuid16(0x2B7A)
    val AUDIO_INPUT_CONTROL_POINT: UUID = uuid16(0x2B7B)
    val AUDIO_INPUT_DESCRIPTION: UUID = uuid16(0x2B7C)

    // VCS characteristics.
    val VOLUME_STATE: UUID = uuid16(0x2B7D)
    val VOLUME_CONTROL_POINT: UUID = uuid16(0x2B7E)
    val VOLUME_FLAGS: UUID = uuid16(0x2B7F)

    // VOCS characteristics.
    val VOLUME_OFFSET_STATE: UUID = uuid16(0x2B80)
    val AUDIO_LOCATION: UUID = uuid16(0x2B81)
    val VOLUME_OFFSET_CONTROL_POINT: UUID = uuid16(0x2B82)
    val AUDIO_OUTPUT_DESCRIPTION: UUID = uuid16(0x2B83)

    data class VcsVolumeState(
        val volumeSetting: Int,
        val muted: Boolean,
        val changeCounter: Int,
    )

    data class VocsVolumeOffsetState(
        val volumeOffset: Int,
        val changeCounter: Int,
    )

    enum class AicsMuteState {
        NOT_MUTED,
        MUTED,
        DISABLED,
        UNKNOWN,
    }

    enum class AicsGainMode {
        MANUAL_ONLY,
        AUTOMATIC_ONLY,
        MANUAL,
        AUTOMATIC,
        UNKNOWN,
    }

    data class AicsInputState(
        val gainSetting: Int,
        val mute: AicsMuteState,
        val gainMode: AicsGainMode,
        val changeCounter: Int,
    )

    data class AicsGainSettingProperties(
        /** Multiplier in tenths of a decibel for one raw gain-setting unit. */
        val gainSettingUnits: Int,
        val minimumGainSetting: Int,
        val maximumGainSetting: Int,
    ) {
        val stepDb: Double get() = gainSettingUnits * 0.1
        fun rawToDb(raw: Int): Double = raw * stepDb

        fun dbToRaw(db: Double): Int? {
            val step = stepDb
            if (!db.isFinite() || step <= 0.0) return null
            val raw = kotlin.math.round(db / step).toInt()
            return raw.takeIf { it in minimumGainSetting..maximumGainSetting }
        }
    }


    // VCS control-point opcodes. Every command includes the latest Change_Counter.
    private const val VCS_SET_ABSOLUTE_VOLUME = 0x04
    private const val VCS_UNMUTE = 0x05
    private const val VCS_MUTE = 0x06

    // VOCS control point.
    private const val VOCS_SET_VOLUME_OFFSET = 0x01

    // AICS control-point opcodes.
    private const val AICS_SET_GAIN = 0x01
    private const val AICS_UNMUTE = 0x02
    private const val AICS_MUTE = 0x03
    private const val AICS_SET_MANUAL_GAIN_MODE = 0x04
    private const val AICS_SET_AUTOMATIC_GAIN_MODE = 0x05

    fun encodeVcsSetAbsoluteVolume(changeCounter: Int, volumeSetting: Int): ByteArray? {
        if (changeCounter !in 0..255 || volumeSetting !in 0..255) return null
        return byteArrayOf(VCS_SET_ABSOLUTE_VOLUME.toByte(), changeCounter.toByte(), volumeSetting.toByte())
    }

    fun encodeVcsMute(changeCounter: Int, muted: Boolean): ByteArray? {
        if (changeCounter !in 0..255) return null
        return byteArrayOf(
            (if (muted) VCS_MUTE else VCS_UNMUTE).toByte(),
            changeCounter.toByte(),
        )
    }

    fun encodeVocsSetVolumeOffset(changeCounter: Int, offset: Int): ByteArray? {
        if (changeCounter !in 0..255 || offset !in -255..255) return null
        val raw = offset and 0xffff
        return byteArrayOf(
            VOCS_SET_VOLUME_OFFSET.toByte(),
            changeCounter.toByte(),
            (raw and 0xff).toByte(),
            ((raw ushr 8) and 0xff).toByte(),
        )
    }

    fun encodeAicsSetGain(changeCounter: Int, gainSetting: Int): ByteArray? {
        if (changeCounter !in 0..255 || gainSetting !in -128..127) return null
        return byteArrayOf(AICS_SET_GAIN.toByte(), changeCounter.toByte(), gainSetting.toByte())
    }

    fun encodeAicsMute(changeCounter: Int, muted: Boolean): ByteArray? {
        if (changeCounter !in 0..255) return null
        return byteArrayOf(
            (if (muted) AICS_MUTE else AICS_UNMUTE).toByte(),
            changeCounter.toByte(),
        )
    }

    fun encodeAicsGainMode(changeCounter: Int, automatic: Boolean): ByteArray? {
        if (changeCounter !in 0..255) return null
        return byteArrayOf(
            (if (automatic) AICS_SET_AUTOMATIC_GAIN_MODE else AICS_SET_MANUAL_GAIN_MODE).toByte(),
            changeCounter.toByte(),
        )
    }

    fun decodeVcsVolumeState(value: ByteArray): VcsVolumeState? {
        if (value.size != 3) return null
        val muteRaw = u8(value[1])
        if (muteRaw !in 0..1) return null
        return VcsVolumeState(
            volumeSetting = u8(value[0]),
            muted = muteRaw == 1,
            changeCounter = u8(value[2]),
        )
    }

    fun decodeVocsVolumeOffsetState(value: ByteArray): VocsVolumeOffsetState? {
        if (value.size != 3) return null
        return VocsVolumeOffsetState(
            volumeOffset = s16Le(value, 0),
            changeCounter = u8(value[2]),
        )
    }

    fun decodeAicsInputState(value: ByteArray): AicsInputState? {
        if (value.size != 4) return null
        return AicsInputState(
            gainSetting = value[0].toInt(),
            mute = when (u8(value[1])) {
                0 -> AicsMuteState.NOT_MUTED
                1 -> AicsMuteState.MUTED
                2 -> AicsMuteState.DISABLED
                else -> AicsMuteState.UNKNOWN
            },
            gainMode = when (u8(value[2])) {
                0 -> AicsGainMode.MANUAL_ONLY
                1 -> AicsGainMode.AUTOMATIC_ONLY
                2 -> AicsGainMode.MANUAL
                3 -> AicsGainMode.AUTOMATIC
                else -> AicsGainMode.UNKNOWN
            },
            changeCounter = u8(value[3]),
        )
    }

    fun decodeAicsGainSettingProperties(value: ByteArray): AicsGainSettingProperties? {
        if (value.size != 3) return null
        val minimum = value[1].toInt()
        val maximum = value[2].toInt()
        if (maximum < minimum) return null
        return AicsGainSettingProperties(
            gainSettingUnits = u8(value[0]),
            minimumGainSetting = minimum,
            maximumGainSetting = maximum,
        )
    }

    fun decodeUint8(value: ByteArray): Int? = value.singleOrNull()?.let(::u8)

    fun decodeUint32Le(value: ByteArray): Long? {
        if (value.size != 4) return null
        return (u8(value[0]).toLong()
            or (u8(value[1]).toLong() shl 8)
            or (u8(value[2]).toLong() shl 16)
            or (u8(value[3]).toLong() shl 24))
    }

    fun decodeUtf8(value: ByteArray): String? {
        if (value.isEmpty()) return ""
        return runCatching { String(value, StandardCharsets.UTF_8) }
            .getOrNull()
            ?.trimEnd('\u0000')
    }

    private fun u8(value: Byte): Int = value.toInt() and 0xff

    private fun s16Le(value: ByteArray, offset: Int): Int {
        val raw = u8(value[offset]) or (u8(value[offset + 1]) shl 8)
        return if (raw and 0x8000 != 0) raw - 0x1_0000 else raw
    }
}
