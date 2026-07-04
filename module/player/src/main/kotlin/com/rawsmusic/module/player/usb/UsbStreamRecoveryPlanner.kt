package com.rawsmusic.module.player.usb

/**
 * Recovery planning layer.
 *
 * The lower layers report generic stream health symptoms (under-output,
 * clock mismatch, feedback suspect, unsafe Feature Unit, transport error).
 * This planner converts those symptoms into profile-dimension fallbacks.  It
 * intentionally never switches on VID/PID/product name: device-specific
 * outcomes are learned as profile facts, not hard-coded quirks.
 */
enum class UsbRecoveryAction {
    None,
    Observe,
    RebuildSameProfile,
    RetryLastGoodProfile,
    RetryWithoutFeedback,
    RetryWithoutClockSet,
    RetryWithoutFeatureUnit,
    RetrySafeAlt,
    FullReopen,
    AndroidFallback
}

data class UsbRecoveryPlan(
    val action: UsbRecoveryAction,
    val reason: UsbSilentKind,
    val message: String,
    val disableFeedback: Boolean = false,
    val disableClockSet: Boolean = false,
    val disableFeatureUnit: Boolean = false,
    val force1msPacket: Boolean = false,
    val preferSafeAlt: Boolean = false,
    val forceFullReopen: Boolean = false,
    val shouldRecordLearnedPolicy: Boolean = false,
    val preferLastGoodProfile: Boolean = false
) {
    val requiresProfileRestart: Boolean
        get() = action != UsbRecoveryAction.None && action != UsbRecoveryAction.Observe
}

object UsbStreamRecoveryPlanner {
    private fun outputRatio(stats: UsbStatsSnapshot?): Double {
        val expected = stats?.expectedBytesPerSec ?: return 0.0
        val usb = stats.usbOutBytesPerSec
        if (expected <= 0L || usb <= 0L) return 0.0
        return usb.toDouble() / expected.toDouble()
    }

    private fun isAppOrSchedulerUnderFeeding(stats: UsbStatsSnapshot?): Boolean {
        val expected = stats?.expectedBytesPerSec ?: return false
        if (expected <= 0L) return false
        val appLow = stats.appInBytesPerSec in 0L until (expected * 75L / 100L)
        val scheduledLow = stats.scheduledUsbBytesPerSec in 0L until (expected * 75L / 100L)
        val noTransportErrors = stats.underrun == 0 &&
            stats.submitErr == 0 &&
            stats.packetErr == 0 &&
            stats.xferErr == 0
        return appLow && scheduledLow && noTransportErrors
    }

    fun plan(
        kind: UsbSilentKind,
        stats: UsbStatsSnapshot?,
        profile: UsbOutputProfile?,
        detail: String = ""
    ): UsbRecoveryPlan {
        if (kind == UsbSilentKind.FeedbackInvalid && stats?.feedbackEnabled != true && stats?.isFeedbackDegradedFixedPacer != true) {
            return plan(
                kind = UsbSilentKind.UsbNotOutputting,
                stats = stats,
                profile = profile,
                detail = detail.ifBlank { "feedback inactive at runtime; treating as under-output" }
            )
        }
        if ((kind == UsbSilentKind.UsbNotOutputting || kind == UsbSilentKind.Unknown) &&
            stats?.isFixedNoFeedbackPacer == true &&
            isAppOrSchedulerUnderFeeding(stats)
        ) {
            return UsbRecoveryPlan(
                action = UsbRecoveryAction.Observe,
                reason = UsbSilentKind.DecoderNotFeeding,
                message = "fixed-pacer output is alive but app/scheduler is under-feeding; do not change USB profile" +
                    detail.prependIfNotBlank(),
                shouldRecordLearnedPolicy = false
            )
        }
        val ratio = outputRatio(stats)
        val msgSuffix = buildString {
            if (detail.isNotBlank()) append(detail)
            if (stats != null) {
                if (isNotEmpty()) append("; ")
                append("app=${stats.appInBytesPerSec} usb=${stats.usbOutBytesPerSec} expected=${stats.expectedBytesPerSec} ratio=${"%.3f".format(ratio)} feedback=${stats.feedbackEnabled}")
            }
        }

        return when (kind) {
            UsbSilentKind.None -> UsbRecoveryPlan(
                action = UsbRecoveryAction.None,
                reason = kind,
                message = "stream healthy"
            )

            UsbSilentKind.DecoderNotFeeding -> UsbRecoveryPlan(
                action = UsbRecoveryAction.Observe,
                reason = kind,
                message = "decoder feed dipped; USB still owns the stream${msgSuffix.prependIfNotBlank()}",
                shouldRecordLearnedPolicy = false
            )

            UsbSilentKind.VolumeTooLow -> UsbRecoveryPlan(
                action = UsbRecoveryAction.Observe,
                reason = kind,
                message = "volume path is low; do not change USB profile${msgSuffix.prependIfNotBlank()}",
                shouldRecordLearnedPolicy = false
            )

            UsbSilentKind.ClockMismatch -> UsbRecoveryPlan(
                action = UsbRecoveryAction.RetryWithoutClockSet,
                reason = kind,
                message = "clock commit mismatch; retry a safer profile without aggressive clock writes${msgSuffix.prependIfNotBlank()}",
                disableClockSet = true,
                preferSafeAlt = true,
                preferLastGoodProfile = (profile?.lastGoodAlt ?: 0) > 0,
                forceFullReopen = true,
                shouldRecordLearnedPolicy = true
            )

            UsbSilentKind.FeedbackInvalid -> UsbRecoveryPlan(
                action = UsbRecoveryAction.RetryWithoutFeedback,
                reason = kind,
                message = "feedback path is suspect; retry same stream profile with feedback disabled${msgSuffix.prependIfNotBlank()}",
                disableFeedback = true,
                force1msPacket = false,
                preferSafeAlt = true,
                preferLastGoodProfile = false,
                forceFullReopen = false,
                shouldRecordLearnedPolicy = true
            )

            UsbSilentKind.FeatureUnitUnsafe -> UsbRecoveryPlan(
                action = UsbRecoveryAction.RetryWithoutFeatureUnit,
                reason = kind,
                message = "hardware Feature Unit failed validation; retry current session without Feature Unit${msgSuffix.prependIfNotBlank()}",
                disableFeatureUnit = true,
                forceFullReopen = true,
                // Do not permanently blacklist hardware volume.
                // controllers and lets them be retried; a failed readback on one
                // run should not override the user's explicit HW-volume choice.
                shouldRecordLearnedPolicy = false
            )

            UsbSilentKind.TransportError -> UsbRecoveryPlan(
                action = if ((profile?.lastGoodAlt ?: 0) > 0) UsbRecoveryAction.RetryLastGoodProfile else UsbRecoveryAction.FullReopen,
                reason = kind,
                message = "USB transport error; full reopen required${msgSuffix.prependIfNotBlank()}",
                disableFeedback = false,
                force1msPacket = false,
                preferSafeAlt = true,
                preferLastGoodProfile = (profile?.lastGoodAlt ?: 0) > 0,
                forceFullReopen = true,
                shouldRecordLearnedPolicy = true
            )

            UsbSilentKind.UsbNotOutputting -> {
                val feedbackLikely = stats?.feedbackEnabled == true && profile?.noFeedback != true
                val severeFixedNoFeedbackUnderOutput =
                    stats?.isFixedNoFeedbackPacer == true &&
                        ratio in 0.0..<0.20 &&
                        (stats.scheduledUsbBytesPerSec > 0L || stats.usbOutBytesPerSec > 0L)
                if (feedbackLikely) {
                    UsbRecoveryPlan(
                        action = UsbRecoveryAction.FullReopen,
                        reason = kind,
                        message = "USB is under-outputting with explicit feedback available; keep feedback and full-reopen the same format${msgSuffix.prependIfNotBlank()}",
                        disableFeedback = false,
                        force1msPacket = false,
                        preferSafeAlt = true,
                        preferLastGoodProfile = false,
                        forceFullReopen = true,
                        shouldRecordLearnedPolicy = true
                    )
                } else if (severeFixedNoFeedbackUnderOutput) {
                    UsbRecoveryPlan(
                        action = UsbRecoveryAction.FullReopen,
                        reason = kind,
                        message = "fixed/no-feedback pacer is alive but not draining; clear no-feedback fallback and full-reopen${msgSuffix.prependIfNotBlank()}",
                        disableFeedback = false,
                        force1msPacket = false,
                        preferSafeAlt = true,
                        preferLastGoodProfile = false,
                        forceFullReopen = true,
                        shouldRecordLearnedPolicy = true
                    )
                } else {
                    UsbRecoveryPlan(
                        action = if ((profile?.lastGoodAlt ?: 0) > 0) UsbRecoveryAction.RetryLastGoodProfile else UsbRecoveryAction.RebuildSameProfile,
                        reason = kind,
                        message = "USB is not draining; rebuild the same modeled profile before changing alt/clock${msgSuffix.prependIfNotBlank()}",
                        // Otherwise an accepted no-feedback stream can be rebuilt with
                        // feedback enabled again, causing the same endpoint to degrade
                        // repeatedly instead of preserving the modeled transport.
                        disableFeedback = profile?.noFeedback == true,
                        force1msPacket = false,
                        preferSafeAlt = false,
                        preferLastGoodProfile = (profile?.lastGoodAlt ?: 0) > 0,
                        forceFullReopen = false,
                        shouldRecordLearnedPolicy = true
                    )
                }
            }

            UsbSilentKind.Unknown -> UsbRecoveryPlan(
                action = UsbRecoveryAction.RebuildSameProfile,
                reason = kind,
                message = "unknown USB stream failure; rebuild current profile${msgSuffix.prependIfNotBlank()}",
                forceFullReopen = true,
                shouldRecordLearnedPolicy = false
            )
        }
    }

    fun planFromHighWaterStall(
        stats: UsbStatsSnapshot?,
        profile: UsbOutputProfile?,
        stalledMs: Long,
        nativeBufferBytes: Int,
        highWaterBytes: Int,
        ringAvailableBytes: Int
    ): UsbRecoveryPlan {
        val kind = if (stats?.feedbackEnabled == true && profile?.noFeedback != true) {
            UsbSilentKind.FeedbackInvalid
        } else {
            UsbSilentKind.UsbNotOutputting
        }
        return plan(
            kind = kind,
            stats = stats,
            profile = profile,
            detail = "high-water stall ${stalledMs}ms native=$nativeBufferBytes high=$highWaterBytes ring=$ringAvailableBytes"
        )
    }

    fun planFromFeatureUnitPolicy(policyString: String): UsbRecoveryPlan {
        val unsafe = policyString.contains("unsafe", ignoreCase = true) ||
            policyString.contains("not-present", ignoreCase = true) ||
            policyString.contains("disabled-by-policy", ignoreCase = true)
        return if (unsafe) {
            UsbRecoveryPlan(
                action = UsbRecoveryAction.RetryWithoutFeatureUnit,
                reason = UsbSilentKind.FeatureUnitUnsafe,
                message = "FeatureUnitPolicy rejected hardware volume: $policyString",
                disableFeatureUnit = true,
                forceFullReopen = true,
                shouldRecordLearnedPolicy = true
            )
        } else {
            UsbRecoveryPlan(
                action = UsbRecoveryAction.None,
                reason = UsbSilentKind.None,
                message = "FeatureUnitPolicy accepted: $policyString"
            )
        }
    }
}

private fun String.prependIfNotBlank(): String = if (isBlank()) "" else ": $this"
