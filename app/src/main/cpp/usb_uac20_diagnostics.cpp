#include "usb_uac20_diagnostics.h"

#include <cstdio>
#include <iomanip>
#include <sstream>

namespace rawsmusic::usb {
namespace {

const char* yesNo(bool v) {
    return v ? "yes" : "no";
}

std::string ratioText(double ratio) {
    if (ratio <= 0.0) return "unknown";
    std::ostringstream os;
    os << std::fixed << std::setprecision(3) << ratio;
    return os.str();
}

std::string endpointText(int ep) {
    if (ep <= 0) return "0x00";
    char buf[12];
    snprintf(buf, sizeof(buf), "0x%02X", ep & 0xff);
    return std::string(buf);
}

} // namespace

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
        bool transportLost) {
    std::ostringstream os;
    os << "source=" << (source.empty() ? "unknown" : source)
       << " signal=" << uac20RecoverySignalName(signal)
       << " decision=" << uac20RecoveryDecisionName(decision)
       << " disableFb=" << yesNo(disableFeedback)
       << " keepExplicitFb=" << yesNo(keepExplicitFeedback)
       << " fullReopen=" << yesNo(requireFullReopen)
       << " resetAlt=" << yesNo(requireAltReset)
       << " lowerFormat=" << yesNo(lowerFormat)
       << " androidFallback=" << yesNo(androidFallback)
       << " transportLost=" << yesNo(transportLost);
    return os.str();
}

std::string summarizeUac20FeedbackHealth(
        const Uac20FeedbackRuntimeStats& stats,
        bool explicitFeedbackExpected,
        int feedbackEndpoint) {
    std::ostringstream os;
    const bool endpointPresent = feedbackEndpoint != 0;
    const bool locked = stats.completeCount > 0 && stats.errorCount == 0 && stats.feedbackFramesPerMicroframe > 0.0;
    const bool failed = stats.submitResult < 0 || stats.errorCount > 0;
    const char* health = "unknown";
    if (!explicitFeedbackExpected || !endpointPresent) {
        health = "not-required";
    } else if (locked) {
        health = "locked";
    } else if (failed) {
        health = "error";
    } else if (stats.submitted || stats.attempted) {
        health = "pending";
    }
    os << "health=" << health
       << " expected=" << yesNo(explicitFeedbackExpected)
       << " ep=" << endpointText(feedbackEndpoint)
       << " submitted=" << yesNo(stats.submitted)
       << " active=" << yesNo(stats.active)
       << " complete=" << stats.completeCount
       << " error=" << stats.errorCount
       << " resubmit=" << stats.resubmitCount
       << " fpmf=" << std::fixed << std::setprecision(3) << stats.feedbackFramesPerMicroframe;
    return os.str();
}

std::string summarizeUac20OutProbeHealth(const Uac20OutProbeStats& stats) {
    const bool healthy = stats.submitted && stats.errorCount == 0 &&
            stats.submitErrorCount == 0 && stats.completionRatio >= 0.95;
    const bool weak = stats.submitted && stats.errorCount == 0 &&
            stats.submitErrorCount == 0 && stats.completionRatio > 0.0 &&
            stats.completionRatio < 0.95;
    const bool failed = stats.submitErrorCount > 0 || stats.errorCount > 0;
    const char* health = "not-run";
    if (healthy) health = "healthy";
    else if (weak) health = "weak";
    else if (failed) health = "error";
    else if (stats.attempted || stats.submitted) health = "pending";

    std::ostringstream os;
    os << "health=" << health
       << " submitted=" << yesNo(stats.submitted)
       << " active=" << yesNo(stats.active)
       << " complete=" << stats.completeCount
       << " error=" << stats.errorCount
       << " submitError=" << stats.submitErrorCount
       << " completedBps=" << stats.completedBytesPerSecond
       << " expectedBps=" << stats.expectedBytesPerSecond
       << " ratio=" << ratioText(stats.completionRatio)
       << " elapsedMs=" << stats.elapsedMs;
    return os.str();
}


std::string summarizeUac20ClockHealth(
        bool configured,
        bool verified,
        int selectedClockSource,
        int deviceSampleRate,
        int targetSampleRate,
        int clockSetResult,
        int clockGetResult) {
    const bool rateMatches = targetSampleRate <= 0 || deviceSampleRate <= 0 || deviceSampleRate == targetSampleRate;
    const char* health = "unknown";
    if (configured && verified && rateMatches) health = "verified";
    else if (configured && rateMatches) health = "configured";
    else if (configured) health = "rate-mismatch";
    else if (clockSetResult < 0 || clockGetResult < 0) health = "error";

    std::ostringstream os;
    os << "health=" << health
       << " configured=" << yesNo(configured)
       << " verified=" << yesNo(verified)
       << " src=" << selectedClockSource
       << " deviceRate=" << deviceSampleRate
       << " targetRate=" << targetSampleRate
       << " setResult=" << clockSetResult
       << " getResult=" << clockGetResult;
    return os.str();
}

std::string summarizeUac20EventLoopHealth(
        bool running,
        int64_t ticks,
        int errors,
        int lastError) {
    const char* health = "not-started";
    if (running && errors == 0 && ticks > 0) health = "running";
    else if (running && errors > 0) health = "running-with-errors";
    else if (!running && ticks > 0 && errors == 0) health = "stopped-clean";
    else if (errors > 0) health = "error";

    std::ostringstream os;
    os << "health=" << health
       << " running=" << yesNo(running)
       << " ticks=" << ticks
       << " errors=" << errors
       << " lastError=" << lastError;
    return os.str();
}

std::string summarizeUac20WriteRingHealth(const Uac20WriteRingStats& stats) {
    const bool aligned = stats.unalignedWriteCalls == 0 && stats.lastAlignmentRemainder == 0;
    const bool dropping = stats.totalDroppedBytes > 0;
    const char* health = "not-run";
    if (stats.initialized && aligned && !dropping) health = "healthy";
    else if (stats.initialized && !dropping) health = "warning";
    else if (stats.initialized) health = "dropping";

    std::ostringstream os;
    os << "health=" << health
       << " initialized=" << yesNo(stats.initialized)
       << " shadow=" << yesNo(stats.shadowMode)
       << " frameBytes=" << stats.frameBytes
       << " level=" << stats.levelBytes << '/' << stats.capacityBytes
       << " appInBps=" << stats.appInBytesPerSecond
       << " calls=" << stats.totalWriteCalls
       << " unaligned=" << stats.unalignedWriteCalls
       << " dropped=" << stats.totalDroppedBytes;
    return os.str();
}

std::string summarizeUac20PcmAdapterHealth(const Uac20PcmAdapterStats& stats) {
    const bool aligned = stats.unalignedCalls == 0 && stats.lastRemainderBytes == 0;
    const bool configured = stats.configured && stats.mode != Uac20PcmAdapterMode::Unsupported;
    const char* health = "not-configured";
    if (configured && aligned) health = "healthy";
    else if (configured) health = "unaligned";

    std::ostringstream os;
    os << "health=" << health
       << " mode=" << uac20PcmAdapterModeName(stats.mode)
       << " srcFrame=" << stats.sourceFrameBytes
       << " devFrame=" << stats.deviceFrameBytes
       << " in=" << stats.totalInputBytes
       << " out=" << stats.totalOutputBytes
       << " calls=" << stats.convertCalls
       << " rem=" << stats.lastRemainderBytes
       << " unaligned=" << stats.unalignedCalls;
    return os.str();
}

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
        const std::string& recoveryReport) {
    std::ostringstream os;
    os << "fmt=" << sampleRate << "Hz/" << validBits << "bit/" << channels << "ch/subslot=" << subslotBytes
       << " iface=" << iface << " alt=" << alt
       << " out=" << endpointText(outEndpoint)
       << " fb=" << endpointText(feedbackEndpoint)
       << " | feedback{" << feedbackHealth << '}'
       << " | out{" << outHealth << '}'
       << " | recovery{" << recoveryReport << '}';
    return os.str();
}

std::string recommendUac20NextAction(
        Uac20RecoverySignal signal,
        Uac20RecoveryDecisionAction decision,
        bool keepExplicitFeedback,
        bool outProbeAttempted,
        double outCompletionRatio,
        bool writeRingHealthy,
        bool pcmAdapterHealthy) {
    if (decision == Uac20RecoveryDecisionAction::MarkTransportLost) {
        return "stop-session-and-wait-for-reattach";
    }
    if (decision == Uac20RecoveryDecisionAction::AndroidHalFallback) {
        return "offer-android-hal-fallback-after-native-choices-exhausted";
    }
    if (signal == Uac20RecoverySignal::UsbNotOutputting) {
        return keepExplicitFeedback
                ? "full-reopen-or-reset-alt-with-explicit-feedback-kept"
                : "reset-alt-before-considering-no-feedback";
    }
    if (signal == Uac20RecoverySignal::FeedbackTimeout ||
        signal == Uac20RecoverySignal::FeedbackTransferError) {
        return "reopen-same-format-before-degrading-feedback";
    }
    if (outProbeAttempted && outCompletionRatio >= 0.95 && writeRingHealthy && pcmAdapterHealthy) {
        return "ready-for-shadow-to-real-out-ring-migration";
    }
    if (!pcmAdapterHealthy) {
        return "fix-pcm-container-alignment-before-real-out";
    }
    if (!writeRingHealthy) {
        return "fix-shadow-write-ring-input-before-real-out";
    }
    return "continue-v2-diagnostics";
}

} // namespace rawsmusic::usb
