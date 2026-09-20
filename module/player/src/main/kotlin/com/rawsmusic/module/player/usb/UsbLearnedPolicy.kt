package com.rawsmusic.module.player.usb

import com.rawsmusic.module.data.prefs.AppPreferences

data class UsbLearnedPolicy(
    val vid: Int,
    val pid: Int,
    val productName: String? = null,
    val serial: String? = null,

    val lastGoodAlt: Int = 0,
    val lastGoodSampleRate: Int = 0,
    val lastGoodBitDepth: Int = 0,
    val lastGoodSubslot: Int = 0,
    val lastGoodFeedbackEndpoint: Int = 0,
    val lastGoodNoFeedback: Boolean = false,
    val lastGoodNoClockSet: Boolean = false,
    val lastGoodNoFeatureUnit: Boolean = false,
    val lastGoodPreferSafeAlt: Boolean = false,

    val noFeedback: Boolean = false,
    val noClockSet: Boolean = false,
    val noFeatureUnit: Boolean = false,
    val force1msPacket: Boolean = false,
    val preferSafeAlt: Boolean = false,

    val failureCount: Int = 0,
    val successCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)

enum class UsbSilentKind {
    None,
    DecoderNotFeeding,
    UsbNotOutputting,
    VolumeTooLow,
    ClockMismatch,
    FeedbackInvalid,
    FeatureUnitUnsafe,
    TransportError,
    Unknown
}

data class UsbSelfTestResult(
    val kind: UsbSilentKind,
    val shouldRestart: Boolean,
    val shouldFallbackProfile: Boolean,
    val message: String
)

data class UsbStatsSnapshot(
    val appInBytesPerSec: Long,
    /** Completed ISO bytes per second from libusb callback actual_length. */
    val usbOutBytesPerSec: Long,
    /** Scheduled ISO bytes per second; useful for detecting submit-vs-complete gaps. */
    val scheduledUsbBytesPerSec: Long = 0L,
    val expectedBytesPerSec: Long,
    val bufferUsedBytes: Long,
    val bufferCapacityBytes: Long,
    val underrun: Int,
    val submitErr: Int,
    val packetErr: Int,
    val xferErr: Int,
    val clockRate: Int,
    val targetRate: Int,
    val finalVolume: Float,
    val feedbackEnabled: Boolean,
    val sessionId: Long = 0L,
    val feedbackState: Int = 0,
    val feedbackValidCount: Int = 0,
    val feedbackInvalidCount: Int = 0,
    val feedbackEmptyCount: Int = 0,
    val feedbackSampleRateMilli: Int = 0,

    /** Pacing mode from native: NoFeedbackFixed / ExplicitFeedback / FeedbackDegradedFixed. */
    val pacingMode: String = "",
    val pacingModeId: Int = -1,

    // Diagnostics: clock / feature-unit / raw stats string.
    val clockSource: String = "",
    val clockSelector: String = "",
    val clockInterface: Int = -1,
    val clockVerified: Boolean? = null,
    val clockVerifiedRate: Int = 0,
    val clockValidKnown: Boolean = false,
    val clockValid: Boolean = false,

    // First normal PCM container diagnostic cached by native and exported in-app.
    val pcmInputDiagReady: Boolean = false,
    val pcmProtocol: Int = 0,
    val pcmSourceFrameBytes: Int = 0,
    val pcmDeviceFrameBytes: Int = 0,
    val pcmAdapter: String = "",
    val pcmNeedsResample: Boolean = false,
    val pcmSamples: Int = 0,
    val pcmNonSilent: Int = 0,
    val pcmLowZero: Int = 0,
    val pcmSignExtendedTop: Int = 0,
    val pcmFirst16Hex: String = "",

    val featureUnitPolicy: String = "",
    val featureUnitPath: String = "",
    val featureUnitResult: Int = 0,
    val featureUnitRangeVerified: Boolean = false,
    val featureUnitReadbackVerified: Boolean = false,
    val featureUnitReason: String = "",
    val featureUnitDescriptorMaster: Boolean = false,
    val featureUnitDescriptorLeft: Boolean = false,
    val featureUnitDescriptorRight: Boolean = false,
    val featureUnitEffectiveMaster: Boolean = false,
    val featureUnitEffectiveLeft: Boolean = false,
    val featureUnitEffectiveRight: Boolean = false,
    val featureUnitSingleChannel: Int = 0,

    val raw: String = ""
) {
    val feedbackStateName: String
        get() = when (feedbackState) {
            0 -> "NONE"
            1 -> "DISCOVERED"
            2 -> "VALIDATING"
            3 -> "LOCKED"
            4 -> "SUSPECT"
            5 -> "DEGRADED"
            6 -> "FAILED"
            else -> "UNKNOWN($feedbackState)"
        }

    val pacingModeName: String
        get() = when {
            pacingMode.isNotBlank() -> pacingMode
            pacingModeId == 0 -> "NoFeedbackFixed"
            pacingModeId == 1 -> "ExplicitFeedback"
            pacingModeId == 2 -> "FeedbackDegradedFixed"
            else -> ""
        }

    val isFeedbackDegradedFixedPacer: Boolean
        get() = pacingModeId == 2 ||
            pacingMode.equals("FeedbackDegradedFixed", ignoreCase = true)

    val isFixedNoFeedbackPacer: Boolean
        get() = pacingModeId == 0 || isFeedbackDegradedFixedPacer ||
            pacingMode.equals("NoFeedbackFixed", ignoreCase = true)
}


object UsbLearnedPolicyStore {
    private const val KEY_PREFIX = "usb_learned_"
    private const val RUNAWAY_UNPROVEN_FAILURES = 8

    /**
     * A learned USB profile is only trustworthy after at least one audible,
     * accepted run.  When a device has only failures, persisting destructive
     * fallback hints (no feedback / no clock / safe alt / force 1ms) can trap
     * it in an endless reopen loop where the DAC keeps re-locking sample rates
     * but playback never leaves PREPARING.
     */
    fun isRunawayUnprovenFallback(policy: UsbLearnedPolicy?): Boolean {
        return policy != null &&
            policy.successCount == 0 &&
            policy.failureCount >= RUNAWAY_UNPROVEN_FAILURES &&
            (policy.noFeedback || policy.noClockSet || policy.noFeatureUnit ||
                policy.force1msPacket || policy.preferSafeAlt)
    }

    fun sanitizedForPlayback(policy: UsbLearnedPolicy?): UsbLearnedPolicy? {
        if (!isRunawayUnprovenFallback(policy)) return policy
        return policy!!.copy(
            noFeedback = false,
            noClockSet = false,
            noFeatureUnit = false,
            force1msPacket = false,
            preferSafeAlt = false
        )
    }

    fun readForPlayback(deviceKey: String): UsbLearnedPolicy? = sanitizedForPlayback(read(deviceKey))

    fun resetRunawayUnprovenFallbacks(deviceKey: String, reason: String): Boolean {
        val old = read(deviceKey) ?: return false
        if (!isRunawayUnprovenFallback(old)) return false
        val reset = old.copy(
            noFeedback = false,
            noClockSet = false,
            noFeatureUnit = false,
            force1msPacket = false,
            preferSafeAlt = false,
            failureCount = 0,
            updatedAt = System.currentTimeMillis()
        )
        write(deviceKey, reset)
        return true
    }

    fun clearNoFeedbackFallback(deviceKey: String, reason: String): Boolean {
        val old = read(deviceKey) ?: return false
        if (!old.noFeedback && !old.lastGoodNoFeedback) return false
        val reset = old.copy(
            noFeedback = false,
            lastGoodNoFeedback = false,
            updatedAt = System.currentTimeMillis()
        )
        write(deviceKey, reset)
        return true
    }

    fun invalidateLastGoodIfMatches(
        deviceKey: String,
        alt: Int,
        sampleRate: Int,
        bitDepth: Int,
        subslot: Int,
        feedbackEndpoint: Int,
    ): Boolean {
        val old = read(deviceKey) ?: return false
        val matches = old.lastGoodAlt == alt &&
            old.lastGoodSampleRate == sampleRate &&
            old.lastGoodBitDepth == bitDepth &&
            old.lastGoodSubslot == subslot &&
            old.lastGoodFeedbackEndpoint == feedbackEndpoint
        if (!matches) return false

        // Older builds recorded last-good as soon as the producer accepted its first PCM block.
        // That says nothing about completed ISO traffic and can make recovery select the same
        // non-draining alternate setting forever. A stable self-test will repopulate these fields.
        write(
            deviceKey,
            old.copy(
                lastGoodAlt = 0,
                lastGoodSampleRate = 0,
                lastGoodBitDepth = 0,
                lastGoodSubslot = 0,
                lastGoodFeedbackEndpoint = 0,
                lastGoodNoFeedback = false,
                lastGoodNoClockSet = false,
                lastGoodNoFeatureUnit = false,
                lastGoodPreferSafeAlt = false,
                successCount = 0,
                updatedAt = System.currentTimeMillis(),
            ),
        )
        return true
    }

    fun keyOf(vid: Int, pid: Int, serial: String?): String {
        return buildString {
            append(vid.toString(16).padStart(4, '0'))
            append(":")
            append(pid.toString(16).padStart(4, '0'))
            if (!serial.isNullOrBlank()) {
                append(":")
                append(serial.hashCode())
            }
        }
    }

    /**
     * Learned-policy namespace. PCM, DoP and Native-DSD are
     * different transport models even when they use the same physical DAC.
     * A last-good DoP profile often runs at 352.8/384 kHz with a 24-bit
     * subslot; reusing it for normal PCM can produce noise, wrong speed or
     * an over-eager safe-alt retry. Keep the policy keys separated so only
     * an accepted run in the same transport family can seed the next one.
     */
    fun keyOfTransport(
        vid: Int,
        pid: Int,
        serial: String?,
        dsdEnabled: Boolean,
        dsdRate: Int,
        dsdTransportMode: Int
    ): String {
        val base = keyOf(vid, pid, serial)
        if (!dsdEnabled) return "$base:pcm"
        val rate = when (dsdRate) {
            64, 128, 256, 512 -> dsdRate
            else -> 64
        }
        val transport = UsbDsdTransport.fromPref(dsdTransportMode).name.lowercase()
        return "$base:dsd:$transport:$rate"
    }

    fun readForPlayback(
        vid: Int,
        pid: Int,
        serial: String?,
        dsdEnabled: Boolean,
        dsdRate: Int,
        dsdTransportMode: Int
    ): UsbLearnedPolicy? = readForPlayback(
        keyOfTransport(vid, pid, serial, dsdEnabled, dsdRate, dsdTransportMode)
    )

    fun read(deviceKey: String): UsbLearnedPolicy? {
        val noFeedback = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_noFeedback", false)
        val noClockSet = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_noClockSet", false)
        val noFeatureUnit = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_noFU", false)
        val force1msPacket = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_force1ms", false)
        val preferSafeAlt = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_safeAlt", false)
        val failureCount = AppPreferences.storage.decodeInt("${KEY_PREFIX}${deviceKey}_failures", 0)
        val successCount = AppPreferences.storage.decodeInt("${KEY_PREFIX}${deviceKey}_successes", 0)
        val lastGoodAlt = AppPreferences.storage.decodeInt("${KEY_PREFIX}${deviceKey}_alt", 0)
        val lastGoodSr = AppPreferences.storage.decodeInt("${KEY_PREFIX}${deviceKey}_sr", 0)
        val lastGoodBits = AppPreferences.storage.decodeInt("${KEY_PREFIX}${deviceKey}_bits", 0)
        val lastGoodSub = AppPreferences.storage.decodeInt("${KEY_PREFIX}${deviceKey}_sub", 0)
        val lastGoodFb = AppPreferences.storage.decodeInt("${KEY_PREFIX}${deviceKey}_fb", 0)
        val lastGoodNoFeedback = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_last_noFeedback", false)
        val lastGoodNoClockSet = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_last_noClock", false)
        val lastGoodNoFeatureUnit = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_last_noFU", false)
        val lastGoodPreferSafeAlt = AppPreferences.storage.decodeBool("${KEY_PREFIX}${deviceKey}_last_safeAlt", false)

        if (failureCount == 0 && successCount == 0) return null

        // Parse VID:PID from key
        val parts = deviceKey.split(":")
        val vid = parts.getOrNull(0)?.toIntOrNull(16) ?: 0
        val pid = parts.getOrNull(1)?.toIntOrNull(16) ?: 0

        return UsbLearnedPolicy(
            vid = vid, pid = pid,
            lastGoodAlt = lastGoodAlt,
            lastGoodSampleRate = lastGoodSr,
            lastGoodBitDepth = lastGoodBits,
            lastGoodSubslot = lastGoodSub,
            lastGoodFeedbackEndpoint = lastGoodFb,
            lastGoodNoFeedback = lastGoodNoFeedback,
            lastGoodNoClockSet = lastGoodNoClockSet,
            lastGoodNoFeatureUnit = lastGoodNoFeatureUnit,
            lastGoodPreferSafeAlt = lastGoodPreferSafeAlt,
            noFeedback = noFeedback,
            noClockSet = noClockSet,
            noFeatureUnit = noFeatureUnit,
            force1msPacket = force1msPacket,
            preferSafeAlt = preferSafeAlt,
            failureCount = failureCount,
            successCount = successCount
        )
    }

    fun write(deviceKey: String, policy: UsbLearnedPolicy) {
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_noFeedback", policy.noFeedback)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_noClockSet", policy.noClockSet)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_noFU", policy.noFeatureUnit)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_force1ms", policy.force1msPacket)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_safeAlt", policy.preferSafeAlt)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_failures", policy.failureCount)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_successes", policy.successCount)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_alt", policy.lastGoodAlt)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_sr", policy.lastGoodSampleRate)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_bits", policy.lastGoodBitDepth)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_sub", policy.lastGoodSubslot)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_fb", policy.lastGoodFeedbackEndpoint)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_last_noFeedback", policy.lastGoodNoFeedback)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_last_noClock", policy.lastGoodNoClockSet)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_last_noFU", policy.lastGoodNoFeatureUnit)
        AppPreferences.storage.encode("${KEY_PREFIX}${deviceKey}_last_safeAlt", policy.lastGoodPreferSafeAlt)
    }

    fun recordFailure(deviceKey: String, kind: UsbSilentKind) {
        // Rule: a failed probe is a symptom, not a persistent
        // device profile.  Keep the failure count for reports/backoff, but
        // do not convert failures into sticky noFeedback/noClock/safeAlt/
        // force1ms flags.  Only recordSuccess() may make future playback
        // prefer a fallback profile.
        val old = read(deviceKey) ?: UsbLearnedPolicy(vid = 0, pid = 0)
        val next = old.copy(
            failureCount = old.failureCount + 1,
            updatedAt = System.currentTimeMillis()
        )
        write(deviceKey, next)
    }

    fun recordRecoveryPlan(deviceKey: String, plan: UsbRecoveryPlan) {
        if (!plan.shouldRecordLearnedPolicy) return
        val old = read(deviceKey) ?: UsbLearnedPolicy(vid = 0, pid = 0)
        val next = old.copy(
            // Recovery plans are runtime attempts.  They may be used for the
            // pending retry in memory, but they must not become persistent
            // quirk flags unless the retry later reaches the audible gate and
            // recordSuccess() stores it as last-good.
            failureCount = old.failureCount + 1,
            updatedAt = System.currentTimeMillis()
        )
        write(deviceKey, next)
    }

    fun recordSuccess(
        deviceKey: String,
        alt: Int,
        sampleRate: Int,
        bitDepth: Int,
        subslot: Int,
        feedbackEndpoint: Int = 0,
        profile: UsbOutputProfile? = null
    ) {
        val old = read(deviceKey) ?: UsbLearnedPolicy(vid = 0, pid = 0)
        val acceptedNoFeedback = profile?.noFeedback == true
        val next = old.copy(
            lastGoodAlt = alt,
            lastGoodSampleRate = sampleRate,
            lastGoodBitDepth = bitDepth,
            lastGoodSubslot = subslot,
            lastGoodFeedbackEndpoint = feedbackEndpoint,
            lastGoodNoFeedback = acceptedNoFeedback,
            lastGoodNoClockSet = profile?.noClockSet ?: old.lastGoodNoClockSet,
            lastGoodNoFeatureUnit = profile?.noFeatureUnit ?: old.lastGoodNoFeatureUnit,
            lastGoodPreferSafeAlt = profile?.preferSafeAlt ?: old.lastGoodPreferSafeAlt,
            noFeedback = acceptedNoFeedback,
            successCount = old.successCount + 1
        )
        write(deviceKey, next)
    }
}

object UsbSelfTest {
    private enum class NativeHealthCode {
        Healthy,
        FeedbackDegradedUnderOutput,
        NoFeedbackFakePlayback,
        FixedPacerUnderFeed,
        NoFeedbackSchedulerUnderTarget,
        TransportError,
        VolumeTooLow,
        SaturatedNoOutput,
        FeedbackInvalid,
        UnderOutput,
        DecoderFeedDip,
        DecoderFeedsButNoUsbOutput,
        ClockMismatch,
        FeedbackScheduledNotCompleting,
        ScheduledNotCompleting,
        Inconclusive,
    }

    fun run(stats: UsbStatsSnapshot): UsbSelfTestResult {
        val values = if (UsbAudioEngine.isNativeLibraryLoaded()) {
            runCatching {
                UsbAudioEngine.nativeClassifyUsbStreamHealth(
                    appBytesPerSecond = stats.appInBytesPerSec,
                    completedUsbBytesPerSecond = stats.usbOutBytesPerSec,
                    scheduledUsbBytesPerSecond = stats.scheduledUsbBytesPerSec,
                    expectedBytesPerSecond = stats.expectedBytesPerSec,
                    bufferUsedBytes = stats.bufferUsedBytes,
                    bufferCapacityBytes = stats.bufferCapacityBytes,
                    underrun = stats.underrun,
                    submitError = stats.submitErr,
                    packetError = stats.packetErr,
                    transferError = stats.xferErr,
                    clockRate = stats.clockRate,
                    targetRate = stats.targetRate,
                    finalVolume = stats.finalVolume,
                    feedbackEnabled = stats.feedbackEnabled,
                    feedbackState = stats.feedbackState,
                    feedbackInvalidCount = stats.feedbackInvalidCount,
                    feedbackEmptyCount = stats.feedbackEmptyCount,
                    fixedNoFeedbackPacer = stats.isFixedNoFeedbackPacer,
                    feedbackDegradedFixedPacer = stats.isFeedbackDegradedFixedPacer,
                )
            }.getOrNull()
        } else {
            null
        }
        if (values == null || values.size < 4) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.Unknown,
                shouldRestart = false,
                shouldFallbackProfile = false,
                message = "native USB health classifier unavailable",
            )
        }
        val kind = UsbSilentKind.entries.getOrNull(values[0]) ?: UsbSilentKind.Unknown
        val code = NativeHealthCode.entries.getOrNull(values[3]) ?: NativeHealthCode.Inconclusive
        return UsbSelfTestResult(
            kind = kind,
            shouldRestart = values[1] != 0,
            shouldFallbackProfile = values[2] != 0,
            message = nativeHealthMessage(code, stats),
        )
    }

    private fun nativeHealthMessage(code: NativeHealthCode, stats: UsbStatsSnapshot): String {
        val expected = stats.expectedBytesPerSec
        val ratio = if (expected > 0L) stats.usbOutBytesPerSec.toDouble() / expected.toDouble() else 0.0
        val bytesPerSec = when {
            stats.usbOutBytesPerSec > 0L -> stats.usbOutBytesPerSec
            expected > 0L -> expected
            else -> 0L
        }
        val bufferedMs = if (bytesPerSec > 0L) stats.bufferUsedBytes * 1000L / bytesPerSec else 0L
        return when (code) {
            NativeHealthCode.Healthy ->
                "USB stream healthy: app=${stats.appInBytesPerSec} usb=${stats.usbOutBytesPerSec} expected=$expected buffered=${bufferedMs}ms"
            NativeHealthCode.FeedbackDegradedUnderOutput ->
                "Feedback-degraded fixed pacer under-output: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=$expected fbState=${stats.feedbackStateName}"
            NativeHealthCode.NoFeedbackFakePlayback ->
                "No-feedback/fixed-pacer fake playback: app=${stats.appInBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} completed=${stats.usbOutBytesPerSec} expected=$expected buffered=${bufferedMs}ms"
            NativeHealthCode.FixedPacerUnderFeed ->
                "Fixed/no-feedback transport alive; app/scheduler under-feeding: app=${stats.appInBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} completed=${stats.usbOutBytesPerSec} expected=$expected"
            NativeHealthCode.NoFeedbackSchedulerUnderTarget ->
                "No-feedback transport alive but scheduler is under target: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=$expected"
            NativeHealthCode.TransportError -> "USB transport error"
            NativeHealthCode.VolumeTooLow -> "Final volume too low: ${stats.finalVolume}"
            NativeHealthCode.SaturatedNoOutput -> "USB buffer saturated but no completed USB output"
            NativeHealthCode.FeedbackInvalid ->
                "USB feedback invalid: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} expected=$expected fbState=${stats.feedbackStateName}"
            NativeHealthCode.UnderOutput ->
                "USB under-output: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=$expected"
            NativeHealthCode.DecoderFeedDip ->
                "Decoder feed dipped while USB still drains; buffered=${bufferedMs}ms"
            NativeHealthCode.DecoderFeedsButNoUsbOutput -> "Decoder feeds PCM but USB out=0"
            NativeHealthCode.ClockMismatch ->
                "Clock mismatch: device=${stats.clockRate}, target=${stats.targetRate}"
            NativeHealthCode.FeedbackScheduledNotCompleting ->
                "USB feedback path suspect: completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=$expected"
            NativeHealthCode.ScheduledNotCompleting ->
                "USB scheduled but not completing: completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=$expected"
            NativeHealthCode.Inconclusive ->
                "USB self-test inconclusive: app=${stats.appInBytesPerSec} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=$expected"
        }
    }
}
