package com.rawsmusic.module.player.usb

/**
 * Immutable next-session USB transport policy.
 *
 * Kotlin owns product/user intent; native owns the transport transaction.  The whole object is
 * serialized into one JNI call immediately before native USB init so session correctness no longer
 * depends on a sequence of process-global setter calls racing with re-open/recovery.
 */
data class UsbNativeSessionPolicy(
    val exclusive: Boolean = false,
    val bitPerfect: Boolean = false,
    val hardwareVolumeRequested: Boolean = false,
    val pcmOutputMode: UsbPcmOutputMode = UsbPcmOutputMode.AUTO,
    val dsdConversionEnabled: Boolean = false,
    val dsdRate: Int = 64,
    val dsdConversionType: Int = 0,
    val dsdDitherEnabled: Boolean = false,
    val dsdDoPEnabled: Boolean = false,
    val noControlInterface: Boolean = false,
    val forceUac1: Boolean = false,
    val linearVolume: Boolean = false,
    val replaceVolume: Boolean = false,
    val force1msPacket: Boolean = false,
    val noClockSet: Boolean = false,
    val noFeedback: Boolean = false,
    val noFeatureUnit: Boolean = false,
    val preferSafeAlt: Boolean = false,
    val safeMode: Boolean = false,
    val lastGoodAlt: Int = 0,
    val lastGoodSampleRate: Int = 0,
    val lastGoodValidBits: Int = 0,
    val lastGoodSubslotBytes: Int = 0,
    val lastGoodFeedbackEndpoint: Int = 0,
) {
    fun toNativeIntArray(): IntArray = intArrayOf(
        SCHEMA_VERSION,
        exclusive.asInt(),
        bitPerfect.asInt(),
        hardwareVolumeRequested.asInt(),
        pcmOutputMode.id,
        dsdConversionEnabled.asInt(),
        dsdRate,
        dsdConversionType,
        dsdDitherEnabled.asInt(),
        dsdDoPEnabled.asInt(),
        noControlInterface.asInt(),
        forceUac1.asInt(),
        linearVolume.asInt(),
        replaceVolume.asInt(),
        force1msPacket.asInt(),
        noClockSet.asInt(),
        noFeedback.asInt(),
        noFeatureUnit.asInt(),
        preferSafeAlt.asInt(),
        safeMode.asInt(),
        lastGoodAlt,
        lastGoodSampleRate,
        lastGoodValidBits,
        lastGoodSubslotBytes,
        lastGoodFeedbackEndpoint,
    )

    fun conciseLogString(): String =
        "v=$SCHEMA_VERSION ex=$exclusive bp=$bitPerfect hw=$hardwareVolumeRequested " +
            "pcm=$pcmOutputMode dsd=$dsdConversionEnabled/DSD$dsdRate/dop=$dsdDoPEnabled " +
            "compat=noClock:$noClockSet noFb:$noFeedback noFU:$noFeatureUnit " +
            "safeAlt:$preferSafeAlt safe:$safeMode force1ms:$force1msPacket " +
            "lastGood=$lastGoodAlt/$lastGoodSampleRate/$lastGoodValidBits/$lastGoodSubslotBytes/" +
            "0x${lastGoodFeedbackEndpoint.toString(16)}"

    companion object {
        const val SCHEMA_VERSION = 1
        const val NATIVE_FIELD_COUNT = 25
    }
}

/**
 * Resolve the native transport's immutable raw/fixed contract before nativeInit.
 *
 * Source DSD direct playback is already encoded data (DoP/native DSD).  It cannot pass through the
 * mutable PCM/software-volume path without destroying the transport, so the native session must be
 * born with the same raw/fixed bit-perfect flag that will still be present at nativeStart().
 * PCM->DSD is intentionally not covered by sourceDsdDirect: it still has a mutable PCM pre-stage.
 */
internal fun resolveUsbNativeSessionBitPerfect(
    requestedBitPerfect: Boolean,
    fixedDigitalVolume: Boolean,
    sourceDsdDirect: Boolean,
): Boolean = requestedBitPerfect || fixedDigitalVolume || sourceDsdDirect

private fun Boolean.asInt(): Int = if (this) 1 else 0
