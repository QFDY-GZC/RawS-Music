package com.rawsmusic.module.player.usb

import com.rawsmusic.module.data.prefs.UsbBitPerfectMode

/** Pure per-track policy for "bit-perfect when possible" semantics. */
internal object UsbBitPerfectModePolicy {
    data class Decision(
        val effectiveBitPerfect: Boolean,
        val refusePlayback: Boolean = false,
        val reason: String,
    )

    fun decidePcmTrack(
        mode: UsbBitPerfectMode,
        capabilities: UsbDeviceAudioCapabilities?,
        sourceSampleRate: Int,
        sourceBits: Int,
        sourceChannels: Int,
        sourceIsDsd: Boolean,
        pcmToDsdActive: Boolean,
    ): Decision {
        if (mode == UsbBitPerfectMode.OFF) return Decision(false, reason = "policy_off")
        if (sourceIsDsd) return Decision(false, reason = "source_dsd_uses_dsd_policy")
        if (pcmToDsdActive) return Decision(false, reason = "pcm_to_dsd_overrides_pcm_bit_perfect")
        if (sourceSampleRate <= 0 || sourceBits !in 1..32 || sourceChannels !in 1..2) {
            return if (mode == UsbBitPerfectMode.STRICT) {
                Decision(false, refusePlayback = true, reason = "strict_source_geometry_unsupported")
            } else {
                Decision(false, reason = "source_geometry_not_strict_usb_pcm")
            }
        }

        if (mode == UsbBitPerfectMode.STRICT) {
            // Strict is an intent guarantee: decoder/native selection must either find an
            // exact profile or fail. Do not silently downgrade merely because the early
            // capability snapshot is absent/stale.
            return Decision(true, reason = "strict_requested")
        }

        val geometryMatches = capabilities?.pcmFormats?.filter { f ->
            f.sampleRate == sourceSampleRate &&
                f.channels == sourceChannels &&
                f.validBits == sourceBits &&
                when (sourceBits) {
                    in 1..16 -> f.subslotBytes == 2
                    in 17..24 -> f.subslotBytes == 3 || f.subslotBytes == 4
                    else -> f.subslotBytes == 4
                }
        }.orEmpty()
        val exact = geometryMatches.any { it.exactRateProvable }
        return when {
            exact -> Decision(true, reason = "when_possible_exact_profile")
            geometryMatches.isNotEmpty() ->
                Decision(false, reason = "when_possible_clock_unverified")
            capabilities == null ->
                Decision(false, reason = "when_possible_capabilities_unknown")
            else ->
                Decision(false, reason = "when_possible_no_exact_profile")
        }
    }
}
