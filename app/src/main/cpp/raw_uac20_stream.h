#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "raw_audio_safety.h"
#include "raw_uac20_format_matcher.h"
#include "usb_uac20_event_loop.h"
#include "usb_uac20_feedback.h"
#include "usb_uac20_real_out_submitter.h"
#include "usb_uac20_write_ring.h"

struct libusb_context;
struct libusb_device_handle;

namespace rawsmusic::usb {

enum class RawUac20StreamState : int {
    Closed = 0,
    Configured = 1,
    Prepared = 2,
    Streaming = 3,
    Draining = 4,
    Standby = 5,
    Recovering = 6,
    Detached = 7,
    Dead = 8,
};

struct RawUac20StreamConfig {
    int sampleRate = 0;
    int channels = 0;
    int validBits = 0;
    int subslotBytes = 0;
    int frameBytes = 0;
    int bytesPerSecond = 0;

    int audioStreamingInterface = -1;
    int altSetting = 0;
    int outEndpoint = 0;
    int feedbackEndpoint = 0;
    int endpointMaxPacketSize = 0;
    int endpointInterval = 1;
    int intervalsPerSecond = 1000;

    bool explicitFeedback = false;
    bool feedbackRequired = false;
    bool allowFeedback = true;
    bool allowOutSubmit = false;
    bool allowZeroFill = true;
    bool dynamicPacketSizing = true;
    bool autoResubmit = true;
    bool debugSmokeTest = false;
    bool syntheticConsume = false;
    bool shadowPacketPacer = false;
    bool useExternalEventLoop = false;

    int packetsPerTransfer = 0;
    int transferCount = 0;
    int transferBytes = 0;
    int queueBytes = 0;
    int startupPrebufferBytes = 0;
    int timeoutMs = 1000;
    int cancelWaitMs = 80;
    std::vector<int> packetBytes;

    RawAudioSafetyPolicy safetyPolicy = defaultRawAudioSafetyPolicy();
    RawUac20FormatMatchResult formatMatch{};
};

struct RawUac20StreamStatus {
    RawUac20StreamState state = RawUac20StreamState::Closed;
    bool configured = false;
    bool attached = false;
    bool prepared = false;
    bool streaming = false;
    bool standby = false;

    int sampleRate = 0;
    int channels = 0;
    int validBits = 0;
    int subslotBytes = 0;
    int frameBytes = 0;
    int bytesPerSecond = 0;
    int outEndpoint = 0;
    int feedbackEndpoint = 0;
    bool explicitFeedback = false;
    bool feedbackRequired = false;
    bool allowOutSubmit = false;
    bool useExternalEventLoop = false;

    int packetsPerTransfer = 0;
    int transferCount = 0;
    int transferBytes = 0;
    int queueBytes = 0;
    int startupPrebufferBytes = 0;
    int writeRingLevelBytes = 0;
    int writeRingCapacityBytes = 0;
    int64_t writeRingTotalInputBytes = 0;
    int64_t writeRingTotalAcceptedBytes = 0;
    int64_t writeRingTotalDroppedBytes = 0;

    bool syntheticConsumeEnabled = false;
    bool syntheticConsumeActive = false;
    int64_t syntheticConsumeCalls = 0;
    int64_t syntheticConsumeTotalTargetBytes = 0;
    int64_t syntheticConsumeTotalConsumedBytes = 0;
    int64_t syntheticConsumeUnderrunCalls = 0;
    int syntheticConsumeLastTargetBytes = 0;
    int syntheticConsumeLastConsumedBytes = 0;
    int syntheticConsumeLastMissingBytes = 0;
    int syntheticConsumeBytesPerSecond = 0;
    std::string syntheticConsumeSummary;

    bool shadowPacketPacerEnabled = false;
    bool shadowPacketPacerActive = false;
    int64_t shadowPacketPacerCalls = 0;
    int64_t shadowPacketPacerIntervals = 0;
    int64_t shadowPacketPacerTransfers = 0;
    int64_t shadowPacketPacerTargetBytes = 0;
    int64_t shadowPacketPacerConsumedBytes = 0;
    int64_t shadowPacketPacerUnderrunPackets = 0;
    int shadowPacketPacerLastIntervalTargetBytes = 0;
    int shadowPacketPacerLastIntervalConsumedBytes = 0;
    int shadowPacketPacerLastIntervalMissingBytes = 0;
    int shadowPacketPacerLastTransferTargetBytes = 0;
    int shadowPacketPacerLastTransferConsumedBytes = 0;
    int shadowPacketPacerLastTransferMissingBytes = 0;
    int shadowPacketPacerLastPacketLengthMin = 0;
    int shadowPacketPacerLastPacketLengthMax = 0;
    int shadowPacketPacerLastPacketLengthTotal = 0;
    int shadowPacketPacerLastTransferPacketCount = 0;
    int shadowPacketPacerBytesPerSecond = 0;
    std::string shadowPacketPacerPacketPatternSummary;
    std::string shadowPacketPacerSummary;

    bool eventLoopRunning = false;
    int64_t eventLoopTicks = 0;
    bool feedbackActive = false;
    int feedbackCompleteCount = 0;
    int feedbackErrorCount = 0;
    double feedbackFramesPerMicroframe = 0.0;

    bool outPrepared = false;
    bool outSubmitted = false;
    bool outActive = false;
    int outCallbackCount = 0;
    int outCompleteCount = 0;
    int outSubmitOkCount = 0;
    int outSubmitFailCount = 0;
    int outCompletedBytesPerSecond = 0;
    int outExpectedBytesPerSecond = 0;
    double outCompletionRatio = 0.0;
    int outZeroFilledBytes = 0;
    int outFedBytes = 0;
    int outFeederUnderrunCount = 0;

    std::string queueSizingSummary;
    std::string formatMatchSummary;
    std::string writeRingSummary;
    std::string feedbackSummary;
    std::string outSummary;
    std::string eventLoopSummary;
    std::string lastError;
    std::string summary;
};

const char* rawUac20StreamStateName(RawUac20StreamState state);
std::string describeRawUac20StreamConfig(const RawUac20StreamConfig& config);
std::string describeRawUac20StreamStatus(const RawUac20StreamStatus& status);

// Step 92: stream lifecycle facade.  This is intentionally not wired
// into production playback yet.  It collects the original-engine stream safety
// rules (FIFO, feedback queue, event thread, OUT URB queue, prebuffer boundary)
// behind one object so the next steps can replace scattered Uac20Session logic
// without changing the Java/Kotlin path first.
class RawUac20Stream {
public:
    RawUac20Stream();
    ~RawUac20Stream();

    RawUac20Stream(const RawUac20Stream&) = delete;
    RawUac20Stream& operator=(const RawUac20Stream&) = delete;

    bool configure(const RawUac20StreamConfig& config);
    bool attach(libusb_context* context, libusb_device_handle* handle);
    bool prepareUrbs();
    bool start(const char* reason = "raw_uac20_stream_start");
    void stop(const char* reason = "raw_uac20_stream_stop");
    void standby(const char* reason = "raw_uac20_stream_standby");
    void close(const char* reason = "raw_uac20_stream_close");
    void reset(const char* reason = "raw_uac20_stream_reset");

    // Takes already-converted USB device PCM.  RawPcmPipeline remains upstream.
    int writeDevicePcm(const uint8_t* data, int bytes);

    bool readyForOutStart() const;
    int requiredPrebufferBytes() const;
    RawUac20StreamStatus status() const;
    std::string runtimeJson() const;

private:
    bool normalizeConfigLocked(RawUac20StreamConfig* config, std::string* error) const;
    Uac20RealOutSubmitterConfig buildOutSubmitterConfigLocked() const;
    int syntheticConsumeForNowLocked(const char* source);
    int shadowPacketPacerConsumeForNowLocked(const char* source);
    int shadowPacketSizeForNextIntervalLocked();
    int readFifoForShadowLocked(int targetBytes);
    void resetSyntheticConsumeLocked();
    void resetShadowPacketPacerLocked();
    void refreshStatusLocked(RawUac20StreamStatus* status) const;
    void transitionTo(RawUac20StreamState state, const char* reason);
    static std::string jsonEscape(const std::string& in);

private:
    libusb_context* context_ = nullptr; // non-owning; owned by caller/session
    libusb_device_handle* handle_ = nullptr; // non-owning; owned by caller/session
    RawUac20StreamConfig config_{};
    RawUac20StreamStatus status_{};
    RawUac20QueueSizing queueSizing_{};
    Uac20WriteRing fifo_{};
    Uac20EventLoop eventLoop_{};
    Uac20PersistentFeedbackTransfer feedback_{};
    Uac20RealOutSubmitter outQueue_{};

    bool syntheticConsumeEnabled_ = false;
    int64_t syntheticConsumeStartMs_ = 0;
    int64_t syntheticConsumeLastMs_ = 0;
    double syntheticConsumeCarryBytes_ = 0.0;
    int64_t syntheticConsumeCalls_ = 0;
    int64_t syntheticConsumeTotalTargetBytes_ = 0;
    int64_t syntheticConsumeTotalConsumedBytes_ = 0;
    int64_t syntheticConsumeUnderrunCalls_ = 0;
    int syntheticConsumeLastTargetBytes_ = 0;
    int syntheticConsumeLastConsumedBytes_ = 0;
    int syntheticConsumeLastMissingBytes_ = 0;
    int syntheticConsumeBytesPerSecond_ = 0;
    mutable std::vector<uint8_t> syntheticConsumeScratch_{};

    bool shadowPacketPacerEnabled_ = false;
    int64_t shadowPacketPacerStartMs_ = 0;
    int64_t shadowPacketPacerLastMs_ = 0;
    double shadowPacketPacerCarryIntervals_ = 0.0;
    uint64_t shadowPacketPacerFramesQ32_ = 0;
    uint64_t shadowPacketPacerFrameStepQ32_ = 0;
    int shadowPacketPacerCurrentTransferPacketCount_ = 0;
    int shadowPacketPacerCurrentTransferTargetBytes_ = 0;
    int shadowPacketPacerCurrentTransferConsumedBytes_ = 0;
    int shadowPacketPacerCurrentTransferMissingBytes_ = 0;
    int64_t shadowPacketPacerCalls_ = 0;
    int64_t shadowPacketPacerIntervals_ = 0;
    int64_t shadowPacketPacerTransfers_ = 0;
    int64_t shadowPacketPacerTargetBytes_ = 0;
    int64_t shadowPacketPacerConsumedBytes_ = 0;
    int64_t shadowPacketPacerUnderrunPackets_ = 0;
    int shadowPacketPacerLastIntervalTargetBytes_ = 0;
    int shadowPacketPacerLastIntervalConsumedBytes_ = 0;
    int shadowPacketPacerLastIntervalMissingBytes_ = 0;
    int shadowPacketPacerLastTransferTargetBytes_ = 0;
    int shadowPacketPacerLastTransferConsumedBytes_ = 0;
    int shadowPacketPacerLastTransferMissingBytes_ = 0;
    int shadowPacketPacerLastPacketLengthMin_ = 0;
    int shadowPacketPacerLastPacketLengthMax_ = 0;
    int shadowPacketPacerLastPacketLengthTotal_ = 0;
    int shadowPacketPacerLastTransferPacketCount_ = 0;
    int shadowPacketPacerBytesPerSecond_ = 0;
    std::string shadowPacketPacerPacketPatternSummary_;
    mutable std::vector<uint8_t> shadowPacketPacerScratch_{};
};

} // namespace rawsmusic::usb
