#pragma once

#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include "libusb.h"
#include "usb_uac20_write_ring.h"

namespace rawsmusic::usb {

struct Uac20RealOutSubmitterConfig {
    int endpointAddress = 0;
    int transferCount = 4;
    int packetsPerTransfer = 8;
    int transferBytes = 0;
    int endpointMaxPacketSize = 0;
    int timeoutMs = 1000;
    int cancelWaitMs = 0;
    int expectedBytesPerSecond = 0;
    bool submissionEnabled = false;
    bool zeroFill = true;
    bool debugSmokeTest = true;
    bool autoResubmit = false;
    bool feedFromWriteRing = false;
    int maxCallbacks = 0;
    int maxRunMs = 0;
    std::vector<int> packetBytes;

    // 0087: For no-feedback / fixed-pacer UAC2 endpoints, 44.1k-family rates
    // cannot be represented by one static 1ms packet pattern. Keep a running
    // frame accumulator so packets stay frame-aligned and the long-term rate is
    // exact instead of always sending 44 frames/ms.
    bool dynamicPacketSizing = false;
    int sampleRate = 0;
    int frameBytes = 0;
    int intervalsPerSecond = 0;
};

struct Uac20RealOutSubmitterStats {
    bool initialized = false;
    bool allocated = false;
    bool attempted = false;
    bool submitted = false;
    bool active = false;
    bool submissionEnabled = false;
    bool dryRunBlockedSubmit = false;
    bool zeroFill = true;
    bool debugSmokeTest = true;
    bool autoResubmit = false;
    bool feedFromWriteRing = false;
    bool cancelRequested = false;
    bool budgetExpired = false;
    bool cleanStopRequested = false;
    bool layoutValid = false;
    bool layoutMismatch = false;

    int endpointAddress = 0;
    int transferCount = 0;
    int allocatedTransferCount = 0;
    int submittedTransferCount = 0;
    int packetsPerTransfer = 0;
    int transferBytes = 0;
    int endpointMaxPacketSize = 0;
    int queueBytes = 0;
    int maxCallbacks = 0;
    int maxRunMs = 0;
    int elapsedMs = 0;
    int releaseGeneration = 0;
    int packetLengthTotal = 0;
    int packetLengthMin = 0;
    int packetLengthMax = 0;
    int zeroLengthPacketCount = 0;

    int allocationErrorCount = 0;
    int submitErrorCount = 0;
    int submitOkCount = 0;
    int submitFailCount = 0;
    int transferErrorCount = 0;
    int completeCount = 0;
    int callbackCount = 0;
    int resubmitCount = 0;
    int cancelledCount = 0;
    int cancelSubmitCount = 0;
    int feederUnderrunCount = 0;

    int64_t submittedBytes = 0;
    int64_t completedBytes = 0;
    int completedBytesPerSecond = 0;
    int expectedBytesPerSecond = 0;
    double completionRatio = 0.0;
    int fedBytes = 0;
    int zeroFilledBytes = 0;
    int lastFedBytes = 0;
    int lastZeroFilledBytes = 0;
    int lastSubmitResult = 0;
    int lastCancelResult = 0;
    int lastTransferStatus = 0;
    int lastIsoPacketStatus = 0;
    int lastIsoActualLength = 0;
    int lastIsoPacketLength = 0;
    int firstSubmitMs = 0;
    int lastSubmitMs = 0;
    int firstCallbackMs = 0;
    int lastCallbackMs = 0;
    int noCompletionMs = 0;

    // 0044-0045: clean stop / cancel / close boundary
    bool releaseComplete = false;
    bool releaseDeferred = false;
    int activeTransferCount = 0;
    int pendingAfterCancel = 0;
    int cancelWaitMs = 0;
    int cancelCalls = 0;

    std::string lastError;
    std::string layoutError;
    std::string lastStopReason;
    std::string summary;
};

class Uac20RealOutSubmitter {
public:
    Uac20RealOutSubmitter();
    ~Uac20RealOutSubmitter();

    bool prepare(const Uac20RealOutSubmitterConfig& config);
    bool startZeroSubmit(libusb_device_handle* handle);
    bool startDebugFeeder(libusb_device_handle* handle, Uac20WriteRing* writeRing);
    void cancelAndRelease(const char* reason);
    Uac20RealOutSubmitterStats snapshot() const;
    std::string summary() const;

private:
    bool startLocked(libusb_device_handle* handle, Uac20WriteRing* writeRing);
    bool allocateTransfersLocked(libusb_device_handle* handle);
    bool refillTransferLocked(libusb_transfer* transfer);
    void applyPacketLengthsLocked(libusb_transfer* transfer);
    int nextDynamicPacketBytesLocked();
    int completedLengthLocked(const libusb_transfer* transfer) const;
    bool shouldResubmitLocked() const;
    static int64_t nowMs();
    static void LIBUSB_CALL callback(libusb_transfer* transfer);
    void onCallback(libusb_transfer* transfer);
    void freeTransfersLocked(bool clearFeeder = true);

private:
    mutable std::mutex mutex_;
    Uac20RealOutSubmitterConfig config_{};
    Uac20RealOutSubmitterStats stats_{};
    std::vector<uint8_t> transferBuffers_;
    std::vector<libusb_transfer*> transfers_;
    std::vector<bool> transferActive_;
    // Logical in-flight counter independent of transferActive_ bitmap. Some
    // Android/libusb builds can report submit success before our active bitmap
    // is useful for diagnostics; keep an ownership counter so no-completion
    // timeout and cancel handling do not silently collapse to activeTransferCount=0.
    int submittedInFlightCount_ = 0;
    // Step 88: original-engine ISO pacer model: Q32 frame accumulator. This
    // keeps 44.1k family rates exact over long runs and never emits a partial
    // PCM frame.
    uint64_t dynamicSampleRateQ32_ = 0;
    uint64_t dynamicAccumulatorQ32_ = 0;
    int dynamicMaxPacketBytes_ = 0;
    Uac20WriteRing* writeRing_ = nullptr; // not owned, valid only while active.
    int64_t startTimeMs_ = 0;
};

std::string describeUac20RealOutSubmitterStats(const Uac20RealOutSubmitterStats& s);

} // namespace rawsmusic::usb
