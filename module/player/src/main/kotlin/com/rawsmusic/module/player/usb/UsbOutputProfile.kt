package com.rawsmusic.module.player.usb

enum class UsbVolumePath {
    /** 软件音量：仅非 bit-perfect PCM 允许 user volume 进入 PCM data plane。 */
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
        get() = UsbVolumeOwnershipPolicy.resolve(
            bitPerfect = bitPerfect,
            hardwareVolumeEffective = hardwareVolumeEffective,
            dsdConversionEnabled = dsdConversionEnabled,
            dsdSourceDirect = dsdSourceDirect,
            fixedDigitalVolume = fixedDigitalVolume,
        )

    val shouldResample: Boolean
        get() = exclusive && !bitPerfect && targetSampleRate > 0 && !dsdConversionEnabled

    val shouldConvertBitDepth: Boolean
        get() = exclusive && !bitPerfect && targetBitDepth > 0 && !dsdConversionEnabled
}

data class UsbVolumePlan(
    val pcmGain: Float,
    val useHardwareVolume: Boolean,
    val fixedOutput: Boolean,
    val reason: String
)
