package com.rawsmusic.module.player.usb

/**
 * Decides whether a track-format boundary may reuse the already-open native USB device.
 *
 * This deliberately models the physical-device lifetime separately from the streaming lifetime:
 * PCM rate/stream-profile changes may reconfigure the current physical session in place, while
 * DSD, policy, feedback-policy, or poisoned-session changes still require the conservative reopen.
 */
internal enum class UsbPersistentSessionAction {
    KEEP_CURRENT_STREAM,
    RECONFIGURE_RATE_IN_PLACE,
    RECONFIGURE_STREAM_PROFILE_IN_PLACE,
    FULL_REOPEN,
}

internal data class UsbPersistentSessionDecision(
    val action: UsbPersistentSessionAction,
    val reason: String,
) {
    val preservePhysicalSession: Boolean
        get() = action != UsbPersistentSessionAction.FULL_REOPEN
}

internal data class UsbRateChangeControlPlane(
    /** Ordinary rate switches never rediscover clock ranges or Feature Units. */
    val minimumControlPlane: Boolean,
    /** Optional post-SET_CUR Clock Validity read; this is not a sample-rate GET_CUR. */
    val verifyClockReadback: Boolean,
    /** Reserved for device quirks. The normal persistent path keeps the current AS alt active. */
    val forceAltZeroBeforeRateChange: Boolean,
)

internal object UsbPersistentSessionPolicy {
    fun decide(
        current: UsbAudioConfig?,
        target: UsbAudioConfig,
        currentSourceRate: Int,
        targetSourceRate: Int,
        currentSourceBits: Int,
        targetSourceBits: Int,
        currentDsdSessionKey: String?,
        targetDsdSessionKey: String?,
        liveSessionHealthy: Boolean,
        policyChanged: Boolean,
        feedbackPolicyRequiresReinit: Boolean,
    ): UsbPersistentSessionDecision {
        if (!liveSessionHealthy || current == null) {
            return UsbPersistentSessionDecision(
                UsbPersistentSessionAction.FULL_REOPEN,
                "no_healthy_live_session",
            )
        }
        if (currentDsdSessionKey != null || targetDsdSessionKey != null) {
            return UsbPersistentSessionDecision(
                UsbPersistentSessionAction.FULL_REOPEN,
                "dsd_session_boundary",
            )
        }
        if (policyChanged) {
            return UsbPersistentSessionDecision(
                UsbPersistentSessionAction.FULL_REOPEN,
                "native_policy_changed",
            )
        }
        if (feedbackPolicyRequiresReinit) {
            return UsbPersistentSessionDecision(
                UsbPersistentSessionAction.FULL_REOPEN,
                "feedback_policy_changed",
            )
        }

        val sameWireGeometry =
            current.bits == target.bits &&
                current.channels == target.channels &&
                current.subslot == target.subslot &&
                current.sourceBits == target.sourceBits &&
                currentSourceBits == targetSourceBits
        val sameRateContract =
            current.sampleRate == target.sampleRate &&
                currentSourceRate == targetSourceRate
        return if (sameRateContract && sameWireGeometry) {
            UsbPersistentSessionDecision(
                UsbPersistentSessionAction.KEEP_CURRENT_STREAM,
                "same_pcm_rate_contract",
            )
        } else if (sameWireGeometry) {
            UsbPersistentSessionDecision(
                UsbPersistentSessionAction.RECONFIGURE_RATE_IN_PLACE,
                "pcm_rate_only",
            )
        } else {
            UsbPersistentSessionDecision(
                UsbPersistentSessionAction.RECONFIGURE_STREAM_PROFILE_IN_PLACE,
                "pcm_stream_profile_change",
            )
        }
    }

    fun persistentControlPlane(): UsbRateChangeControlPlane =
        UsbRateChangeControlPlane(
            minimumControlPlane = true,
            // The reference USB control plane performs one validity read immediately after
            // SET_CUR and does not fail the rate change based on the validity result.
            verifyClockReadback = true,
            // The UAC2 rate-change path then drops the active playback AS interface to alt0.
            // Native owns the actual ordering; this flag documents the control-plane contract.
            forceAltZeroBeforeRateChange = true,
        )
}
