#include "raw_uac20_format_matcher.h"

#include "raw_audio_safety.h"

#include <algorithm>
#include <cmath>
#include <cstdint>
#include <sstream>

namespace rawsmusic::usb {
namespace {

int ceilDiv64(int64_t a, int64_t b) {
    if (b <= 0) return 0;
    if (a <= 0) return 0;
    return static_cast<int>((a + b - 1) / b);
}

int highSpeedIntervalsPerSecondForMatch(int interval) {
    if (interval <= 1) return 8000;
    if (interval > 16) return 1000;
    const int divisor = 1 << std::min(12, interval - 1);
    return std::max(1, 8000 / divisor);
}

bool isExplicitFeedbackEligible(const Uac20AltSnapshot& alt) {
    return alt.hasFeedbackEndpoint && alt.feedbackEndpoint.address != 0 && alt.feedbackEndpoint.usageType == 1;
}

bool exactPcmPreferenceMatch(int bits, int subslot, RawUac20PcmOutputPreference pref) {
    switch (pref) {
        case RawUac20PcmOutputPreference::Packed16: return bits == 16 && subslot == 2;
        case RawUac20PcmOutputPreference::Packed24: return bits == 24 && subslot == 3;
        case RawUac20PcmOutputPreference::Container32: return subslot == 4 && bits >= 24;
        case RawUac20PcmOutputPreference::Auto:
        default:
            return true;
    }
}

RawUac20ProfileRisk addRisk(RawUac20ProfileRisk risks, RawUac20ProfileRisk bit) {
    return static_cast<RawUac20ProfileRisk>(static_cast<int>(risks) | static_cast<int>(bit));
}

bool isWidenAdapter(RawPcmAdapterMode mode) {
    return mode == RawPcmAdapterMode::S16ToS24 ||
           mode == RawPcmAdapterMode::S16ToS32 ||
           mode == RawPcmAdapterMode::S24ToS32 ||
           mode == RawPcmAdapterMode::S32ToS24InS32 ||
           mode == RawPcmAdapterMode::Pcm24In32ToContainer32 ||
           mode == RawPcmAdapterMode::Pcm24In32ToPacked24 ||
           mode == RawPcmAdapterMode::Packed24ToPcm24In32;
}

int adapterScore(const RawPcmAdapterDecision& adapter) {
    if (!adapter.supported) return -5000;
    if (adapter.mode == RawPcmAdapterMode::None) return 2000;
    if (adapter.mode == RawPcmAdapterMode::Pcm24In32ToContainer32) return 2400;
    if (isWidenAdapter(adapter.mode)) return 1200;
    if (adapter.mode == RawPcmAdapterMode::S32ToS24) return 650;
    return 300;
}

std::string endpointSyncName(int syncType) {
    switch (syncType) {
        case 1: return "async";
        case 2: return "adaptive";
        case 3: return "sync";
        default: return "unknown";
    }
}

RawUac20FormatCandidate scoreCandidate(
        const Uac20AltSnapshot& alt,
        const RawUac20FormatMatchRequest& req) {
    RawUac20FormatCandidate c{};
    c.alt = alt;
    c.effectiveSampleRate = req.transport == RawAudioTransportKind::NativeDsd && req.nativeDsdSampleRate > 0
            ? req.nativeDsdSampleRate
            : (req.transport == RawAudioTransportKind::DoP && req.dopSampleRate > 0
                    ? req.dopSampleRate
                    : req.targetSampleRate);
    c.effectiveChannels = alt.channels > 0 ? alt.channels : req.targetChannels;
    c.effectiveBits = alt.validBits > 0 ? alt.validBits : req.targetBits;
    c.effectiveSubslotBytes = alt.subslotBytes > 0 ? alt.subslotBytes : req.targetSubslotBytes;
    c.frameBytes = c.effectiveChannels * c.effectiveSubslotBytes;
    c.explicitFeedbackEligible = isExplicitFeedbackEligible(alt);
    c.rateOk = true; // UAC2 rates often live behind clock-source RANGE/CUR; clock code verifies later.

    if (!alt.hasOutEndpoint || alt.outEndpoint.address == 0) {
        c.reason = "no-out-endpoint";
        c.summary = describeRawUac20FormatCandidate(c);
        return c;
    }
    if (!alt.outEndpoint.isIsochronous) {
        c.reason = "out-not-isochronous";
        c.summary = describeRawUac20FormatCandidate(c);
        return c;
    }
    if (c.effectiveSampleRate <= 0 || c.effectiveChannels <= 0 || c.effectiveBits <= 0 || c.effectiveSubslotBytes <= 0 || c.frameBytes <= 0) {
        c.reason = "invalid-effective-format";
        c.summary = describeRawUac20FormatCandidate(c);
        return c;
    }

    RawPcmFormatSpec source{
            req.sourceSampleRate,
            req.sourceChannels,
            req.sourceBits,
            req.sourceBytesPerSample};
    RawPcmFormatSpec device{
            c.effectiveSampleRate,
            c.effectiveChannels,
            c.effectiveBits,
            c.effectiveSubslotBytes};

    c.adapter = chooseRawPcmAdapter(
            source.channels,
            source.validBits,
            source.containerBytesPerSample,
            device.channels,
            device.validBits,
            device.containerBytesPerSample,
            req.bitPerfect);
    c.resampler = buildRawResamplerPlan(source, device, req.transport, req.bitPerfect);
    c.adapterSupported = c.adapter.supported || req.transport != RawAudioTransportKind::Pcm;

    const bool nativeDsdRequested = req.transport == RawAudioTransportKind::NativeDsd || req.nativeDsdRaw;
    const bool dopRequested = req.transport == RawAudioTransportKind::DoP || req.forceDoP24;
    if (nativeDsdRequested) {
        c.formatOk = c.effectiveChannels == req.targetChannels &&
                c.effectiveBits == req.targetBits &&
                c.effectiveSubslotBytes == req.targetSubslotBytes;
        c.adapterSupported = c.formatOk;
    } else if (dopRequested) {
        c.formatOk = c.effectiveChannels == req.targetChannels && c.effectiveSubslotBytes == 3;
        c.adapterSupported = c.formatOk;
    } else {
        c.formatOk = c.adapterSupported;
    }

    c.serviceIntervalsPerSecond = highSpeedIntervalsPerSecondForMatch(alt.outEndpoint.interval);
    const int64_t requiredBps = static_cast<int64_t>(c.effectiveSampleRate) * c.frameBytes;
    c.nominalBytesPerInterval = ceilDiv64(requiredBps, std::max(1, c.serviceIntervalsPerSecond));
    c.nominalBytesPerTransfer = c.nominalBytesPerInterval * defaultRawAudioSafetyPolicy().isoPacketsPerTransfer;
    c.capacityOk = alt.outEndpoint.maxPacketSize <= 0 || alt.outEndpoint.maxPacketSize >= c.nominalBytesPerInterval;
    c.capacityRatioPermille = c.nominalBytesPerInterval > 0 && alt.outEndpoint.maxPacketSize > 0
            ? static_cast<int>((static_cast<int64_t>(alt.outEndpoint.maxPacketSize) * 1000LL) / c.nominalBytesPerInterval)
            : 0;

    if (!c.capacityOk) c.risks = addRisk(c.risks, RawUac20ProfileRisk::LowCapacity);
    if (alt.outEndpoint.syncType == 1 && !alt.hasFeedbackEndpoint) c.risks = addRisk(c.risks, RawUac20ProfileRisk::AsyncWithoutFeedback);
    if (alt.hasFeedbackEndpoint && !c.explicitFeedbackEligible) c.risks = addRisk(c.risks, RawUac20ProfileRisk::FeedbackNonStandard);
    if (alt.outEndpoint.syncType == 0) c.risks = addRisk(c.risks, RawUac20ProfileRisk::UnknownSync);
    if (alt.clockSourceId != 0) c.risks = addRisk(c.risks, RawUac20ProfileRisk::ClockUnverified);
    if (c.adapterSupported && c.adapter.mode != RawPcmAdapterMode::None && req.transport == RawAudioTransportKind::Pcm) {
        c.risks = addRisk(c.risks, RawUac20ProfileRisk::FormatAdapterRequired);
    }
    if (c.adapter.dropsLowBits) c.risks = addRisk(c.risks, RawUac20ProfileRisk::LossyAdapter);

    int score = 0;
    if (nativeDsdRequested) {
        score += c.formatOk ? 6000 : -8000;
        if (c.effectiveSubslotBytes == req.targetSubslotBytes) score += 2400; else score -= 3000;
        if (c.effectiveBits == req.targetBits) score += 2400; else score -= 3000;
        if (req.nativeDsdSampleRate > 0) score += 2200;
    } else if (dopRequested) {
        score += c.effectiveSubslotBytes == 3 ? 5000 : -5000;
        score += c.effectiveBits == 24 ? 2200 : -2200;
        if (req.dopSampleRate > 0) score += 1200;
    } else {
        score += adapterScore(c.adapter);
        score += 1000; // PCM alt, since descriptor snapshot only includes Type-I playback alts.
    }

    score += c.rateOk ? 1000 : -2000;
    if (c.effectiveChannels == req.targetChannels) score += 800;
    else if (c.effectiveChannels > req.targetChannels) score += 100;
    else score -= 500;

    if (!nativeDsdRequested && !dopRequested) {
        if (c.effectiveBits == req.targetBits) score += 800;
        else if (c.effectiveBits > req.targetBits && c.adapterSupported) score += 400;
        else score -= 800;

        const int desiredSubslot = req.targetSubslotBytes > 0
                ? req.targetSubslotBytes
                : (req.targetBits > 16 && req.prefer24In32 ? 4 : std::max(1, (req.targetBits + 7) / 8));
        if (c.effectiveSubslotBytes == desiredSubslot) score += 300;
        if (req.sourceBits == 24 && req.sourceBytesPerSample == 4 && req.prefer24In32 &&
            req.pcmPreference == RawUac20PcmOutputPreference::Auto) {
            if (c.effectiveBits == 32 && c.effectiveSubslotBytes == 4 &&
                c.adapter.mode == RawPcmAdapterMode::Pcm24In32ToContainer32) {
                score += 2400;
            } else if (c.effectiveBits == 24 && c.effectiveSubslotBytes == 3 &&
                       c.adapter.mode == RawPcmAdapterMode::Pcm24In32ToPacked24) {
                score -= 900;
            }
        }

        if (req.targetBits == 24 && req.targetSubslotBytes == 3) {
            if (c.effectiveBits == 24 && c.effectiveSubslotBytes == 3) score += 1200;
            else if (c.effectiveSubslotBytes == 4) score -= 400;
        }

        if (req.pcmPreference != RawUac20PcmOutputPreference::Auto) {
            if (exactPcmPreferenceMatch(c.effectiveBits, c.effectiveSubslotBytes, req.pcmPreference)) score += 3000;
            else score -= 1500;
        }
    }

    if (c.capacityOk) {
        score += 600;
        if (c.capacityRatioPermille >= 2000) score += 150;
    } else {
        score -= 4000;
    }

    switch (alt.outEndpoint.syncType) {
        case 1: score += c.explicitFeedbackEligible ? 520 : 160; break;
        case 2: score += 240; break;
        case 3: score += 100; break;
        default: score -= 120; break;
    }
    if (c.explicitFeedbackEligible) score += 320;
    else if (alt.hasFeedbackEndpoint) score -= 120;
    if (req.noFeedbackPolicy && alt.hasFeedbackEndpoint) score -= 900;

    if (req.safeMode) {
        if (c.capacityRatioPermille >= 1400) score += 260;
        if (c.risks == RawUac20ProfileRisk::None || c.risks == RawUac20ProfileRisk::ClockUnverified) score += 220;
        if (rawUac20RiskHas(c.risks, RawUac20ProfileRisk::FeedbackNonStandard)) score -= 250;
        if (!nativeDsdRequested && !dopRequested) {
            if (c.effectiveBits == 16 && c.effectiveSubslotBytes == 2) score += 3000;
            else if (c.effectiveBits == 24 && c.effectiveSubslotBytes == 3) score += 2200;
            else if (c.effectiveBits >= 32 || c.effectiveSubslotBytes >= 4) score -= 600;
        }
    } else if (!nativeDsdRequested && !dopRequested && !req.bitPerfect) {
        if (c.effectiveBits == 16 && c.effectiveSubslotBytes == 2) score += 900;
        else if (c.effectiveBits == 24 && c.effectiveSubslotBytes == 3) score += 550;
        else if (c.effectiveBits >= 32 || c.effectiveSubslotBytes >= 4) score -= 700;
    }

    if (!nativeDsdRequested && !dopRequested && !req.bitPerfect &&
        req.pcmPreference == RawUac20PcmOutputPreference::Auto && req.targetBits >= 24 && req.targetSubslotBytes == 4) {
        if (c.effectiveBits == 32 && c.effectiveSubslotBytes == 4) score += 4200;
        else if (c.effectiveSubslotBytes == 4 && c.effectiveBits >= 24) score += 2200;
        else if (c.effectiveBits == 24 && c.effectiveSubslotBytes == 3) score -= 1400;
    }

    if (req.javaHintInterface >= 0 && alt.interfaceNumber == req.javaHintInterface) score += 100;
    if (req.javaHintAlt > 0 && alt.altSetting == req.javaHintAlt) score += 100;
    if (req.lastGoodAlt > 0 && alt.altSetting == req.lastGoodAlt) score += 1600;
    if (req.lastGoodSampleRate > 0) score += 800;
    if (req.lastGoodBits > 0 && c.effectiveBits == req.lastGoodBits) score += 500;
    if (req.lastGoodSubslotBytes > 0 && c.effectiveSubslotBytes == req.lastGoodSubslotBytes) score += 500;
    if (req.lastGoodFeedbackEndpoint > 0 && alt.hasFeedbackEndpoint && alt.feedbackEndpoint.address == req.lastGoodFeedbackEndpoint) score += 250;

    score -= alt.altSetting * (req.bitPerfect || dopRequested || nativeDsdRequested ? 2 : 40);

    c.compatible = c.formatOk && c.rateOk && c.capacityOk && c.adapterSupported;
    if (!c.formatOk) score -= 3000;
    if (!c.adapterSupported) score -= 4000;
    c.score = score;
    c.reason = c.compatible ? "compatible" : "compatible-with-risk-or-adapter-rejected";
    c.summary = describeRawUac20FormatCandidate(c);
    return c;
}

} // namespace

const char* rawUac20PcmOutputPreferenceName(RawUac20PcmOutputPreference pref) {
    switch (pref) {
        case RawUac20PcmOutputPreference::Auto: return "AUTO";
        case RawUac20PcmOutputPreference::Packed16: return "PACKED_16";
        case RawUac20PcmOutputPreference::Packed24: return "PACKED_24";
        case RawUac20PcmOutputPreference::Container32: return "CONTAINER_32";
        default: return "UNKNOWN";
    }
}

std::string rawUac20ProfileRiskToString(RawUac20ProfileRisk risks) {
    if (risks == RawUac20ProfileRisk::None) return "none";
    std::ostringstream os;
    bool first = true;
    auto add = [&](RawUac20ProfileRisk bit, const char* name) {
        if (!rawUac20RiskHas(risks, bit)) return;
        if (!first) os << '|';
        os << name;
        first = false;
    };
    add(RawUac20ProfileRisk::LowCapacity, "low-capacity");
    add(RawUac20ProfileRisk::AsyncWithoutFeedback, "async-no-feedback");
    add(RawUac20ProfileRisk::FeedbackNonStandard, "feedback-nonstandard");
    add(RawUac20ProfileRisk::UnknownSync, "unknown-sync");
    add(RawUac20ProfileRisk::ClockUnverified, "clock-unverified");
    add(RawUac20ProfileRisk::FormatAdapterRequired, "format-adapter");
    add(RawUac20ProfileRisk::LossyAdapter, "lossy-adapter");
    return os.str();
}

RawUac20FormatMatchRequest normalizeRawUac20FormatMatchRequest(
        const RawUac20FormatMatchRequest& request) {
    RawUac20FormatMatchRequest r = request;
    if (r.sourceSampleRate <= 0) r.sourceSampleRate = r.targetSampleRate;
    if (r.sourceChannels <= 0) r.sourceChannels = r.targetChannels;
    if (r.sourceBits <= 0) r.sourceBits = r.targetBits;
    if (r.sourceBytesPerSample <= 0) {
        // Mirror the original engine: FFmpeg often exposes >16-bit PCM in a 32-bit container.
        r.sourceBytesPerSample = r.sourceBits > 16 ? 4 : std::max(1, (r.sourceBits + 7) / 8);
    }
    if (r.targetSampleRate <= 0) r.targetSampleRate = r.sourceSampleRate;
    if (r.targetChannels <= 0) r.targetChannels = r.sourceChannels;
    if (r.targetBits <= 0) r.targetBits = r.sourceBits;
    if (r.targetSubslotBytes <= 0) {
        if (r.transport == RawAudioTransportKind::DoP || r.forceDoP24) r.targetSubslotBytes = 3;
        else if (r.targetBits == 24 && r.prefer24In32) r.targetSubslotBytes = 4;
        else r.targetSubslotBytes = std::max(1, (r.targetBits + 7) / 8);
    }
    if (r.transport == RawAudioTransportKind::DoP) {
        r.targetBits = 24;
        r.targetSubslotBytes = 3;
        r.forceDoP24 = true;
    }
    return r;
}

RawUac20FormatMatchResult matchRawUac20Format(
        const Uac20DescriptorSnapshot& snapshot,
        const RawUac20FormatMatchRequest& request) {
    const RawUac20FormatMatchRequest req = normalizeRawUac20FormatMatchRequest(request);
    RawUac20FormatMatchResult result{};
    result.transport = req.transport;

    int bestIndex = -1;
    for (const Uac20AltSnapshot& alt : snapshot.streamingAlternates) {
        RawUac20FormatCandidate candidate = scoreCandidate(alt, req);
        result.candidates.push_back(candidate);
        if (bestIndex < 0 || candidate.score > result.candidates[static_cast<size_t>(bestIndex)].score) {
            bestIndex = static_cast<int>(result.candidates.size()) - 1;
        }
    }

    if (bestIndex < 0) {
        result.reason = "no-streaming-alternates";
        result.summary = describeRawUac20FormatMatchResult(result);
        return result;
    }

    RawUac20FormatCandidate& best = result.candidates[static_cast<size_t>(bestIndex)];
    best.selected = true;
    best.summary = describeRawUac20FormatCandidate(best);

    if (!best.compatible && best.score < -1000) {
        result.reason = "no-compatible-alt";
        result.selectedCandidate = best;
        result.summary = describeRawUac20FormatMatchResult(result);
        return result;
    }

    result.matched = true;
    result.selectedAlt = best.alt;
    result.selectedCandidate = best;
    result.sampleRate = best.effectiveSampleRate;
    result.channels = best.effectiveChannels;
    result.validBits = best.effectiveBits;
    result.subslotBytes = best.effectiveSubslotBytes;
    result.frameBytes = best.frameBytes;
    result.bytesPerSecond = result.sampleRate * result.frameBytes;
    result.adapter = best.adapter;
    result.resampler = best.resampler;
    result.reason = best.reason;
    result.summary = describeRawUac20FormatMatchResult(result);
    return result;
}

std::string describeRawUac20FormatCandidate(const RawUac20FormatCandidate& c) {
    std::ostringstream os;
    os << (c.selected ? "*" : "")
       << "if=" << c.alt.interfaceNumber
       << " alt=" << c.alt.altSetting
       << " score=" << c.score
       << " compat=" << (c.compatible ? "yes" : "no")
       << " fmt=" << c.effectiveSampleRate << "Hz/" << c.effectiveChannels << "ch/"
       << c.effectiveBits << "bit/subslot" << c.effectiveSubslotBytes
       << " frame=" << c.frameBytes
       << " out=0x" << std::hex << c.alt.outEndpoint.address
       << " fb=0x" << (c.alt.hasFeedbackEndpoint ? c.alt.feedbackEndpoint.address : 0)
       << std::dec
       << " sync=" << endpointSyncName(c.alt.outEndpoint.syncType)
       << " fbEligible=" << (c.explicitFeedbackEligible ? "yes" : "no")
       << " epMax=" << c.alt.outEndpoint.maxPacketSize
       << " nominal=" << c.nominalBytesPerInterval
       << " capRatio=" << c.capacityRatioPermille / 1000 << "." << c.capacityRatioPermille % 1000
       << " adapter=" << rawPcmAdapterModeName(c.adapter.mode)
       << " adapterOk=" << (c.adapterSupported ? "yes" : "no")
       << " risks=" << rawUac20ProfileRiskToString(c.risks)
       << " reason=" << c.reason;
    return os.str();
}

std::string describeRawUac20FormatMatchResult(const RawUac20FormatMatchResult& result) {
    std::ostringstream os;
    os << "matched=" << (result.matched ? "yes" : "no")
       << " reason=" << result.reason
       << " selected=";
    if (result.matched || result.selectedCandidate.alt.hasOutEndpoint) {
        os << describeRawUac20FormatCandidate(result.selectedCandidate);
    } else {
        os << "none";
    }
    os << " candidates=" << result.candidates.size();
    const size_t limit = std::min<size_t>(result.candidates.size(), 8);
    for (size_t i = 0; i < limit; ++i) {
        os << " {" << describeRawUac20FormatCandidate(result.candidates[i]) << "}";
    }
    if (result.candidates.size() > limit) os << " ...";
    return os.str();
}

} // namespace rawsmusic::usb
