#pragma once

#include <string>
#include <vector>

#include "raw_audio_format_policy.h"
#include "usb_uac20_descriptors.h"

namespace rawsmusic::usb {

enum class RawUac20PcmOutputPreference : int {
    Auto = 0,
    Packed16 = 1,
    Packed24 = 2,
    Container32 = 3,
};

enum class RawUac20ProfileRisk : int {
    None = 0,
    LowCapacity = 1 << 0,
    AsyncWithoutFeedback = 1 << 1,
    FeedbackNonStandard = 1 << 2,
    UnknownSync = 1 << 3,
    ClockUnverified = 1 << 4,
    FormatAdapterRequired = 1 << 5,
    LossyAdapter = 1 << 6,
};

inline RawUac20ProfileRisk operator|(RawUac20ProfileRisk a, RawUac20ProfileRisk b) {
    return static_cast<RawUac20ProfileRisk>(static_cast<int>(a) | static_cast<int>(b));
}

inline bool rawUac20RiskHas(RawUac20ProfileRisk risks, RawUac20ProfileRisk bit) {
    return (static_cast<int>(risks) & static_cast<int>(bit)) != 0;
}

struct RawUac20FormatMatchRequest {
    int sourceSampleRate = 0;
    int sourceChannels = 0;
    int sourceBits = 0;
    int sourceBytesPerSample = 0;

    int targetSampleRate = 0;
    int targetChannels = 0;
    int targetBits = 0;
    int targetSubslotBytes = 0;

    RawAudioTransportKind transport = RawAudioTransportKind::Pcm;
    bool bitPerfect = true;
    bool prefer24In32 = true;
    bool preferExplicitFeedback = true;
    bool noFeedbackPolicy = false;
    bool safeMode = false;
    bool forceDoP24 = false;
    bool nativeDsdRaw = false;
    int dopSampleRate = 0;
    int nativeDsdSampleRate = 0;
    int javaHintInterface = -1;
    int javaHintAlt = -1;
    int lastGoodAlt = -1;
    int lastGoodSampleRate = 0;
    int lastGoodBits = 0;
    int lastGoodSubslotBytes = 0;
    int lastGoodFeedbackEndpoint = 0;
    RawUac20PcmOutputPreference pcmPreference = RawUac20PcmOutputPreference::Auto;
};

struct RawUac20FormatCandidate {
    Uac20AltSnapshot alt{};
    int score = -1000000;
    bool compatible = false;
    bool capacityOk = false;
    bool explicitFeedbackEligible = false;
    bool rateOk = true;
    bool formatOk = false;
    bool adapterSupported = false;
    bool selected = false;

    int effectiveSampleRate = 0;
    int effectiveChannels = 0;
    int effectiveBits = 0;
    int effectiveSubslotBytes = 0;
    int frameBytes = 0;
    int serviceIntervalsPerSecond = 0;
    int nominalBytesPerInterval = 0;
    int nominalBytesPerTransfer = 0;
    int capacityRatioPermille = 0;

    RawPcmAdapterDecision adapter{};
    RawResamplerPlan resampler{};
    RawUac20ProfileRisk risks = RawUac20ProfileRisk::None;
    std::string reason;
    std::string summary;
};

struct RawUac20FormatMatchResult {
    bool matched = false;
    Uac20AltSnapshot selectedAlt{};
    RawUac20FormatCandidate selectedCandidate{};
    std::vector<RawUac20FormatCandidate> candidates;

    int sampleRate = 0;
    int channels = 0;
    int validBits = 0;
    int subslotBytes = 0;
    int frameBytes = 0;
    int bytesPerSecond = 0;
    RawAudioTransportKind transport = RawAudioTransportKind::Pcm;
    RawPcmAdapterDecision adapter{};
    RawResamplerPlan resampler{};
    std::string reason;
    std::string summary;
};

const char* rawUac20PcmOutputPreferenceName(RawUac20PcmOutputPreference pref);
std::string rawUac20ProfileRiskToString(RawUac20ProfileRisk risks);

RawUac20FormatMatchRequest normalizeRawUac20FormatMatchRequest(
        const RawUac20FormatMatchRequest& request);

RawUac20FormatMatchResult matchRawUac20Format(
        const Uac20DescriptorSnapshot& snapshot,
        const RawUac20FormatMatchRequest& request);

std::string describeRawUac20FormatCandidate(const RawUac20FormatCandidate& candidate);
std::string describeRawUac20FormatMatchResult(const RawUac20FormatMatchResult& result);

} // namespace rawsmusic::usb
