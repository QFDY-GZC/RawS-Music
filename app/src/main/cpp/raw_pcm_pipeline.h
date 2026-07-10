#pragma once

#include <cstdint>
#include <string>
#include <vector>

#include "raw_audio_format_policy.h"

struct SwrContext;


namespace rawsmusic::usb {

struct RawPcmPipelineConfig {
    RawPcmFormatSpec source{};
    RawPcmFormatSpec device{};
    RawAudioTransportKind transport = RawAudioTransportKind::Pcm;
    bool bitPerfect = true;

    // Original usb_audio_engine.cpp convention: FFmpeg S32LE carries 24 valid
    // bits in the upper 24 bits, so packed-S24 output drops the low padding byte.
    int source24In32ShiftBits = 8;

    // Keep swr output in the decoder/source PCM container, then pack to the USB
    // device container afterwards.  This prevents S32 swr output from being
    // copied as 3-byte S24 frames and causing frame corruption.
    bool swrOutputsSourceContainer = true;

    int outputBufferSeconds = 4;
};

struct RawPcmPipelineProcessResult {
    int consumedInputBytes = 0;
    int producedDeviceBytes = 0;
    int droppedRemainderBytes = 0;
    int swrOutputFrames = 0;
    int errorCode = 0;
    std::string reason;
};

struct RawPcmPipelineStats {
    bool configured = false;
    bool resamplerRequired = false;
    bool resamplerInitialized = false;
    RawAudioTransportKind transport = RawAudioTransportKind::Pcm;

    int sourceSampleRate = 0;
    int sourceChannels = 0;
    int sourceBits = 0;
    int sourceBytesPerSample = 0;
    int sourceFrameBytes = 0;

    int deviceSampleRate = 0;
    int deviceChannels = 0;
    int deviceBits = 0;
    int deviceSubslotBytes = 0;
    int deviceFrameBytes = 0;

    int swrFrameBytes = 0;
    int swrOutputBufferBytes = 0;
    RawPcmAdapterMode adapterMode = RawPcmAdapterMode::Unsupported;
    std::string adapterReason;

    int64_t totalInputBytes = 0;
    int64_t totalConsumedInputBytes = 0;
    int64_t totalProducedDeviceBytes = 0;
    int64_t totalDroppedRemainderBytes = 0;
    int64_t totalSWRFrames = 0;
    int64_t processCalls = 0;
    int64_t unalignedInputCalls = 0;
    int64_t zeroOutputCalls = 0;
    int64_t adapterErrorCalls = 0;
    int64_t swrErrorCalls = 0;

    int lastInputBytes = 0;
    int lastConsumedInputBytes = 0;
    int lastProducedDeviceBytes = 0;
    int lastDroppedRemainderBytes = 0;
    int lastSWROutputFrames = 0;
    int lastErrorCode = 0;
    std::string lastReason;
    std::string lastError;
};

class RawPcmPipeline {
public:
    RawPcmPipeline();
    ~RawPcmPipeline();

    RawPcmPipeline(const RawPcmPipeline&) = delete;
    RawPcmPipeline& operator=(const RawPcmPipeline&) = delete;

    bool configure(const RawPcmPipelineConfig& config);
    void reset();

    // Converts input PCM into USB device PCM. The caller owns the final FIFO/ring
    // write and passes an output capacity that is already frame-aligned to the
    // current device container. The returned consumedInputBytes tells the caller
    // how many source bytes were accepted by the pipeline.
    RawPcmPipelineProcessResult process(
            const uint8_t* input,
            int inputBytes,
            uint8_t* output,
            int outputCapacityBytes);

    RawPcmPipelineStats snapshot() const;
    std::string summary() const;

private:
    bool configureResamplerLocked();
    void closeResamplerLocked();
    int adaptPcmToDeviceLocked(const uint8_t* input, int inputBytes, uint8_t* output, int outputCapacityBytes);
    int copyExactLocked(const uint8_t* input, int inputBytes, uint8_t* output, int outputCapacityBytes) const;
    int convertS16ToPacked24Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const;
    int convertS16To24In32Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const;
    int convertPacked24To24In32Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const;
    int convert24In32ToPacked24Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const;
    int convert32To24In32Locked(const uint8_t* input, int frames, uint8_t* output, int outputCapacityBytes) const;

private:
    RawPcmPipelineConfig config_{};
    RawPcmPipelineStats stats_{};
    RawResamplerPlan plan_{};
    RawPcmAdapterDecision adapter_{};
    SwrContext* swr_ = nullptr;
    std::vector<uint8_t> swrOutputBuffer_{};
};

std::string describeRawPcmPipelineStats(const RawPcmPipelineStats& stats);

} // namespace rawsmusic::usb
