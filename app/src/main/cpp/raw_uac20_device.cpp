#include "raw_uac20_device.h"

#include <algorithm>
#include <sstream>
#include <utility>

namespace {
std::string rawJsonEscape(const std::string& in) {
    std::string out;
    out.reserve(in.size() + 8);
    for (char c : in) {
        switch (c) {
            case '"': out += "\\\""; break;
            case '\\': out += "\\\\"; break;
            case '\n': out += "\\n"; break;
            case '\r': out += "\\r"; break;
            case '\t': out += "\\t"; break;
            default: out += c; break;
        }
    }
    return out;
}
} // namespace

namespace rawsmusic::usb {

const char* rawUac20DeviceStateName(RawUac20DeviceState state) {
    switch (state) {
        case RawUac20DeviceState::Closed: return "Closed";
        case RawUac20DeviceState::Opened: return "Opened";
        case RawUac20DeviceState::ParametersSet: return "ParametersSet";
        case RawUac20DeviceState::StreamPrepared: return "StreamPrepared";
        case RawUac20DeviceState::Streaming: return "Streaming";
        case RawUac20DeviceState::Draining: return "Draining";
        case RawUac20DeviceState::Standby: return "Standby";
        case RawUac20DeviceState::Recovering: return "Recovering";
        case RawUac20DeviceState::Detached: return "Detached";
        case RawUac20DeviceState::Dead: return "Dead";
        default: return "Unknown";
    }
}

RawUac20Device::RawUac20Device()
        : session_(std::make_unique<Uac20Session>()),
          safetyPolicy_(defaultRawAudioSafetyPolicy()) {
    rebuildPlanningSummariesLocked();
}

RawUac20Device::~RawUac20Device() {
    closeStream("raw_uac20_device_destructor");
}

bool RawUac20Device::openFromFd(int fd, const char* deviceName) {
    if (!session_) session_ = std::make_unique<Uac20Session>();
    if (state_ != RawUac20DeviceState::Closed) {
        closeStream("raw_uac20_reopen");
        session_ = std::make_unique<Uac20Session>();
    }
    deviceName_ = deviceName ? deviceName : "";
    if (!session_->openFromFd(fd)) {
        lastError_ = "openFromFd-failed";
        transitionTo(RawUac20DeviceState::Closed, "open-failed");
        return false;
    }
    lastError_.clear();
    transitionTo(RawUac20DeviceState::Opened, "open-ok");
    return true;
}

bool RawUac20Device::setParameters(const RawUac20AudioParams& audioParams) {
    audioParams_ = audioParams;
    if (audioParams_.sourceBytesPerSample <= 0) {
        audioParams_.sourceBytesPerSample = std::max(1, (audioParams_.sourceBits + 7) / 8);
    }
    if (audioParams_.requestedChannels <= 0) {
        audioParams_.requestedChannels = audioParams_.sourceChannels;
    }
    if (audioParams_.requestedSampleRate <= 0) {
        audioParams_.requestedSampleRate = audioParams_.sourceSampleRate;
    }
    if (audioParams_.requestedBits <= 0) {
        audioParams_.requestedBits = audioParams_.sourceBits;
    }
    if (audioParams_.requestedSubslotBytes <= 0) {
        audioParams_.requestedSubslotBytes = std::max(1, (audioParams_.requestedBits + 7) / 8);
    }

    rebuildPlanningSummariesLocked();
    if (state_ == RawUac20DeviceState::Closed || !session_) {
        lastError_ = "setParameters-before-open";
        return false;
    }

    const Uac20Params legacy = buildLegacyParamsLocked();
    if (!session_->prepare(legacy)) {
        lastError_ = "prepare-failed";
        transitionTo(RawUac20DeviceState::Opened, "prepare-failed");
        return false;
    }
    lastError_.clear();
    transitionTo(RawUac20DeviceState::ParametersSet, "set-parameters-ok");
    transitionTo(RawUac20DeviceState::StreamPrepared, "prepare-ok");
    return true;
}

bool RawUac20Device::setParametersRawDirect(const RawUac20RawParams& rawParams) {
    rawParams_ = rawParams;
    rebuildPlanningSummariesLocked();
    if (state_ == RawUac20DeviceState::Closed || !session_) {
        lastError_ = "setParametersRawDirect-before-open";
        return false;
    }
    const Uac20Params legacy = buildLegacyParamsLocked();
    if (!session_->prepare(legacy)) {
        lastError_ = "raw-direct-prepare-failed";
        transitionTo(RawUac20DeviceState::Opened, "raw-direct-prepare-failed");
        return false;
    }
    lastError_.clear();
    transitionTo(RawUac20DeviceState::ParametersSet, "raw-direct-ok");
    transitionTo(RawUac20DeviceState::StreamPrepared, "raw-direct-prepare-ok");
    return true;
}

bool RawUac20Device::startStream() {
    if (!session_) {
        lastError_ = "start-before-session";
        return false;
    }
    if (state_ != RawUac20DeviceState::StreamPrepared && state_ != RawUac20DeviceState::Standby) {
        lastError_ = "start-before-prepare";
        return false;
    }
    if (!session_->start()) {
        lastError_ = "start-failed";
        return false;
    }
    lastError_.clear();
    transitionTo(RawUac20DeviceState::Streaming, "start-ok");
    return true;
}

void RawUac20Device::stopStream(const char* reason) {
    if (session_) session_->stop(reason ? reason : "raw_uac20_stop");
    if (state_ == RawUac20DeviceState::Streaming || state_ == RawUac20DeviceState::Draining) {
        transitionTo(RawUac20DeviceState::StreamPrepared, reason ? reason : "stop");
    }
}

void RawUac20Device::standby(const char* reason) {
    if (session_) session_->stop(reason ? reason : "raw_uac20_standby");
    if (state_ != RawUac20DeviceState::Closed) {
        transitionTo(RawUac20DeviceState::Standby, reason ? reason : "standby");
    }
}

void RawUac20Device::closeStream(const char* reason) {
    if (session_) session_->close(reason ? reason : "raw_uac20_close");
    transitionTo(RawUac20DeviceState::Closed, reason ? reason : "close");
}

int RawUac20Device::write(const uint8_t* data, int length) {
    if (!session_) return -1003;
    const int frameBytes = std::max(1, audioParams_.requestedChannels) *
            std::max(1, audioParams_.requestedSubslotBytes);
    const auto alignment = rawValidateFrameAlignedBytes(length, frameBytes, "RawUac20Device::write");
    if (!alignment.valid || alignment.alignedBytes <= 0) return 0;
    return session_->write(data, alignment.alignedBytes);
}

RawUac20DeviceStatus RawUac20Device::status() const {
    RawUac20DeviceStatus s{};
    s.state = state_;
    s.opened = state_ != RawUac20DeviceState::Closed;
    s.parametersSet = state_ == RawUac20DeviceState::ParametersSet ||
            state_ == RawUac20DeviceState::StreamPrepared ||
            state_ == RawUac20DeviceState::Streaming ||
            state_ == RawUac20DeviceState::Standby;
    s.streaming = state_ == RawUac20DeviceState::Streaming;
    s.deviceName = deviceName_;
    s.lastError = lastError_;
    s.safetyPolicySummary = describeRawAudioSafetyPolicy(safetyPolicy_);
    s.adapterDecisionSummary = describeRawPcmAdapterDecision(adapterDecision_);
    s.formatMatchSummary = formatMatchRequestSummary_;
    s.resamplerPlanSummary = describeRawResamplerPlan(resamplerPlan_);
    s.runtimeJson = runtimeJson();
    return s;
}

std::string RawUac20Device::runtimeJson() const {
    if (!session_) return "{}";
    std::ostringstream os;
    os << "{\"rawUac20DeviceState\":\"" << rawUac20DeviceStateName(state_) << "\",";
    os << "\"rawDeviceName\":\"" << rawJsonEscape(deviceName_) << "\",";
    os << "\"rawSafetyPolicy\":\"" << rawJsonEscape(describeRawAudioSafetyPolicy(safetyPolicy_)) << "\",";
    os << "\"rawAdapterDecision\":\"" << rawJsonEscape(describeRawPcmAdapterDecision(adapterDecision_)) << "\",";
    os << "\"rawFormatMatchRequest\":\"" << rawJsonEscape(formatMatchRequestSummary_) << "\",";
    os << "\"rawResamplerPlan\":\"" << rawJsonEscape(describeRawResamplerPlan(resamplerPlan_)) << "\",";
    os << "\"legacyRuntime\":" << session_->runtimeJson() << "}";
    return os.str();
}

Uac20Session* RawUac20Device::legacySession() {
    return session_.get();
}

const Uac20Session* RawUac20Device::legacySession() const {
    return session_.get();
}

Uac20Params RawUac20Device::buildLegacyParamsLocked() const {
    Uac20Params p{};
    p.sourceSampleRate = audioParams_.sourceSampleRate;
    p.sourceBits = audioParams_.sourceBits;
    p.sourceChannels = audioParams_.sourceChannels;
    p.requestedSampleRate = audioParams_.requestedSampleRate;
    p.requestedBits = audioParams_.requestedBits;
    p.requestedSubslotBytes = audioParams_.requestedSubslotBytes;
    p.resetAltBeforeStart = rawParams_.resetAltBeforeStart;
    p.preferExplicitFeedback = rawParams_.preferExplicitFeedback;
    p.forbidLearnedNoFeedback = rawParams_.forbidLearnedNoFeedback;
    p.minimalMixerControl = rawParams_.minimalMixerControl;
    p.prefer24In32 = audioParams_.prefer24In32;
    p.fullReopenOnNotOutputting = rawParams_.fullReopenOnNotOutputting;
    p.enableDebugRealOutSubmitter = rawParams_.enableDebugRealOutSubmitter;
    p.debugRealOutFeedFromWriteRing = rawParams_.debugRealOutFeedFromWriteRing;
    p.debugRealOutAutoResubmit = rawParams_.debugRealOutAutoResubmit;
    p.enableRawStreamRealOutTakeover = rawParams_.enableRawStreamRealOutTakeover;
    p.enableDebugPlaybackRuntimeGuard = rawParams_.enableDebugPlaybackRuntimeGuard;
    p.enableDebugRecoveryExecutor = rawParams_.enableDebugRecoveryExecutor;
    p.enableDebugFormatFallbackExecutor = rawParams_.enableDebugFormatFallbackExecutor;
    p.debugRealOutMaxCallbacks = rawParams_.debugRealOutMaxCallbacks;
    p.debugRealOutMaxRunMs = rawParams_.debugRealOutMaxRunMs;
    p.debugRealOutPrebufferMs = rawParams_.debugRealOutPrebufferMs;
    return p;
}

void RawUac20Device::rebuildPlanningSummariesLocked() {
    const RawPcmFormatSpec source{
            audioParams_.sourceSampleRate,
            audioParams_.sourceChannels,
            audioParams_.sourceBits,
            audioParams_.sourceBytesPerSample > 0
                    ? audioParams_.sourceBytesPerSample
                    : std::max(1, (audioParams_.sourceBits + 7) / 8)};
    const RawPcmFormatSpec device{
            audioParams_.requestedSampleRate,
            audioParams_.requestedChannels > 0 ? audioParams_.requestedChannels : audioParams_.sourceChannels,
            audioParams_.requestedBits,
            audioParams_.requestedSubslotBytes > 0
                    ? audioParams_.requestedSubslotBytes
                    : std::max(1, (audioParams_.requestedBits + 7) / 8)};
    adapterDecision_ = chooseRawPcmAdapter(
            source.channels,
            source.validBits,
            source.containerBytesPerSample,
            device.channels,
            device.validBits,
            device.containerBytesPerSample,
            audioParams_.bitPerfect);
    resamplerPlan_ = buildRawResamplerPlan(source, device, audioParams_.transport, audioParams_.bitPerfect);

    formatMatchRequest_ = RawUac20FormatMatchRequest{};
    formatMatchRequest_.sourceSampleRate = source.sampleRate;
    formatMatchRequest_.sourceChannels = source.channels;
    formatMatchRequest_.sourceBits = source.validBits;
    formatMatchRequest_.sourceBytesPerSample = source.containerBytesPerSample;
    formatMatchRequest_.targetSampleRate = device.sampleRate;
    formatMatchRequest_.targetChannels = device.channels;
    formatMatchRequest_.targetBits = device.validBits;
    formatMatchRequest_.targetSubslotBytes = device.containerBytesPerSample;
    formatMatchRequest_.transport = audioParams_.transport;
    formatMatchRequest_.bitPerfect = audioParams_.bitPerfect;
    formatMatchRequest_.prefer24In32 = audioParams_.prefer24In32;
    formatMatchRequest_.preferExplicitFeedback = rawParams_.preferExplicitFeedback;
    const auto normalized = normalizeRawUac20FormatMatchRequest(formatMatchRequest_);
    std::ostringstream os;
    os << "transport=" << rawAudioTransportKindName(normalized.transport)
       << " bitPerfect=" << (normalized.bitPerfect ? "yes" : "no")
       << " src=" << normalized.sourceSampleRate << "Hz/" << normalized.sourceChannels << "ch/"
       << normalized.sourceBits << "bit/" << normalized.sourceBytesPerSample << "B"
       << " target=" << normalized.targetSampleRate << "Hz/" << normalized.targetChannels << "ch/"
       << normalized.targetBits << "bit/subslot" << normalized.targetSubslotBytes
       << " prefer24In32=" << (normalized.prefer24In32 ? "yes" : "no")
       << " preferFb=" << (normalized.preferExplicitFeedback ? "yes" : "no");
    formatMatchRequestSummary_ = os.str();
}

void RawUac20Device::transitionTo(RawUac20DeviceState state, const char*) {
    state_ = state;
}

} // namespace rawsmusic::usb
