#pragma once

#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "libusb.h"
#include "usb_uac20_descriptors.h"

struct libusb_device_handle;

namespace rawsmusic::usb {

enum class Uac20PackageAdjustMode : int {
    Nominal = 0,
    Conservative = 1,
    FeedbackGuided = 2,
};

const char* uac20PackageAdjustModeName(Uac20PackageAdjustMode mode);

struct Uac20OutTransferPlan {
    bool prepared = false;
    int endpointAddress = 0;
    int endpointMaxPacketSize = 0;
    int endpointInterval = 0;
    int packetsPerTransfer = 0;
    int transferCount = 0;
    int nominalFramesPerPacket = 0;
    int nominalPacketBytes = 0;
    int minPacketBytes = 0;
    int maxPacketBytesInPattern = 0;
    int maxPacketBytes = 0;
    int transferBytes = 0;
    int queueBytes = 0;
    int frameBytes = 0;
    int sampleRate = 0;
    int intervalsPerSecond = 0;
    Uac20PackageAdjustMode packageAdjustMode = Uac20PackageAdjustMode::Conservative;
    double feedbackFramesPerMicroframe = 0.0;
    double targetFramesPerPacket = 0.0;
    std::vector<int> packetBytes;
    std::string packetPatternSummary;
    std::string summary;
    std::string lastError;
};

struct Uac20OutProbeStats {
    bool attempted = false;
    bool submitted = false;
    bool active = false;
    bool cancelled = false;

    int submitResult = 0;
    int cancelResult = 0;
    int transferStatus = 0;
    int submittedTransferCount = 0;
    int activeTransferCount = 0;
    int completeCount = 0;
    int resubmitCount = 0;
    int errorCount = 0;
    int submitErrorCount = 0;
    int unreleasedTransferCount = 0;

    int scheduledBytes = 0;
    int completedBytes = 0;
    int completedBytesPerSecond = 0;
    int expectedBytesPerSecond = 0;
    int elapsedMs = 0;
    double completionRatio = 0.0;

    std::string lastError;
};

// Builds the OUT ISO transfer plan without submitting any transfer. This is the
// v2 OUT URB preparation stage: select packet
// count/packet bytes/queue size and expose diagnostics, but keep production
// playback on the legacy engine until event-thread and write-path migration.
bool prepareUac20OutTransferPlan(
        const Uac20AltSnapshot& alt,
        int sampleRate,
        int frameBytes,
        bool explicitFeedback,
        Uac20PackageAdjustMode packageAdjustMode,
        double feedbackFramesPerMicroframe,
        Uac20OutTransferPlan* outPlan);

std::string describeUac20OutTransferPlan(const Uac20OutTransferPlan& plan);

// Short diagnostic OUT probe used after persistent feedback has been submitted.
// It sends silence for a bounded window, then cancels all transfers. This is the
// first controlled test for the old HyperOS/TP55 failure mode: whether ISO OUT
// completions drain at the expected rate while feedback is alive.
class Uac20SilentOutSubmitProbe {
public:
    Uac20SilentOutSubmitProbe();
    ~Uac20SilentOutSubmitProbe();

    Uac20SilentOutSubmitProbe(const Uac20SilentOutSubmitProbe&) = delete;
    Uac20SilentOutSubmitProbe& operator=(const Uac20SilentOutSubmitProbe&) = delete;

    bool run(
            libusb_device_handle* handle,
            const Uac20OutTransferPlan& plan,
            int expectedBytesPerSecond,
            int durationMs = 420);
    void stop(const char* reason);

    bool active() const;
    Uac20OutProbeStats snapshot() const;
    std::string summary() const;

private:
    struct TransferSlot;

    static void LIBUSB_CALL callback(libusb_transfer* transfer);
    void onCallback(TransferSlot* slot, libusb_transfer* transfer);
    void freeCompletedSlotsLocked();
    void updateRatesLocked();

private:
    mutable std::mutex mutex_;
    libusb_device_handle* handle_ = nullptr; // non-owning; owned by session
    Uac20OutTransferPlan plan_{};
    std::vector<std::unique_ptr<TransferSlot>> slots_;
    bool stopping_ = false;
    bool active_ = false;
    int expectedBytesPerSecond_ = 0;
    int64_t startTimeMs_ = 0;
    Uac20OutProbeStats stats_{};
};

std::string describeUac20OutProbeStats(const Uac20OutProbeStats& stats);

} // namespace rawsmusic::usb
