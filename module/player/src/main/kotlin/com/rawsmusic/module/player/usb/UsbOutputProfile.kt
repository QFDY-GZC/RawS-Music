package com.rawsmusic.module.player.usb

enum class UsbVolumePath {
    /**
     * 软件音量：PCM gain 至少包含 user volume。
     * bit-perfect 开启但没有有效硬件音量时也走这里：不重采样/不改位深，
     * 只允许用户音量进入 PCM，避免"完美比特 + 无硬件音量"完全不可调音量。
     */
    Software,
    /** 硬件音量：PCM 用户音量保持 unity，FU = userVolume */
    HardwareUserVolume,
    /** 严格固定输出：PCM gain = 1.0，保留给显式固定输出/诊断路径 */
    Fixed
}

data class UsbOutputProfile(
    val exclusive: Boolean,
    val bitPerfect: Boolean,
    val hardwareVolumeRequested: Boolean,
    val hardwareVolumeValidated: Boolean,

    val targetSampleRate: Int,
    val targetBitDepth: Int,
    val targetSubslotBytes: Int,
    val pcmOutputMode: UsbPcmOutputMode,

    val dsdConversionEnabled: Boolean,
    val dsdDoPEnabled: Boolean,
    val dsdSourceDirect: Boolean = false,

    val safeMode: Boolean,

    val noClockSet: Boolean,
    val noFeedback: Boolean,
    val noFeatureUnit: Boolean,
    val force1msPacket: Boolean,
    val preferSafeAlt: Boolean,
    val forceSoftwareVolume: Boolean,
    val fixedDigitalVolume: Boolean = false,

    val lastGoodAlt: Int = 0,
    val lastGoodSampleRate: Int = 0,
    val lastGoodBitDepth: Int = 0,
    val lastGoodSubslot: Int = 0,
    val lastGoodFeedbackEndpoint: Int = 0
) {
    val hardwareVolumeEffective: Boolean
        get() = exclusive &&
            hardwareVolumeRequested &&
            hardwareVolumeValidated &&
            !noFeatureUnit &&
            !forceSoftwareVolume

    val volumePath: UsbVolumePath
        get() = when {
            fixedDigitalVolume -> UsbVolumePath.Fixed
            // Existing DSD source direct output is a raw 1-bit bitstream.
            // Software PCM gain cannot be applied without converting it back
            // to PCM, so expose either hardware volume or fixed output.
            dsdSourceDirect && hardwareVolumeEffective -> UsbVolumePath.HardwareUserVolume
            dsdSourceDirect -> UsbVolumePath.Fixed
            // PCM->DSD has a PCM pre-conversion stage, but when a validated
            // Feature Unit exists we prefer hardware volume so the user does
            // not end up with a hidden low PCM gain while the UI shows the DAC
            // near 0dB.
            dsdConversionEnabled && hardwareVolumeEffective -> UsbVolumePath.HardwareUserVolume
            dsdConversionEnabled -> UsbVolumePath.Software
            hardwareVolumeEffective -> UsbVolumePath.HardwareUserVolume
            else -> UsbVolumePath.Software
        }

    val shouldResample: Boolean
        get() = exclusive && !bitPerfect && targetSampleRate > 0 && !dsdConversionEnabled

    val shouldConvertBitDepth: Boolean
        get() = exclusive && !bitPerfect && targetBitDepth > 0 && !dsdConversionEnabled
}

data class UsbVolumePlan(
    val pcmGain: Float,
    val hardwareDb: Int,
    val useHardwareVolume: Boolean,
    val fixedOutput: Boolean,
    val reason: String
)

private const val HW_VOLUME_MIN_DB = -60
private const val HW_VOLUME_MAX_DB = 0

fun userVolumeToHardwareDb(volume: Float): Int {
    return UsbHardwareVolumeModel.uiVolumeToHardwareDb(volume)
        .coerceIn(HW_VOLUME_MIN_DB, HW_VOLUME_MAX_DB)
}
