#pragma once

#include <cstdint>
#include <memory>
#include <string>

#include "raw_audio_format_policy.h"
#include "raw_uac20_format_matcher.h"
#include "raw_audio_safety.h"
#include "usb_uac20_session.h"

namespace rawsmusic::usb {

enum class RawUac20DeviceState : int {
    Closed = 0,
    Opened = 1,
    ParametersSet = 2,
    StreamPrepared = 3,
    Streaming = 4,
    Draining = 5,
    Standby = 6,
    Recovering = 7,
    Detached = 8,
    Dead = 9,
};

struct RawUac20AudioParams {
    int sourceSampleRate = 0;
    int sourceBits = 0;
    int sourceChannels = 0;
    int sourceBytesPerSample = 0;

    int requestedSampleRate = 0;
    int requestedBits = 0;
    int requestedSubslotBytes = 0;
    int requestedChannels = 0;

    RawAudioTransportKind transport = RawAudioTransportKind::Pcm;
    bool bitPerfect = true;
    bool prefer24In32 = true;
};

struct RawUac20RawParams {
    bool resetAltBeforeStart = true;
    bool preferExplicitFeedback = true;
    bool forbidLearnedNoFeedback = true;
    bool minimalMixerControl = true;
    bool fullReopenOnNotOutputting = true;

    bool enableDebugRealOutSubmitter = false;
    bool debugRealOutFeedFromWriteRing = false;
    bool debugRealOutAutoResubmit = false;
    bool enableRawStreamRealOutTakeover = false;
    bool enableDebugPlaybackRuntimeGuard = false;
    bool enableDebugRecoveryExecutor = false;
    bool enableDebugFormatFallbackExecutor = false;
    int debugRealOutMaxCallbacks = 0;
    int debugRealOutMaxRunMs = 0;
    int debugRealOutPrebufferMs = 20;
};

struct RawUac20DeviceStatus {
    RawUac20DeviceState state = RawUac20DeviceState::Closed;
    bool opened = false;
    bool parametersSet = false;
    bool streaming = false;
    std::string deviceName;
    std::string lastError;
    std::string safetyPolicySummary;
    std::string resamplerPlanSummary;
    std::string adapterDecisionSummary;
    std::string formatMatchSummary;
    std::string runtimeJson;
};

const char* rawUac20DeviceStateName(RawUac20DeviceState state);

// Step 89: UAC20 device facade.  It deliberately wraps the existing
// Uac20Session instead of replacing it immediately, so the 2-month legacy/native
// behaviour can be migrated module-by-module without deleting working code.
class RawUac20Device {
public:
    RawUac20Device();
    ~RawUac20Device();

    RawUac20Device(const RawUac20Device&) = delete;
    RawUac20Device& operator=(const RawUac20Device&) = delete;

    bool openFromFd(int fd, const char* deviceName = nullptr);
    bool setParameters(const RawUac20AudioParams& audioParams);
    bool setParametersRawDirect(const RawUac20RawParams& rawParams);

    bool startStream();
    void stopStream(const char* reason = "raw_uac20_stop");
    void standby(const char* reason = "raw_uac20_standby");
    void closeStream(const char* reason = "raw_uac20_close");

    int write(const uint8_t* data, int length);

    RawUac20DeviceStatus status() const;
    std::string runtimeJson() const;

    Uac20Session* legacySession();
    const Uac20Session* legacySession() const;

private:
    Uac20Params buildLegacyParamsLocked() const;
    void rebuildPlanningSummariesLocked();
    void transitionTo(RawUac20DeviceState state, const char* reason = nullptr);

private:
    std::unique_ptr<Uac20Session> session_;
    RawUac20DeviceState state_ = RawUac20DeviceState::Closed;
    RawUac20AudioParams audioParams_{};
    RawUac20RawParams rawParams_{};
    RawAudioSafetyPolicy safetyPolicy_{};
    RawPcmAdapterDecision adapterDecision_{};
    RawResamplerPlan resamplerPlan_{};
    RawUac20FormatMatchRequest formatMatchRequest_{};
    std::string formatMatchRequestSummary_;
    std::string deviceName_;
    std::string lastError_;
};

} // namespace rawsmusic::usb
