package com.rawsmusic.module.player.usb

/**
 * Public recovery result used by PlayerController / profile persistence.
 *
 * The decision itself is native-owned. Kotlin keeps only the transport-neutral result model so
 * Android lifecycle code can log, persist learned profile facts, and request a reprepare/reopen.
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
    val preferLastGoodProfile: Boolean = false,
    val nativeRecoveryHandle: Long = 0L,
    val nativeRecoveryToken: Long = 0L,
) {
    val requiresProfileRestart: Boolean
        get() = action != UsbRecoveryAction.None && action != UsbRecoveryAction.Observe
}

object UsbStreamRecoveryPlanner {
    private const val FLAG_DISABLE_FEEDBACK = 1 shl 0
    private const val FLAG_DISABLE_CLOCK_SET = 1 shl 1
    private const val FLAG_DISABLE_FEATURE_UNIT = 1 shl 2
    private const val FLAG_FORCE_1MS_PACKET = 1 shl 3
    private const val FLAG_PREFER_SAFE_ALT = 1 shl 4
    private const val FLAG_FORCE_FULL_REOPEN = 1 shl 5
    private const val FLAG_RECORD_LEARNED_POLICY = 1 shl 6
    private const val FLAG_PREFER_LAST_GOOD = 1 shl 7

    fun plan(
        kind: UsbSilentKind,
        stats: UsbStatsSnapshot?,
        profile: UsbOutputProfile?,
        detail: String = ""
    ): UsbRecoveryPlan {
        val native = if (UsbAudioEngine.isNativeLibraryLoaded()) {
            runCatching {
                UsbAudioEngine.nativePlanUsbRecovery(
                    kind = kind.ordinal,
                    appBytesPerSecond = stats?.appInBytesPerSec ?: 0L,
                    completedUsbBytesPerSecond = stats?.usbOutBytesPerSec ?: 0L,
                    scheduledUsbBytesPerSecond = stats?.scheduledUsbBytesPerSec ?: 0L,
                    expectedBytesPerSecond = stats?.expectedBytesPerSec ?: 0L,
                    underrun = stats?.underrun ?: 0,
                    submitError = stats?.submitErr ?: 0,
                    packetError = stats?.packetErr ?: 0,
                    transferError = stats?.xferErr ?: 0,
                    feedbackEnabled = stats?.feedbackEnabled == true,
                    fixedNoFeedbackPacer = stats?.isFixedNoFeedbackPacer == true,
                    feedbackDegradedFixedPacer = stats?.isFeedbackDegradedFixedPacer == true,
                    profileNoFeedback = profile?.noFeedback == true,
                    lastGoodAlt = profile?.lastGoodAlt ?: 0,
                    runtimeLivenessStall = kind == UsbSilentKind.TransportError &&
                        detail.startsWith("runtime_liveness_stall"),
                )
            }.getOrNull()
        } else {
            null
        }
        return attachNativeRecoveryToken(decodeNativeDecision(native, kind, stats, detail))
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
        val native = if (UsbAudioEngine.isNativeLibraryLoaded()) {
            runCatching { UsbAudioEngine.nativePlanUsbFeatureUnitRecovery(unsafe) }.getOrNull()
        } else {
            null
        }
        return attachNativeRecoveryToken(decodeNativeDecision(
            values = native,
            requestedKind = if (unsafe) UsbSilentKind.FeatureUnitUnsafe else UsbSilentKind.None,
            stats = null,
            detail = "FeatureUnitPolicy: $policyString",
        ))
    }

    private fun attachNativeRecoveryToken(plan: UsbRecoveryPlan): UsbRecoveryPlan {
        if (!plan.requiresProfileRestart || !UsbAudioEngine.isNativeLibraryLoaded()) return plan
        val handle = UsbAudioEngine.currentHandle
        if (handle == 0L) return plan
        val token = runCatching { UsbAudioEngine.nativeIssueUsbRecoveryToken(handle) }.getOrDefault(0L)
        if (token <= 0L) return plan
        return plan.copy(nativeRecoveryHandle = handle, nativeRecoveryToken = token)
    }

    fun isNativeRecoveryTokenCurrent(plan: UsbRecoveryPlan): Boolean {
        if (plan.nativeRecoveryHandle == 0L || plan.nativeRecoveryToken == 0L) return false
        if (!UsbAudioEngine.isNativeLibraryLoaded()) return false
        return runCatching {
            UsbAudioEngine.nativeIsUsbRecoveryTokenCurrent(
                plan.nativeRecoveryHandle,
                plan.nativeRecoveryToken,
            )
        }.getOrDefault(false)
    }

    private fun decodeNativeDecision(
        values: IntArray?,
        requestedKind: UsbSilentKind,
        stats: UsbStatsSnapshot?,
        detail: String,
    ): UsbRecoveryPlan {
        if (values == null || values.size < 3) {
            return conservativeFallback(requestedKind, detail)
        }
        val action = UsbRecoveryAction.entries.getOrNull(values[0]) ?: UsbRecoveryAction.FullReopen
        val reason = UsbSilentKind.entries.getOrNull(values[1]) ?: requestedKind
        val flags = values[2]
        return UsbRecoveryPlan(
            action = action,
            reason = reason,
            message = recoveryMessage(action, reason, stats, detail),
            disableFeedback = flags and FLAG_DISABLE_FEEDBACK != 0,
            disableClockSet = flags and FLAG_DISABLE_CLOCK_SET != 0,
            disableFeatureUnit = flags and FLAG_DISABLE_FEATURE_UNIT != 0,
            force1msPacket = flags and FLAG_FORCE_1MS_PACKET != 0,
            preferSafeAlt = flags and FLAG_PREFER_SAFE_ALT != 0,
            forceFullReopen = flags and FLAG_FORCE_FULL_REOPEN != 0,
            shouldRecordLearnedPolicy = flags and FLAG_RECORD_LEARNED_POLICY != 0,
            preferLastGoodProfile = flags and FLAG_PREFER_LAST_GOOD != 0,
        )
    }

    /** Native unavailable is not a reason to learn destructive profile fallbacks. */
    private fun conservativeFallback(kind: UsbSilentKind, detail: String): UsbRecoveryPlan = when (kind) {
        UsbSilentKind.None -> UsbRecoveryPlan(UsbRecoveryAction.None, kind, "native recovery policy unavailable")
        UsbSilentKind.DecoderNotFeeding,
        UsbSilentKind.VolumeTooLow -> UsbRecoveryPlan(
            UsbRecoveryAction.Observe,
            kind,
            "native recovery policy unavailable; observe${detail.prependIfNotBlank()}",
        )
        else -> UsbRecoveryPlan(
            UsbRecoveryAction.FullReopen,
            kind,
            "native recovery policy unavailable; conservative full reopen${detail.prependIfNotBlank()}",
            forceFullReopen = true,
        )
    }

    private fun recoveryMessage(
        action: UsbRecoveryAction,
        reason: UsbSilentKind,
        stats: UsbStatsSnapshot?,
        detail: String,
    ): String {
        val prefix = when (action) {
            UsbRecoveryAction.None -> "stream healthy"
            UsbRecoveryAction.Observe -> "observe USB stream without profile mutation"
            UsbRecoveryAction.RebuildSameProfile -> "rebuild current USB profile"
            UsbRecoveryAction.RetryLastGoodProfile -> "retry last-good USB profile"
            UsbRecoveryAction.RetryWithoutFeedback -> "retry USB profile without explicit feedback"
            UsbRecoveryAction.RetryWithoutClockSet -> "retry USB profile without clock write"
            UsbRecoveryAction.RetryWithoutFeatureUnit -> "retry USB session without Feature Unit"
            UsbRecoveryAction.RetrySafeAlt -> "retry safer USB alt setting"
            UsbRecoveryAction.FullReopen -> "full-reopen USB session"
            UsbRecoveryAction.AndroidFallback -> "fall back to Android audio"
        }
        val runtime = if (stats != null && stats.expectedBytesPerSec > 0L) {
            val ratio = stats.usbOutBytesPerSec.toDouble() / stats.expectedBytesPerSec.toDouble()
            "; app=${stats.appInBytesPerSec} usb=${stats.usbOutBytesPerSec} " +
                "expected=${stats.expectedBytesPerSec} ratio=${"%.3f".format(ratio)} feedback=${stats.feedbackEnabled}"
        } else {
            ""
        }
        return "$prefix; reason=$reason${detail.prependIfNotBlank()}$runtime"
    }
}

private fun String.prependIfNotBlank(): String = if (isBlank()) "" else ": $this"
