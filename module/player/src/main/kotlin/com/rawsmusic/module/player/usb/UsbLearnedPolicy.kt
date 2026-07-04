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
    private fun outputRatio(stats: UsbStatsSnapshot): Double {
        val expected = stats.expectedBytesPerSec
        val usbOut = stats.usbOutBytesPerSec
        if (expected <= 0L || usbOut <= 0L) return 0.0
        return usbOut.toDouble() / expected.toDouble()
    }

    private fun bufferedMs(stats: UsbStatsSnapshot): Long {
        val bytesPerSec = when {
            stats.usbOutBytesPerSec > 0 -> stats.usbOutBytesPerSec
            stats.expectedBytesPerSec > 0 -> stats.expectedBytesPerSec
            else -> 0L
        }
        if (bytesPerSec <= 0L) return 0L
        return (stats.bufferUsedBytes * 1000L) / bytesPerSec
    }

    private fun noHardTransportErrors(stats: UsbStatsSnapshot): Boolean {
        return stats.submitErr == 0 &&
            stats.packetErr == 0 &&
            stats.xferErr == 0
    }

    private fun noTransportErrors(stats: UsbStatsSnapshot): Boolean {
        return stats.underrun == 0 && noHardTransportErrors(stats)
    }

    private fun appOrSchedulerUnderFeeds(stats: UsbStatsSnapshot): Boolean {
        val expected = stats.expectedBytesPerSec
        if (expected <= 0L) return false
        val appLow = stats.appInBytesPerSec in 0L until (expected * 75L / 100L)
        val scheduledLow = stats.scheduledUsbBytesPerSec in 0L until (expected * 75L / 100L)
        // Underrun is a symptom of producer/scheduler starvation on MIUI and
        // no-feedback DACs. It is not a hard USB transport error like submit,
        // packet, or transfer failure, so do not use it to escalate to profile
        // recovery.
        return appLow && scheduledLow && noHardTransportErrors(stats)
    }

    private fun noFeedbackFakePlayback(stats: UsbStatsSnapshot): Boolean {
        val expected = stats.expectedBytesPerSec
        if (expected <= 0L) return false
        if (!(stats.isFixedNoFeedbackPacer || !stats.feedbackEnabled)) return false
        if (!noHardTransportErrors(stats)) return false
        val bufferedEnough = stats.bufferUsedBytes >= expected * 160L / 1000L
        val appCollapsed = stats.appInBytesPerSec in 0L until (expected * 75L / 100L)
        val scheduledCollapsed = stats.scheduledUsbBytesPerSec in 0L until (expected * 60L / 100L)
        val completedCollapsed = stats.usbOutBytesPerSec in 0L until (expected * 35L / 100L)
        return bufferedEnough && appCollapsed && scheduledCollapsed && completedCollapsed
    }


    private fun feedbackDegradedFixedUnderOutput(stats: UsbStatsSnapshot): Boolean {
        val expected = stats.expectedBytesPerSec
        if (expected <= 0L) return false
        val degraded = stats.isFeedbackDegradedFixedPacer ||
            stats.feedbackState >= 4 ||
            stats.feedbackInvalidCount > 0 ||
            stats.feedbackEmptyCount > 0
        if (!degraded) return false
        if (!noHardTransportErrors(stats)) return false
        val ratio = outputRatio(stats)
        // Native has already proven the explicit feedback endpoint is unusable
        // and switched to a fixed pacer.  If OUT completion then collapses,
        // treating it as "decoder under-feed" can park no-feedback-capable
        // devices forever after the first audible second.  the model drops the
        // bad feedback endpoint and retries the same stream as no-feedback.
        val scheduledLow = stats.scheduledUsbBytesPerSec <= 0L ||
            stats.scheduledUsbBytesPerSec < expected * 70L / 100L
        return stats.usbOutBytesPerSec >= 0L && ratio < 0.70 && scheduledLow
    }

    private fun looksHealthy(stats: UsbStatsSnapshot, bufferedMs: Long): Boolean {
        val usbNearExpected = outputRatio(stats) in 0.70..1.30
        val bufferHealthy = stats.bufferCapacityBytes <= 0L || bufferedMs in 20L..4000L
        return stats.appInBytesPerSec > 0 &&
            usbNearExpected &&
            bufferHealthy &&
            noHardTransportErrors(stats)
    }

    private fun feedbackSuspect(stats: UsbStatsSnapshot): Boolean {
        // Once native has already dropped the feedback endpoint and switched to
        // PI pacing, later under-output must no longer be treated as
        // "feedback invalid". Otherwise we keep retrying the same no-feedback
        // fallback in a loop.
        if (!stats.feedbackEnabled || stats.isFixedNoFeedbackPacer) return false
        return stats.feedbackState in 4..6 ||
            stats.feedbackInvalidCount > 0 ||
            stats.feedbackEmptyCount > 0
    }

    fun run(stats: UsbStatsSnapshot): UsbSelfTestResult {
        val bufferedMs = bufferedMs(stats)
        val ratio = outputRatio(stats)
        if (looksHealthy(stats, bufferedMs)) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.None,
                shouldRestart = false,
                shouldFallbackProfile = false,
                message = "USB stream healthy: app=${stats.appInBytesPerSec} usb=${stats.usbOutBytesPerSec} expected=${stats.expectedBytesPerSec} buffered=${bufferedMs}ms"
            )
        }

        // If the native side already degraded an explicit feedback endpoint,
        // under-output is a stream-config symptom, not a decoder starvation
        // symptom.  Retry the same profile with feedback disabled before the
        // Kotlin feeder classifies low app/scheduled rates as harmless.
        if (feedbackDegradedFixedUnderOutput(stats)) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.FeedbackInvalid,
                shouldRestart = true,
                shouldFallbackProfile = true,
                message = "Feedback-degraded fixed pacer is under-outputting: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=${stats.expectedBytesPerSec} fbState=${stats.feedbackStateName} fbInvalid=${stats.feedbackInvalidCount} fbEmpty=${stats.feedbackEmptyCount}"
            )
        }

        if (noFeedbackFakePlayback(stats)) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.UsbNotOutputting,
                shouldRestart = true,
                shouldFallbackProfile = true,
                message = "No-feedback/fixed-pacer fake playback: native buffer parked but transport collapsed: app=${stats.appInBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} completed=${stats.usbOutBytesPerSec} expected=${stats.expectedBytesPerSec} buffered=${bufferedMs}ms"
            )
        }

        // Fixed/no-feedback path: a low app feed or low scheduled
        // rate during pause, cutover, warm pause, or Android scheduling jitter
        // is not a DAC/profile failure.  Do not persist safeAlt/noFb hints and
        // do not request a profile restart unless the app is actually feeding
        // close to the target rate.
        if ((stats.isFixedNoFeedbackPacer || !stats.feedbackEnabled) &&
            stats.usbOutBytesPerSec > 0 &&
            appOrSchedulerUnderFeeds(stats)
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.DecoderNotFeeding,
                shouldRestart = false,
                shouldFallbackProfile = false,
                message = "No-feedback/fixed-pacer stream is alive but app/scheduler under-feeds: app=${stats.appInBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=${stats.expectedBytesPerSec} completed=${stats.usbOutBytesPerSec}"
            )
        }

        if (!stats.feedbackEnabled &&
            stats.usbOutBytesPerSec > 0 &&
            stats.scheduledUsbBytesPerSec > 0 &&
            stats.expectedBytesPerSec > 0 &&
            ratio in 0.0..<0.70 &&
            noHardTransportErrors(stats)
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.DecoderNotFeeding,
                shouldRestart = false,
                shouldFallbackProfile = false,
                message = "No-feedback stream is alive but Android scheduler is under target: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=${stats.expectedBytesPerSec} underrun=${stats.underrun}"
            )
        }

        if (stats.xferErr > 0 || stats.submitErr > 0) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.TransportError,
                shouldRestart = true, shouldFallbackProfile = true,
                message = "USB transport error"
            )
        }
        if (stats.finalVolume >= 0.0001f && stats.finalVolume < 0.03f) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.VolumeTooLow,
                shouldRestart = false, shouldFallbackProfile = false,
                message = "Final volume too low: ${stats.finalVolume}"
            )
        }
        if (stats.usbOutBytesPerSec <= 0 &&
            stats.bufferCapacityBytes > 0 &&
            stats.bufferUsedBytes * 100 >= stats.bufferCapacityBytes * 60
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.UsbNotOutputting,
                shouldRestart = true, shouldFallbackProfile = true,
                message = "USB buffer is saturated but no USB out traffic"
            )
        }
        if (!appOrSchedulerUnderFeeds(stats) &&
            (stats.appInBytesPerSec > 0 || stats.scheduledUsbBytesPerSec > stats.expectedBytesPerSec / 2) &&
            stats.usbOutBytesPerSec > 0 &&
            stats.expectedBytesPerSec > 0 &&
            ratio in 0.0..<0.70 &&
            feedbackSuspect(stats)
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.FeedbackInvalid,
                shouldRestart = true,
                shouldFallbackProfile = true,
                message = "USB feedback invalid: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} expected=${stats.expectedBytesPerSec} fbState=${stats.feedbackStateName} fbInvalid=${stats.feedbackInvalidCount} fbEmpty=${stats.feedbackEmptyCount}"
            )
        }
        if (!appOrSchedulerUnderFeeds(stats) &&
            (stats.appInBytesPerSec > 0 || stats.scheduledUsbBytesPerSec > stats.expectedBytesPerSec / 2) &&
            stats.usbOutBytesPerSec > 0 &&
            stats.expectedBytesPerSec > 0 &&
            ratio in 0.0..<0.55
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.UsbNotOutputting,
                shouldRestart = true,
                shouldFallbackProfile = true,
                message = "USB under-output: ratio=${"%.3f".format(ratio)} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=${stats.expectedBytesPerSec} buffered=${bufferedMs}ms feedback=${stats.feedbackEnabled} pacing=${stats.pacingMode}"
            )
        }
        if (stats.usbOutBytesPerSec > 0 && stats.appInBytesPerSec <= 0) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.DecoderNotFeeding,
                shouldRestart = false, shouldFallbackProfile = false,
                message = "Decoder feed dipped to 0, but USB is still outputting with ${bufferedMs}ms buffered"
            )
        }
        if (!appOrSchedulerUnderFeeds(stats) && stats.appInBytesPerSec > 0 && stats.usbOutBytesPerSec <= 0) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.UsbNotOutputting,
                shouldRestart = true, shouldFallbackProfile = true,
                message = "Decoder feeds PCM but USB out=0"
            )
        }
        if (stats.targetRate > 0 && stats.clockRate > 0 &&
            kotlin.math.abs(stats.clockRate - stats.targetRate) > 100
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.ClockMismatch,
                shouldRestart = true, shouldFallbackProfile = true,
                message = "Clock mismatch: device=${stats.clockRate}, target=${stats.targetRate}"
            )
        }
        if (!appOrSchedulerUnderFeeds(stats) &&
            stats.expectedBytesPerSec > 0 &&
            stats.scheduledUsbBytesPerSec > stats.expectedBytesPerSec / 2 &&
            stats.usbOutBytesPerSec < stats.expectedBytesPerSec * 70 / 100 &&
            feedbackSuspect(stats)
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.FeedbackInvalid,
                shouldRestart = true, shouldFallbackProfile = true,
                message = "USB feedback path is suspect while scheduled traffic is not completing: completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=${stats.expectedBytesPerSec} fbState=${stats.feedbackStateName}"
            )
        }
        if (!appOrSchedulerUnderFeeds(stats) &&
            stats.expectedBytesPerSec > 0 &&
            stats.scheduledUsbBytesPerSec > stats.expectedBytesPerSec / 2 &&
            stats.usbOutBytesPerSec < stats.expectedBytesPerSec * 55 / 100
        ) {
            return UsbSelfTestResult(
                kind = UsbSilentKind.UsbNotOutputting,
                shouldRestart = true,
                shouldFallbackProfile = true,
                message = "USB scheduled but not completing: completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=${stats.expectedBytesPerSec}"
            )
        }

        return UsbSelfTestResult(
            kind = UsbSilentKind.Unknown,
            shouldRestart = false, shouldFallbackProfile = false,
            message = "USB self-test inconclusive: app=${stats.appInBytesPerSec} completed=${stats.usbOutBytesPerSec} scheduled=${stats.scheduledUsbBytesPerSec} expected=${stats.expectedBytesPerSec} fbState=${stats.feedbackStateName} pacing=${stats.pacingMode}"
        )
    }
}
