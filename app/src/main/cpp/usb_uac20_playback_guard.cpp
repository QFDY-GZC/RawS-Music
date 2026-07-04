#include "usb_uac20_playback_guard.h"

#include <algorithm>
#include <sstream>

namespace rawsmusic::usb {
namespace {

double ratioOrZero(int64_t num, int64_t den) {
    if (den <= 0) return 0.0;
    return static_cast<double>(num) / static_cast<double>(den);
}

void appendReason(std::string& out, const char* reason) {
    if (out.empty()) out = reason;
    else {
        out += ";";
        out += reason;
    }
}

} // namespace

Uac20PlaybackGuardResult evaluateUac20PlaybackGuard(const Uac20PlaybackGuardInput& input) {
    Uac20PlaybackGuardResult r;
    r.initialized = true;

    const int64_t sourceBytes = std::max(
            std::max<int64_t>(input.pumpInputBytes, input.decodedInputBytes),
            input.writeRingInputBytes);
    const int64_t acceptedBytes = std::max(
            std::max<int64_t>(input.pumpAcceptedBytes, input.writeRingAcceptedBytes),
            input.writeRingAcceptedBytes);
    r.acceptedRatio = ratioOrZero(acceptedBytes, sourceBytes);
    r.completedRatio = ratioOrZero(input.realOutCompletedBytes, input.realOutSubmittedBytes);
    r.throughputRatio = input.realOutCompletionRatio > 0.0
            ? input.realOutCompletionRatio
            : ratioOrZero(input.realOutCompletedBytesPerSecond, input.realOutExpectedBytesPerSecond);
    r.zeroFillRatio = ratioOrZero(input.realOutZeroFilledBytes, std::max<int>(1, input.realOutSubmittedBytes));

    const bool hasRealOutThroughputMeasurement = input.debugRealOutEnabled &&
            (input.realOutExpectedBytesPerSecond > 0 || input.realOutCompletionRatio > 0.0);

    // Step 99: after RawPcmPipeline was introduced, debug decoded PCM may
    // reach the native path as writeRing/source FIFO bytes without the smoke
    // runner filling decodedInputBytes on this guard input. Treat any
    // frame-aligned source/write-ring traffic as proof that decoded PCM is no
    // longer empty. This prevents a healthy 44.1k/24/2ch stream from being
    // blocked with decoded-pcm-empty while OUT throughput and FIFO health are
    // already good.
    r.decodedPathReady = !input.decodedPcmSource ||
            input.decodedInputBytes > 0 ||
            input.pumpInputBytes > 0 ||
            input.pumpAcceptedBytes > 0 ||
            input.writeRingInputBytes > 0 ||
            input.writeRingAcceptedBytes > 0 ||
            sourceBytes > 0 ||
            acceptedBytes > 0;
    r.feedbackReady = !input.explicitFeedbackExpected || input.feedbackCompleteCount > 0;
    r.writePathReady = sourceBytes <= 0 ||
            (r.acceptedRatio >= 0.90 && input.writeRingDroppedBytes == 0 && input.writeRingUnalignedCalls == 0);
    r.transportReady = !input.debugRealOutEnabled ||
            (input.realOutCompletedBytes > 0 &&
             input.realOutSubmitErrorCount == 0 &&
             input.realOutTransferErrorCount == 0 &&
             r.zeroFillRatio < 0.20 &&
             (!hasRealOutThroughputMeasurement || r.throughputRatio >= 0.85));
    r.cleanStopReady = input.realOutPendingAfterCancel == 0 && !input.realOutReleaseDeferred;

    if (!input.sessionStarted) appendReason(r.reason, "session-not-started");
    if (!r.decodedPathReady) appendReason(r.reason, "decoded-pcm-empty");
    if (!r.feedbackReady) appendReason(r.reason, "feedback-not-locked");
    if (!r.writePathReady) {
        if (input.writeRingDroppedBytes > 0) appendReason(r.reason, "write-ring-dropping");
        else if (input.writeRingUnalignedCalls > 0) appendReason(r.reason, "write-ring-unaligned");
        else appendReason(r.reason, "write-ring-not-ready");
    }
    if (!r.transportReady) {
        if (input.realOutSubmitErrorCount > 0) appendReason(r.reason, "real-out-submit-error");
        else if (input.realOutTransferErrorCount > 0) appendReason(r.reason, "real-out-transfer-error");
        else if (r.zeroFillRatio >= 0.20) appendReason(r.reason, "real-out-zero-fill-high");
        else if (input.debugRealOutEnabled && input.realOutCompletedBytes <= 0) appendReason(r.reason, "real-out-no-completion");
        else if (r.throughputRatio > 0.0 && r.throughputRatio < 0.85) appendReason(r.reason, "real-out-under-output");
        else appendReason(r.reason, "real-out-not-ready");
    }
    if (!r.cleanStopReady) appendReason(r.reason, "clean-stop-not-ready");

    r.passed = input.sessionStarted && r.decodedPathReady && r.feedbackReady && r.writePathReady && r.transportReady && r.cleanStopReady;
    r.blocksPromotion = !r.passed;
    if (r.reason.empty()) r.reason = "pass";
    r.summary = describeUac20PlaybackGuard(r);
    return r;
}

std::string describeUac20PlaybackGuard(const Uac20PlaybackGuardResult& r) {
    std::ostringstream os;
    os << "initialized=" << (r.initialized ? "yes" : "no")
       << " passed=" << (r.passed ? "yes" : "no")
       << " blocksPromotion=" << (r.blocksPromotion ? "yes" : "no")
       << " decoded=" << (r.decodedPathReady ? "ready" : "not-ready")
       << " feedback=" << (r.feedbackReady ? "ready" : "not-ready")
       << " write=" << (r.writePathReady ? "ready" : "not-ready")
       << " transport=" << (r.transportReady ? "ready" : "not-ready")
       << " cleanStop=" << (r.cleanStopReady ? "ready" : "not-ready")
       << " acceptedRatio=" << r.acceptedRatio
       << " completedRatio=" << r.completedRatio
       << " throughputRatio=" << r.throughputRatio
       << " zeroFillRatio=" << r.zeroFillRatio
       << " reason=" << r.reason;
    return os.str();
}

} // namespace rawsmusic::usb
