#pragma once

#include <string>

namespace rawsmusic::usb {

enum class RawAudioTransportKind : int {
    Pcm = 0,
    DoP = 1,
    NativeDsd = 2,
    Unknown = 3,
};

enum class RawPcmAdapterMode : int {
    None = 0,
    S16ToS24 = 1,
    S16ToS32 = 2,
    S24ToS32 = 3,
    S32ToS24 = 4,
    S32ToS24InS32 = 5,
    Pcm24In32ToPacked24 = 6,
    Packed24ToPcm24In32 = 7,
    Pcm24In32ToContainer32 = 8,
    Unsupported = 9,
};

struct RawPcmFormatSpec {
    int sampleRate = 0;
    int channels = 0;
    int validBits = 0;
    int containerBytesPerSample = 0;

    int frameBytes() const {
        return channels > 0 && containerBytesPerSample > 0 ? channels * containerBytesPerSample : 0;
    }
};

struct RawPcmAdapterDecision {
    RawPcmAdapterMode mode = RawPcmAdapterMode::Unsupported;
    bool supported = false;
    bool losslessIntegerTransform = false;
    bool dropsLowBits = false;
    std::string reason;
};

struct RawResamplerPlan {
    bool required = false;
    bool valid = false;
    RawAudioTransportKind transport = RawAudioTransportKind::Pcm;

    int sourceSampleRate = 0;
    int sourceChannels = 0;
    int sourceValidBits = 0;
    int sourceBytesPerSample = 0;
    int sourceFrameBytes = 0;

    int deviceSampleRate = 0;
    int deviceChannels = 0;
    int deviceValidBits = 0;
    int deviceSubslotBytes = 0;
    int deviceFrameBytes = 0;

    int swrOutputFrameBytes = 0;
    int outputBufferBytes = 0;
    RawPcmAdapterMode postResampleAdapter = RawPcmAdapterMode::Unsupported;
    std::string reason;
};

const char* rawAudioTransportKindName(RawAudioTransportKind transport);
const char* rawPcmAdapterModeName(RawPcmAdapterMode mode);

RawPcmAdapterDecision chooseRawPcmAdapter(
        int sourceChannels,
        int sourceBitDepth,
        int sourceBytesPerSample,
        int deviceChannels,
        int deviceBitDepth,
        int deviceSubslotSize,
        bool bitPerfect);

RawResamplerPlan buildRawResamplerPlan(
        const RawPcmFormatSpec& source,
        const RawPcmFormatSpec& device,
        RawAudioTransportKind transport,
        bool bitPerfect);

std::string describeRawPcmAdapterDecision(const RawPcmAdapterDecision& decision);
std::string describeRawResamplerPlan(const RawResamplerPlan& plan);

} // namespace rawsmusic::usb
