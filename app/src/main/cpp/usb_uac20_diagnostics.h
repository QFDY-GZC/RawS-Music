#pragma once

#include <string>

#include "usb_uac20_feedback.h"
#include "usb_uac20_pcm_adapter.h"
#include "usb_uac20_recovery_policy.h"
#include "usb_uac20_transfers.h"
#include "usb_uac20_write_ring.h"

namespace rawsmusic::usb {

// Human-readable diagnostics for the parked UAC20 v2 stack. These helpers are
// intentionally native-only: Kotlin should not parse dozens of transient counters
// just to show whether feedback/OUT/write/recovery are healthy.
std::string summarizeUac20RecoveryPolicyForReport(
        const std::string& source,
        Uac20RecoverySignal signal,
        Uac20RecoveryDecisionAction decision,
        bool disableFeedback,
        bool keepExplicitFeedback,
        bool requireFullReopen,
        bool requireAltReset,
        bool lowerFormat,
        bool androidFallback,
        bool transportLost);

std::string summarizeUac20FeedbackHealth(
        const Uac20FeedbackRuntimeStats& stats,
        bool explicitFeedbackExpected,
        int feedbackEndpoint);

std::string summarizeUac20OutProbeHealth(const Uac20OutProbeStats& stats);
std::string summarizeUac20ClockHealth(
        bool configured,
        bool verified,
        int selectedClockSource,
        int deviceSampleRate,
        int targetSampleRate,
        int clockSetResult,
        int clockGetResult);
std::string summarizeUac20EventLoopHealth(
        bool running,
        int64_t ticks,
        int errors,
        int lastError);
std::string summarizeUac20WriteRingHealth(const Uac20WriteRingStats& stats);
std::string summarizeUac20PcmAdapterHealth(const Uac20PcmAdapterStats& stats);

std::string makeUac20RuntimeHeadline(
        int sampleRate,
        int validBits,
        int channels,
        int subslotBytes,
        int iface,
        int alt,
        int outEndpoint,
        int feedbackEndpoint,
        const std::string& feedbackHealth,
        const std::string& outHealth,
        const std::string& recoveryReport);

std::string recommendUac20NextAction(
        Uac20RecoverySignal signal,
        Uac20RecoveryDecisionAction decision,
        bool keepExplicitFeedback,
        bool outProbeAttempted,
        double outCompletionRatio,
        bool writeRingHealthy,
        bool pcmAdapterHealthy);

} // namespace rawsmusic::usb
