#pragma once

#include <string>

namespace rawsmusic::usb {

struct Uac20PlaybackGuardInput {
    bool debugRealOutEnabled = false;
    bool sessionStarted = false;
    bool decodedPcmSource = false;
    bool explicitFeedbackExpected = true;

    int requestedDurationMs = 0;
    int elapsedMs = 0;
    int sampleRate = 0;
    int frameBytes = 0;

    int64_t decodedInputBytes = 0;
    int64_t pumpInputBytes = 0;
    int64_t pumpAcceptedBytes = 0;
    int64_t writeRingInputBytes = 0;
    int64_t writeRingAcceptedBytes = 0;
    int64_t writeRingDroppedBytes = 0;
    int64_t writeRingUnalignedCalls = 0;

    int feedbackCompleteCount = 0;
    int feedbackErrorCount = 0;
    int realOutSubmittedBytes = 0;
    int realOutCompletedBytes = 0;
    int realOutCompletedBytesPerSecond = 0;
    int realOutExpectedBytesPerSecond = 0;
    double realOutCompletionRatio = 0.0;
    int realOutSubmitErrorCount = 0;
    int realOutTransferErrorCount = 0;
    int realOutFeederUnderrunCount = 0;
    int realOutZeroFilledBytes = 0;
    int realOutPendingAfterCancel = 0;
    bool realOutReleaseDeferred = false;
};

struct Uac20PlaybackGuardResult {
    bool initialized = false;
    bool passed = false;
    bool blocksPromotion = true;
    bool decodedPathReady = false;
    bool transportReady = false;
    bool feedbackReady = false;
    bool writePathReady = false;
    bool cleanStopReady = false;

    double acceptedRatio = 0.0;
    double completedRatio = 0.0;
    double throughputRatio = 0.0;
    double zeroFillRatio = 0.0;

    std::string reason;
    std::string summary;
};

Uac20PlaybackGuardResult evaluateUac20PlaybackGuard(const Uac20PlaybackGuardInput& input);
std::string describeUac20PlaybackGuard(const Uac20PlaybackGuardResult& result);

} // namespace rawsmusic::usb
